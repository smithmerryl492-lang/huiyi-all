import threading
from dataclasses import dataclass
from typing import Any

import numpy as np

from app.core.config import settings
from app.schemas import TranscriptSegment


_MODEL_LOCK = threading.Lock()
_MODEL: Any | None = None
_CLUSTER_CLS: Any | None = None


@dataclass(frozen=True)
class _EmbeddingItem:
    segment_index: int
    speaker_key: str | None
    embedding: np.ndarray
    duration_ms: int
    text_len: int


def repair_single_speaker_segments(
    segments: list[TranscriptSegment],
    pcm: bytes,
    sample_rate: int = 16000,
    force_recluster: bool = False,
) -> list[TranscriptSegment]:
    return repair_speaker_segments(segments, pcm, sample_rate=sample_rate, force_recluster=force_recluster)


def repair_speaker_segments(
    segments: list[TranscriptSegment],
    pcm: bytes,
    sample_rate: int = 16000,
    force_recluster: bool = False,
) -> list[TranscriptSegment]:
    if len(segments) < 2:
        return _renumber_speakers(segments)
    speakers = {segment.speaker for segment in segments if _speaker_key(segment.speaker) is not None}
    if len(speakers) > 1 and not force_recluster:
        return _renumber_speakers(segments)

    candidates: list[tuple[int, TranscriptSegment, bytes, int, int]] = []
    for index, segment in enumerate(segments):
        audio = _slice_pcm(pcm, segment.start_ms, segment.end_ms, sample_rate)
        duration_ms = _duration_ms(audio, sample_rate)
        text_len = _clean_text_len(segment.text)
        if duration_ms >= 700 and text_len > 0:
            candidates.append((index, segment, audio, duration_ms, text_len))

    if len(candidates) < 2:
        return _renumber_speakers(segments)

    model, cluster_cls = _load_campplus()
    items: list[_EmbeddingItem] = []
    for index, segment, audio, duration_ms, text_len in candidates:
        output = model.generate(input=audio, embedding=True)[0]
        embedding = output["spk_embedding"][0].cpu().numpy()
        items.append(
            _EmbeddingItem(
                segment_index=index,
                speaker_key=_speaker_key(segment.speaker),
                embedding=np.asarray(embedding, dtype=np.float32),
                duration_ms=duration_ms,
                text_len=text_len,
            )
        )

    if len(items) < 2:
        return _renumber_speakers(segments)

    if len(speakers) <= 1:
        item_labels = _cluster_unlabeled_items(items, cluster_cls)
    else:
        item_labels = _labels_from_existing_speakers(items)

    item_labels = _merge_over_split_clusters(items, item_labels)
    item_labels = _refine_outlier_items(items, item_labels)
    item_labels = _merge_over_split_clusters(items, item_labels)

    segment_label_by_index = _labels_for_all_segments(segments, items, item_labels)
    if not segment_label_by_index:
        return _renumber_speakers(segments)

    repaired: list[TranscriptSegment] = []
    ordered_labels = _labels_by_first_occurrence(segments, segment_label_by_index)
    last_label = next(iter(ordered_labels), 0)
    for index, segment in enumerate(segments):
        label = segment_label_by_index.get(index)
        if label is None:
            label = _nearest_label(index, segment_label_by_index) or last_label
        last_label = label
        speaker = f"说话人 {ordered_labels.setdefault(label, len(ordered_labels) + 1)}"
        repaired.append(segment.model_copy(update={"speaker": speaker}))
    return repaired


def attach_speaker_voiceprints(
    segments: list[TranscriptSegment],
    pcm: bytes,
    sample_rate: int = 16000,
) -> list[TranscriptSegment]:
    if not segments:
        return segments

    candidates: dict[str, list[tuple[int, bytes, int, int]]] = {}
    for index, segment in enumerate(segments):
        speaker_key = _speaker_key(segment.speaker)
        if speaker_key is None:
            continue
        audio = _slice_pcm(pcm, segment.start_ms, segment.end_ms, sample_rate)
        duration_ms = _duration_ms(audio, sample_rate)
        text_len = _clean_text_len(segment.text)
        if duration_ms < 700 or text_len <= 0:
            continue
        candidates.setdefault(speaker_key, []).append((index, audio, duration_ms, text_len))

    if not candidates:
        return segments

    selected: dict[str, list[tuple[int, bytes, int, int]]] = {}
    max_per_speaker = max(1, settings.voiceprint_max_samples_per_speaker)
    max_total = max(1, settings.voiceprint_max_total_samples)
    ranked = [
        (speaker_key, item)
        for speaker_key, items in candidates.items()
        for item in sorted(items, key=_voiceprint_candidate_score, reverse=True)[:max_per_speaker]
    ]
    ranked.sort(key=lambda item: _voiceprint_candidate_score(item[1]), reverse=True)
    for speaker_key, item in ranked[:max_total]:
        selected.setdefault(speaker_key, []).append(item)

    model, _ = _load_campplus()
    embeddings_by_speaker: dict[str, list[tuple[int, np.ndarray, int, int]]] = {}
    for speaker_key, items in selected.items():
        for index, audio, duration_ms, text_len in items:
            output = model.generate(input=audio, embedding=True)[0]
            embedding = np.asarray(output["spk_embedding"][0].cpu().numpy(), dtype=np.float32)
            embeddings_by_speaker.setdefault(speaker_key, []).append((index, embedding, duration_ms, text_len))

    updates: dict[int, tuple[list[float], float]] = {}
    for items in embeddings_by_speaker.values():
        matrix = _normalize_embedding_matrix(np.stack([item[1] for item in items], axis=0))
        centroid = matrix.mean(axis=0)
        centroid = centroid / max(float(np.linalg.norm(centroid)), 1e-6)
        total_duration_ms = sum(item[2] for item in items)
        total_text_len = sum(item[3] for item in items)
        quality = _voiceprint_quality(len(items), total_duration_ms, total_text_len)
        vector = [round(float(value), 6) for value in centroid.tolist()]
        for index, _, _, _ in items:
            updates[index] = (vector, quality)

    output_segments: list[TranscriptSegment] = []
    for index, segment in enumerate(segments):
        update = updates.get(index)
        if update is None:
            output_segments.append(segment)
            continue
        vector, quality = update
        output_segments.append(segment.model_copy(update={"voiceprint_embedding": vector, "voiceprint_quality": quality}))
    return output_segments


def extract_voiceprint_embedding_from_pcm(
    pcm: bytes,
    sample_rate: int = 16000,
) -> dict[str, Any]:
    audio = _trim_voiceprint_pcm(pcm, sample_rate)
    duration_ms = _duration_ms(audio, sample_rate)
    if duration_ms < 1_500:
        raise ValueError("声纹采样音频过短，请录制至少 2 秒清晰人声")

    max_bytes = sample_rate * 2 * 30
    if len(audio) > max_bytes:
        audio = audio[:max_bytes]
        duration_ms = _duration_ms(audio, sample_rate)

    model, _ = _load_campplus()
    output = model.generate(input=audio, embedding=True)[0]
    embedding = np.asarray(output["spk_embedding"][0].cpu().numpy(), dtype=np.float32)
    normalized = _normalize_embedding_matrix(np.expand_dims(embedding, axis=0))[0]
    vector = [round(float(value), 6) for value in normalized.tolist()]
    return {
        "embedding": vector,
        "quality": _voiceprint_quality(1, duration_ms, 0),
        "duration_ms": duration_ms,
    }


def _trim_voiceprint_pcm(pcm: bytes, sample_rate: int) -> bytes:
    if not pcm or len(pcm) < 2:
        return b""
    if len(pcm) % 2 == 1:
        pcm = pcm[:-1]
    samples = np.frombuffer(pcm, dtype=np.int16)
    if len(samples) == 0:
        return b""
    frame_size = max(160, int(sample_rate * 0.05))
    frame_count = max(1, len(samples) // frame_size)
    active_indices: list[int] = []
    for index in range(frame_count):
        frame = samples[index * frame_size : (index + 1) * frame_size].astype(np.float32)
        if len(frame) == 0:
            continue
        rms = float(np.sqrt(np.mean(np.square(frame))))
        peak = float(np.max(np.abs(frame)))
        if rms >= 12.0 and peak >= 90.0:
            active_indices.append(index)
    if not active_indices:
        return pcm
    start = max(0, (min(active_indices) - 2) * frame_size)
    end = min(len(samples), (max(active_indices) + 3) * frame_size)
    return samples[start:end].tobytes()


def _voiceprint_candidate_score(item: tuple[int, bytes, int, int]) -> tuple[int, int]:
    return item[2], item[3]


def _voiceprint_quality(sample_count: int, duration_ms: int, text_len: int) -> float:
    duration_score = min(duration_ms / 12_000.0, 1.0)
    sample_score = min(sample_count / 3.0, 1.0)
    text_score = min(text_len / 60.0, 1.0)
    return round(float(duration_score * 0.55 + sample_score * 0.30 + text_score * 0.15), 4)


def _load_campplus() -> tuple[Any, Any]:
    global _MODEL, _CLUSTER_CLS
    with _MODEL_LOCK:
        if _MODEL is not None and _CLUSTER_CLS is not None:
            return _MODEL, _CLUSTER_CLS
        from funasr import AutoModel
        from funasr.models.campplus.cluster_backend import SpectralCluster

        _MODEL = AutoModel(
            model="iic/speech_campplus_sv_zh-cn_16k-common",
            ngpu=0 if settings.device == "cpu" else 1,
            device=settings.device,
            disable_pbar=True,
            disable_log=True,
            disable_update=True,
        )
        _CLUSTER_CLS = SpectralCluster
        return _MODEL, _CLUSTER_CLS


def _slice_pcm(pcm: bytes, start_ms: int | None, end_ms: int | None, sample_rate: int) -> bytes:
    if start_ms is None or end_ms is None or end_ms <= start_ms:
        return b""
    padded_start = max(0, start_ms - 120)
    padded_end = min(_duration_ms(pcm, sample_rate), end_ms + 120)
    start_byte = int(padded_start * sample_rate * 2 / 1000)
    end_byte = int(padded_end * sample_rate * 2 / 1000)
    start_byte -= start_byte % 2
    end_byte -= end_byte % 2
    return pcm[start_byte:end_byte]


def _duration_ms(pcm: bytes, sample_rate: int) -> int:
    if not pcm:
        return 0
    return int(len(pcm) / (sample_rate * 2) * 1000)


def _clean_text_len(text: str) -> int:
    return sum(1 for char in str(text or "") if char.strip() and char not in "，。！？；、,.!?;:：")


def _speaker_key(speaker: str | None) -> str | None:
    clean = str(speaker or "").strip()
    if not clean or clean in {"未分离", "发言"}:
        return None
    return clean


def _renumber_speakers(segments: list[TranscriptSegment]) -> list[TranscriptSegment]:
    mapping: dict[str, str] = {}
    output: list[TranscriptSegment] = []
    for segment in segments:
        key = _speaker_key(segment.speaker)
        if key is None:
            output.append(segment.model_copy(update={"speaker": "说话人 1"}))
            continue
        if key not in mapping:
            mapping[key] = f"说话人 {len(mapping) + 1}"
        output.append(segment.model_copy(update={"speaker": mapping[key]}))
    return output


def _labels_from_existing_speakers(items: list[_EmbeddingItem]) -> list[int]:
    mapping: dict[str, int] = {}
    labels: list[int] = []
    for item in items:
        key = item.speaker_key or f"unknown-{item.segment_index}"
        if key not in mapping:
            mapping[key] = len(mapping)
        labels.append(mapping[key])
    return labels


def _cluster_unlabeled_items(items: list[_EmbeddingItem], cluster_cls: Any) -> list[int]:
    embeddings = np.stack([item.embedding for item in items], axis=0)
    max_speakers = max(2, min(settings.max_speakers, len(items)))
    cluster = cluster_cls(min_num_spks=2, max_num_spks=max_speakers)
    if len(items) == 2:
        if not _looks_like_two_speakers(embeddings):
            return [0, 0]
        return _normalize_labels([int(item) for item in cluster(embeddings, oracle_num=2)])

    raw_labels = [int(item) for item in cluster(embeddings)]
    if max_speakers >= 3 and _looks_like_three_speakers(embeddings, cluster_cls):
        raw_labels = [int(item) for item in cluster(embeddings, oracle_num=3)]
    if len(set(raw_labels)) < 2 and _looks_like_two_speakers(embeddings):
        raw_labels = [int(item) for item in cluster(embeddings, oracle_num=2)]
    return _normalize_labels(raw_labels)


def _merge_over_split_clusters(items: list[_EmbeddingItem], labels: list[int]) -> list[int]:
    merged = _normalize_labels(labels)
    while True:
        stats = _cluster_stats(items, merged)
        if len(stats) <= 1:
            return merged
        pair = _best_high_confidence_merge(stats)
        if pair is None:
            pair = _best_weak_cluster_merge(stats)
        if pair is None:
            return _normalize_labels(merged)
        source, target = pair
        merged = [target if label == source else label for label in merged]
        merged = _normalize_labels(merged)


def _cluster_stats(items: list[_EmbeddingItem], labels: list[int]) -> dict[int, dict[str, Any]]:
    normalized = _normalize_embedding_matrix(np.stack([item.embedding for item in items], axis=0))
    stats: dict[int, dict[str, Any]] = {}
    for item, label, embedding in zip(items, labels, normalized, strict=False):
        entry = stats.setdefault(
            label,
            {
                "indices": [],
                "embeddings": [],
                "duration_ms": 0,
                "text_len": 0,
            },
        )
        entry["indices"].append(item.segment_index)
        entry["embeddings"].append(embedding)
        entry["duration_ms"] += item.duration_ms
        entry["text_len"] += item.text_len
    for entry in stats.values():
        matrix = np.stack(entry["embeddings"], axis=0)
        centroid = matrix.mean(axis=0)
        entry["centroid"] = centroid / max(float(np.linalg.norm(centroid)), 1e-6)
        entry["count"] = len(entry["indices"])
    return stats


def _best_high_confidence_merge(stats: dict[int, dict[str, Any]]) -> tuple[int, int] | None:
    best_pair: tuple[int, int] | None = None
    best_similarity = -1.0
    labels = sorted(stats)
    for left_index, left in enumerate(labels[:-1]):
        for right in labels[left_index + 1:]:
            similarity = _centroid_similarity(stats[left], stats[right])
            if similarity > best_similarity:
                best_similarity = similarity
                best_pair = (left, right)
    if best_pair is None or best_similarity < 0.68:
        return None
    left, right = best_pair
    return _merge_direction(stats, left, right)


def _best_weak_cluster_merge(stats: dict[int, dict[str, Any]]) -> tuple[int, int] | None:
    best_pair: tuple[int, int] | None = None
    best_score = -1.0
    for label, entry in stats.items():
        if not _is_weak_cluster(entry):
            continue
        nearest_label = None
        nearest_similarity = -1.0
        for other_label, other_entry in stats.items():
            if other_label == label:
                continue
            similarity = _centroid_similarity(entry, other_entry)
            if similarity > nearest_similarity:
                nearest_similarity = similarity
                nearest_label = other_label
        if nearest_label is None:
            continue
        threshold = _weak_merge_threshold(entry, len(stats))
        if nearest_similarity < threshold:
            continue
        score = nearest_similarity + _cluster_weakness_score(entry)
        if score > best_score:
            best_score = score
            best_pair = (label, nearest_label)
    return best_pair


def _is_weak_cluster(entry: dict[str, Any]) -> bool:
    count = int(entry["count"])
    duration_ms = int(entry["duration_ms"])
    text_len = int(entry["text_len"])
    if count <= 1 and duration_ms <= 3_000:
        return True
    if count <= 2 and duration_ms <= 5_500 and text_len <= 24:
        return True
    if count <= 3 and duration_ms <= 12_000 and text_len <= 80:
        return True
    if count <= 4 and duration_ms <= 15_000 and text_len <= 100:
        return True
    return False


def _weak_merge_threshold(entry: dict[str, Any], cluster_count: int) -> float:
    duration_ms = int(entry["duration_ms"])
    text_len = int(entry["text_len"])
    if cluster_count <= 2:
        return 0.62
    if duration_ms <= 1_800 or text_len <= 3:
        return 0.42
    if duration_ms <= 5_500 and text_len <= 24:
        return 0.46
    if duration_ms <= 15_000 and text_len <= 100:
        return 0.58
    return 0.50


def _cluster_weakness_score(entry: dict[str, Any]) -> float:
    duration_ms = int(entry["duration_ms"])
    text_len = int(entry["text_len"])
    count = int(entry["count"])
    score = 0.0
    if duration_ms <= 1_800:
        score += 0.10
    if text_len <= 3:
        score += 0.10
    if count <= 1:
        score += 0.05
    return score


def _merge_direction(stats: dict[int, dict[str, Any]], left: int, right: int) -> tuple[int, int]:
    left_weight = int(stats[left]["duration_ms"]) + int(stats[left]["text_len"]) * 120 + int(stats[left]["count"]) * 800
    right_weight = int(stats[right]["duration_ms"]) + int(stats[right]["text_len"]) * 120 + int(stats[right]["count"]) * 800
    return (left, right) if right_weight >= left_weight else (right, left)


def _centroid_similarity(left: dict[str, Any], right: dict[str, Any]) -> float:
    return float(np.dot(left["centroid"], right["centroid"]))


def _refine_outlier_items(items: list[_EmbeddingItem], labels: list[int]) -> list[int]:
    refined = _normalize_labels(labels)
    if len(set(refined)) <= 1 or len(items) < 4:
        return refined
    normalized = _normalize_embedding_matrix(np.stack([item.embedding for item in items], axis=0))
    for item_index, item in enumerate(items):
        current_label = refined[item_index]
        same_indices = [index for index, label in enumerate(refined) if label == current_label and index != item_index]
        if len(same_indices) < 2:
            continue
        own_centroid = normalized[same_indices].mean(axis=0)
        own_centroid = own_centroid / max(float(np.linalg.norm(own_centroid)), 1e-6)
        own_similarity = float(np.dot(normalized[item_index], own_centroid))
        best_label = current_label
        best_similarity = own_similarity
        for label in sorted(set(refined)):
            if label == current_label:
                continue
            other_indices = [index for index, other_label in enumerate(refined) if other_label == label]
            if not other_indices:
                continue
            centroid = normalized[other_indices].mean(axis=0)
            centroid = centroid / max(float(np.linalg.norm(centroid)), 1e-6)
            similarity = float(np.dot(normalized[item_index], centroid))
            if similarity > best_similarity:
                best_similarity = similarity
                best_label = label
        if best_label != current_label and best_similarity >= 0.54 and best_similarity - own_similarity >= 0.16:
            refined[item_index] = best_label
    return _normalize_labels(refined)


def _labels_for_all_segments(
    segments: list[TranscriptSegment],
    items: list[_EmbeddingItem],
    item_labels: list[int],
) -> dict[int, int]:
    label_by_candidate_index = {
        item.segment_index: label
        for item, label in zip(items, item_labels, strict=False)
    }
    by_speaker_key: dict[str, list[int]] = {}
    for item, label in zip(items, item_labels, strict=False):
        if item.speaker_key is None:
            continue
        by_speaker_key.setdefault(item.speaker_key, []).append(label)

    speaker_key_to_label = {
        key: _majority_label(labels)
        for key, labels in by_speaker_key.items()
        if labels
    }
    output: dict[int, int] = {}
    for index, segment in enumerate(segments):
        if index in label_by_candidate_index:
            output[index] = label_by_candidate_index[index]
            continue
        key = _speaker_key(segment.speaker)
        if key is not None and key in speaker_key_to_label:
            output[index] = speaker_key_to_label[key]
    return output


def _majority_label(labels: list[int]) -> int:
    counts: dict[int, int] = {}
    for label in labels:
        counts[label] = counts.get(label, 0) + 1
    return max(counts, key=lambda label: (counts[label], -label))


def _labels_by_first_occurrence(
    segments: list[TranscriptSegment],
    label_by_index: dict[int, int],
) -> dict[int, int]:
    ordered: dict[int, int] = {}
    for index in range(len(segments)):
        label = label_by_index.get(index)
        if label is not None and label not in ordered:
            ordered[label] = len(ordered) + 1
    return ordered


def _normalize_labels(labels: list[int]) -> list[int]:
    mapping: dict[int, int] = {}
    output: list[int] = []
    for label in labels:
        if label not in mapping:
            mapping[label] = len(mapping)
        output.append(mapping[label])
    return output


def _looks_like_two_speakers(embeddings: np.ndarray) -> bool:
    norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
    normalized = embeddings / np.maximum(norms, 1e-6)
    similarity = normalized @ normalized.T
    upper = similarity[np.triu_indices(similarity.shape[0], k=1)]
    return bool(len(upper) and float(np.min(upper)) <= 0.55)


def _looks_like_three_speakers(embeddings: np.ndarray, cluster_cls: Any) -> bool:
    if len(embeddings) < 3:
        return False
    normalized = _normalize_embedding_matrix(embeddings)
    similarity = normalized @ normalized.T
    for first in range(len(embeddings) - 2):
        for second in range(first + 1, len(embeddings) - 1):
            for third in range(second + 1, len(embeddings)):
                pairwise = [
                    similarity[first, second],
                    similarity[first, third],
                    similarity[second, third],
                ]
                if max(float(item) for item in pairwise) <= 0.58:
                    return True
    if len(embeddings) < 5:
        return False
    cluster = cluster_cls(min_num_spks=3, max_num_spks=3)
    labels = [int(item) for item in cluster(embeddings, oracle_num=3)]
    if len(set(labels)) < 3:
        return False
    centroids: list[np.ndarray] = []
    for label in sorted(set(labels)):
        members = normalized[np.asarray(labels) == label]
        if len(members) == 0:
            return False
        centroid = members.mean(axis=0)
        centroid = centroid / max(float(np.linalg.norm(centroid)), 1e-6)
        centroids.append(centroid)
    centroid_matrix = np.stack(centroids)
    similarity = centroid_matrix @ centroid_matrix.T
    upper = similarity[np.triu_indices(similarity.shape[0], k=1)]
    return bool(len(upper) and float(np.max(upper)) <= 0.72 and float(np.min(upper)) <= 0.52)


def _normalize_embedding_matrix(embeddings: np.ndarray) -> np.ndarray:
    norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
    return embeddings / np.maximum(norms, 1e-6)


def _nearest_label(index: int, label_by_index: dict[int, int]) -> int | None:
    if not label_by_index:
        return None
    nearest = min(label_by_index, key=lambda candidate: abs(candidate - index))
    return label_by_index[nearest]
