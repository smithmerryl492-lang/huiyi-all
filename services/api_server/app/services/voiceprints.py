import re
import uuid
from collections import defaultdict
from pathlib import Path
from typing import Any

from fastapi import HTTPException

from app import repositories
from app.core.config import settings
from app.schemas import MeetingTask, SpeakerProfile, TranscriptSegment
from app.services.asr_client import enroll_voiceprint_audio_with_asr_service, extract_voiceprints_with_asr_service


def store_task_speaker_embeddings(user_id: str, task_id: str, transcripts: list[TranscriptSegment]) -> None:
    groups = _embedding_groups_from_transcripts(transcripts)
    repositories.replace_task_speaker_embeddings(user_id, task_id, groups)


def identify_task_speakers(
    user_id: str,
    task: MeetingTask,
    transcripts: list[TranscriptSegment],
    participant_names: list[str] | None = None,
    profile_ids: list[str] | None = None,
) -> tuple[list[TranscriptSegment], int, int]:
    if not settings.voiceprint_enabled:
        return _strip_voiceprint_fields(transcripts), 0, 0
    groups = _embedding_groups_from_transcripts(transcripts)
    if not groups:
        return _strip_voiceprint_fields(transcripts), 0, 0

    profiles = _candidate_profiles(user_id, participant_names or [], profile_ids or [])
    if not profiles:
        return _strip_voiceprint_fields(transcripts), 0, 0

    matches = _match_groups(groups, profiles)
    if not matches:
        return _strip_voiceprint_fields(transcripts), 0, len(profiles)

    output: list[TranscriptSegment] = []
    for segment in transcripts:
        key = _segment_speaker_key(segment)
        match = matches.get(key)
        update = {"voiceprint_embedding": None, "voiceprint_quality": None}
        if match is not None:
            update.update(
                {
                    "speaker": match["display_name"],
                    "speaker_id": match["profile_id"],
                    "confidence": match["score"],
                }
            )
        output.append(segment.model_copy(update=update))
    return output, len(matches), len(profiles)


def identify_task_speakers_from_audio(
    user_id: str,
    task: MeetingTask,
    source_file_path: str,
    transcripts: list[TranscriptSegment],
    participant_names: list[str] | None = None,
    profile_ids: list[str] | None = None,
    require_profiles: bool = True,
) -> tuple[list[TranscriptSegment], int, int, bool]:
    if not settings.voiceprint_enabled or not transcripts:
        return transcripts, 0, 0, False
    participant_names = participant_names or []
    profile_ids = profile_ids or []
    profiles = _candidate_profiles(user_id, participant_names, profile_ids)
    if require_profiles and not profiles:
        return transcripts, 0, 0, False

    enriched = extract_voiceprints_with_asr_service(task.id, source_file_path, transcripts)
    if not enriched:
        return transcripts, 0, len(profiles), False
    store_task_speaker_embeddings(user_id, task.id, enriched)
    if not profiles:
        return _strip_voiceprint_fields(enriched), 0, 0, True
    identified, matched_count, profile_count = identify_task_speakers(
        user_id,
        task,
        enriched,
        participant_names=participant_names,
        profile_ids=profile_ids,
    )
    return identified, matched_count, profile_count, True


def enroll_profile_from_task_speaker(
    user_id: str,
    task_id: str,
    display_name: str,
    speaker_id: str | None = None,
    speaker_name: str | None = None,
    profile_id: str | None = None,
) -> SpeakerProfile:
    task = repositories.get_task_for_user(task_id, user_id)
    embeddings = _task_speaker_embeddings_for_enrollment(user_id, task)
    target = _find_task_embedding(embeddings, speaker_id, speaker_name)
    if target is None:
        raise HTTPException(status_code=422, detail="该说话人的声纹样本还未提取完成，请稍后再试")
    _validate_enrollment_sample(target)
    return repositories.upsert_speaker_profile_sample(
        user_id=user_id,
        display_name=display_name,
        embedding=target["embedding"],
        quality=float(target.get("quality") or 0.0),
        task_id=task.id,
        speaker_key=target.get("speaker_key"),
        speaker_name=speaker_name or target.get("speaker_name"),
        profile_id=profile_id,
        duration_ms=target.get("duration_ms"),
    )


def enroll_profile_from_audio_file(
    user_id: str,
    display_name: str,
    source_file_path: str,
    profile_id: str | None = None,
) -> SpeakerProfile:
    clean_name = display_name.strip()
    if not clean_name:
        raise HTTPException(status_code=422, detail="声纹名称不能为空")
    result = enroll_voiceprint_audio_with_asr_service(str(uuid.uuid4()), source_file_path)
    embedding = result.get("embedding")
    if not embedding:
        raise HTTPException(status_code=422, detail="未能从采样音频提取声纹")
    _validate_enrollment_sample(result)
    return repositories.upsert_speaker_profile_sample(
        user_id=user_id,
        display_name=clean_name,
        embedding=embedding,
        quality=float(result.get("quality") or 0.0),
        task_id=None,
        speaker_key=None,
        speaker_name=clean_name,
        profile_id=profile_id,
        duration_ms=result.get("duration_ms"),
    )


def _task_speaker_embeddings_for_enrollment(user_id: str, task: MeetingTask) -> list[dict[str, Any]]:
    embeddings = repositories.list_task_speaker_embeddings(user_id, task.id)
    if embeddings:
        return embeddings

    result = repositories.get_result(task.id)
    if result is None or not result.transcripts:
        return []
    source_file_path = result.source_file_path
    if not source_file_path or not Path(source_file_path).exists():
        raise HTTPException(status_code=422, detail="会议源音频不存在，无法从本会议保存声纹")

    enriched = extract_voiceprints_with_asr_service(task.id, source_file_path, result.transcripts)
    if enriched:
        store_task_speaker_embeddings(user_id, task.id, enriched)
    return repositories.list_task_speaker_embeddings(user_id, task.id)


def participant_names_for_task(user_id: str, schedule_id: str | None, fallback_text: str | None = None) -> list[str]:
    names: list[str] = []
    if schedule_id:
        schedule = repositories.get_scheduled_meeting(user_id, schedule_id)
        if schedule is not None:
            names.extend(_participants_from_text(schedule.get("participants")))
    if not names:
        names.extend(_participants_from_text(fallback_text))
    return names


def _candidate_profiles(user_id: str, participant_names: list[str], profile_ids: list[str]) -> list[dict[str, Any]]:
    profiles = repositories.list_speaker_profiles(user_id, active_only=True)
    if profile_ids:
        wanted = set(profile_ids)
        profiles = [profile for profile in profiles if profile["id"] in wanted]
    names = {_normalize_name(name) for name in participant_names if _normalize_name(name)}
    if names:
        named = [profile for profile in profiles if _normalize_name(profile["display_name"]) in names]
        if named:
            profiles = named
    return [profile for profile in profiles if profile.get("centroid")]


def _embedding_groups_from_transcripts(transcripts: list[TranscriptSegment]) -> list[dict[str, Any]]:
    grouped: dict[str, list[TranscriptSegment]] = defaultdict(list)
    for segment in transcripts:
        key = _segment_speaker_key(segment)
        if key and segment.voiceprint_embedding:
            grouped[key].append(segment)
    output: list[dict[str, Any]] = []
    for key, segments in grouped.items():
        vectors = [_normalize_vector(segment.voiceprint_embedding) for segment in segments if segment.voiceprint_embedding]
        vectors = [vector for vector in vectors if vector]
        if not vectors:
            continue
        centroid = _centroid(vectors)
        if not centroid:
            continue
        duration_ms = sum(
            max(0, int((segment.end_ms or 0) - (segment.start_ms or 0)))
            for segment in segments
            if segment.start_ms is not None and segment.end_ms is not None
        )
        output.append(
            {
                "speaker_key": key,
                "speaker_name": segments[0].speaker,
                "embedding": centroid,
                "quality": max(float(segment.voiceprint_quality or 0.0) for segment in segments),
                "segment_count": len(segments),
                "duration_ms": duration_ms or None,
            }
        )
    return output


def _match_groups(groups: list[dict[str, Any]], profiles: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    proposed: list[dict[str, Any]] = []
    for group in groups:
        if not _matchable_group(group):
            continue
        scores = sorted(
            [
                {
                    "speaker_key": group["speaker_key"],
                    "profile_id": profile["id"],
                    "display_name": profile["display_name"],
                    "score": _cosine(group["embedding"], profile["centroid"]),
                }
                for profile in profiles
            ],
            key=lambda item: item["score"],
            reverse=True,
        )
        if not scores:
            continue
        best = scores[0]
        second = scores[1]["score"] if len(scores) > 1 else 0.0
        if best["score"] >= settings.speaker_match_threshold and (best["score"] - second) >= settings.speaker_match_margin:
            proposed.append(best)

    proposed.sort(key=lambda item: item["score"], reverse=True)
    used_profiles: set[str] = set()
    matches: dict[str, dict[str, Any]] = {}
    for item in proposed:
        if item["profile_id"] in used_profiles:
            continue
        used_profiles.add(item["profile_id"])
        matches[item["speaker_key"]] = item
    return matches


def _matchable_group(group: dict[str, Any]) -> bool:
    quality = float(group.get("quality") or 0.0)
    if quality < settings.speaker_min_match_quality:
        return False
    duration_ms = int(group.get("duration_ms") or 0)
    if duration_ms and duration_ms < settings.speaker_min_match_duration_ms:
        return False
    return True


def _validate_enrollment_sample(sample: dict[str, Any]) -> None:
    duration_ms = int(sample.get("duration_ms") or 0)
    if duration_ms < settings.speaker_min_enroll_duration_ms:
        min_seconds = max(1, settings.speaker_min_enroll_duration_ms // 1000)
        raise HTTPException(status_code=422, detail=f"声纹样本太短，请提供至少 {min_seconds} 秒清晰人声")
    quality = float(sample.get("quality") or 0.0)
    if quality < settings.speaker_min_enroll_quality:
        raise HTTPException(status_code=422, detail="声纹样本质量不足，请换一段更清晰的人声")


def _find_task_embedding(embeddings: list[dict[str, Any]], speaker_id: str | None, speaker_name: str | None) -> dict[str, Any] | None:
    clean_id = str(speaker_id or "").strip()
    clean_name = str(speaker_name or "").strip()
    if clean_id:
        for item in embeddings:
            if item.get("speaker_key") == clean_id:
                return item
    if clean_name:
        normalized = _normalize_name(clean_name)
        for item in embeddings:
            if _normalize_name(item.get("speaker_name")) == normalized:
                return item
    return embeddings[0] if len(embeddings) == 1 else None


def _strip_voiceprint_fields(transcripts: list[TranscriptSegment]) -> list[TranscriptSegment]:
    return [segment.model_copy(update={"voiceprint_embedding": None, "voiceprint_quality": None}) for segment in transcripts]


def _segment_speaker_key(segment: TranscriptSegment) -> str:
    clean_id = str(segment.speaker_id or "").strip()
    if clean_id:
        return clean_id
    clean_speaker = str(segment.speaker or "").strip()
    return clean_speaker if clean_speaker and clean_speaker != "未分离" else ""


def _participants_from_text(text: str | None) -> list[str]:
    clean = str(text or "").strip()
    if not clean:
        return []
    parts = re.split(r"[\s,，、;；/|]+", clean)
    return [part.strip() for part in parts if part.strip()]


def _normalize_name(value: Any) -> str:
    return re.sub(r"\s+", "", str(value or "").strip()).lower()


def _normalize_vector(values: list[float] | tuple[float, ...] | None) -> list[float]:
    if not values:
        return []
    vector = [float(value) for value in values if value is not None]
    norm = sum(value * value for value in vector) ** 0.5
    if norm <= 1e-6:
        return []
    return [value / norm for value in vector]


def _centroid(vectors: list[list[float]]) -> list[float]:
    dimensions = len(vectors[0]) if vectors else 0
    if dimensions <= 0:
        return []
    sums = [0.0] * dimensions
    count = 0
    for vector in vectors:
        if len(vector) != dimensions:
            continue
        count += 1
        for index, value in enumerate(vector):
            sums[index] += value
    if count == 0:
        return []
    return [round(value, 6) for value in _normalize_vector([value / count for value in sums])]


def _cosine(left: list[float], right: list[float]) -> float:
    if not left or not right or len(left) != len(right):
        return 0.0
    return round(float(sum(a * b for a, b in zip(left, right, strict=False))), 4)
