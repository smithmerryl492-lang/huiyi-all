import os
import threading
import logging
import time
import wave
from fastapi import HTTPException

from app import membership_repositories, repositories
from app.schemas import (
    MeetingProcessingResult,
    MeetingTaskStatus,
    MeetingTaskSource,
    KnowledgeScope,
    TranscriptSegment,
)
from app.services.ai_client import (
    generate_minutes_with_ai_service,
    risks_from_ai_response,
    todos_from_ai_response_for_task,
    topics_from_ai_response,
)
from app.services.asr_client import extract_voiceprints_with_asr_service, transcribe_with_asr_service
from app.services.knowledge import clear_knowledge_cache, index_meeting_result, reindex_meeting_result_reusing_embeddings
from app.services import task_runtime
from app.services import voiceprints
from app.services.errors import user_message_from_exception
from app.services.temp_audio_links import generate_temp_audio_download_url


logger = logging.getLogger(__name__)
TRANSIENT_PROCESSING_MESSAGES = (
    "智能处理暂时失败",
    "服务器维护中",
    "请求超时",
    "请稍后重试",
)
BUSINESS_FAILURE_MESSAGES = (
    "额度",
    "登录",
    "冻结",
    "文件不存在",
    "文件暂不可用",
    "音频文件暂不可用",
    "没有检测到有效声音",
    "任务已终止",
    "转写内容不能为空",
)


def process_task(task_id: str) -> MeetingProcessingResult:
    runtime_detail = task_runtime.get_any(task_id)
    if runtime_detail is not None:
        task = runtime_detail.task
        file = runtime_detail.file
        meeting_note = runtime_detail.meeting_note
        user_id = runtime_detail.user_id
        schedule_id = runtime_detail.schedule_id
        recognition_language = runtime_detail.recognition_language
        live_transcripts = runtime_detail.transcripts
    else:
        task = repositories.get_task(task_id)
        file = repositories.get_file(task.file_id)
        meeting_note = None
        user_id = repositories.DEFAULT_USER_ID
        schedule_id = None
        recognition_language = None
        live_transcripts = None

    if not os.path.exists(file.stored_path):
        user_message = "文件暂不可用，请重新上传后再试"
        repositories.update_task_status(
            task.id,
            MeetingTaskStatus.failed,
            user_message,
            progress_label="处理失败",
            progress_stage="failed",
        )
        raise HTTPException(status_code=409, detail=user_message)

    _raise_if_cancelled(task.id)
    _update_processing_progress(task.id, 5, "正在检查文件", "prepare")
    try:
        _raise_if_cancelled(task.id)
        _update_processing_progress(task.id, 20, "正在转写音频", "asr")
        transcripts = _usable_live_transcripts(live_transcripts, file.stored_path)
        if transcripts:
            logger.info("任务 %s 使用实时转写结果，片段数 %s", task.id, len(transcripts))
        else:
            asr_started = time.perf_counter()
            transcripts = transcribe_with_asr_service(
                task_id=task.id,
                source_file_path=file.stored_path,
                source_file_url=generate_temp_audio_download_url(task.id),
                language_hint=_normalize_recognition_language(recognition_language),
            )
            logger.info("任务 %s ASR 转写完成，片段数 %s，耗时 %.2fs", task.id, len(transcripts), time.perf_counter() - asr_started)
        _raise_if_cancelled(task.id)
        if not transcripts:
            raise HTTPException(status_code=502, detail="ASR 未返回真实转写结果")
        consumed_minutes = _transcription_minutes_from_segments(transcripts)
        grace_minutes = 30 if task.source == MeetingTaskSource.recording else 0
        membership_repositories.consume_transcription_minutes(user_id, consumed_minutes, grace_minutes=grace_minutes)
        _update_processing_progress(task.id, 50, "转写完成，正在整理文本", "transcript_ready")
        transcripts = _apply_voiceprints_before_minutes(user_id, task, file.stored_path, schedule_id, transcripts)
        _update_processing_progress(task.id, 55, "转写完成，正在生成纪要", "minutes")
        _raise_if_cancelled(task.id)
        minutes_started = time.perf_counter()
        minutes = generate_minutes_with_ai_service(
            task_id=task.id,
            title=task.title,
            transcripts=transcripts,
            meeting_note=meeting_note,
        )
        logger.info("任务 %s 纪要生成完成，耗时 %.2fs", task.id, time.perf_counter() - minutes_started)
        _raise_if_cancelled(task.id)
        summary = str(minutes.get("summary", "")).strip()
        if not summary:
            raise HTTPException(status_code=502, detail="LLM 未返回会议摘要")
        _update_processing_progress(task.id, 82, "纪要生成完成，正在保存结果", "saving")
        _raise_if_cancelled(task.id)
        result = MeetingProcessingResult(
            task_id=task.id,
            source_file_path=file.stored_path,
            summary=summary,
            topics=topics_from_ai_response(minutes.get("topics", [])),
            decisions=[str(item) for item in minutes.get("decisions", [])],
            todos=todos_from_ai_response_for_task(task.id, minutes.get("todos", [])),
            risks=risks_from_ai_response(minutes.get("risks", [])),
            transcripts=transcripts,
            generated_at=repositories.now_iso(),
        )
        repositories.save_result(result)
        task_runtime.finish(task.id, result)
        threading.Thread(target=_mark_task_finished_safely, args=(task.id,), daemon=True).start()
        if task.knowledge_scope == KnowledgeScope.cloud and not task.is_private:
            threading.Thread(target=_index_meeting_safely, args=(task.title, result), daemon=True).start()
        else:
            repositories.delete_knowledge_for_task(task.id)
            clear_knowledge_cache(user_id)
        return result
    except task_runtime.TaskCanceledError:
        raise
    except Exception as exc:
        if task_runtime.is_cancelled(task.id):
            raise task_runtime.TaskCanceledError("任务已终止") from exc
        logger.exception("会议处理失败：%s", task.id)
        user_message = user_message_from_exception(exc)
        if _is_transient_processing_message(user_message):
            task_runtime.update_progress(
                task.id,
                max(float(task.progress_percent or 0.0), 8.0),
                "处理暂未完成，稍后可继续",
                "waiting_retry",
                status=MeetingTaskStatus.waiting_process,
            )
            repositories.update_task_status(
                task.id,
                MeetingTaskStatus.waiting_process,
                "处理暂未完成，稍后可继续",
                progress_percent=max(float(task.progress_percent or 0.0), 8.0),
                progress_label="可继续处理",
                progress_stage="waiting_retry",
            )
        else:
            task_runtime.fail(task.id, user_message)
            repositories.update_task_status(
                task.id,
                MeetingTaskStatus.failed,
                user_message,
                progress_label="处理失败",
                progress_stage="failed",
            )
        raise


def _index_meeting_safely(title: str, result: MeetingProcessingResult) -> None:
    try:
        index_meeting_result(title, result)
    except Exception:
        logger.exception("会议知识库索引后台生成失败：%s", result.task_id)


def _is_transient_processing_message(message: str) -> bool:
    clean = str(message or "").strip()
    if not clean:
        return True
    if any(marker in clean for marker in BUSINESS_FAILURE_MESSAGES):
        return False
    lower = clean.lower()
    return (
        any(marker in clean for marker in TRANSIENT_PROCESSING_MESSAGES)
        or "timeout" in lower
        or "timed out" in lower
        or "connection" in lower
        or "unavailable" in lower
        or "bad gateway" in lower
        or "gateway timeout" in lower
        or "internal server error" in lower
    )


def _transcription_minutes_from_segments(transcripts: list) -> int:
    max_end_ms = 0
    for segment in transcripts:
        end_ms = getattr(segment, "end_ms", None)
        start_ms = getattr(segment, "start_ms", None)
        if isinstance(segment, dict):
            end_ms = segment.get("end_ms")
            start_ms = segment.get("start_ms")
        value = end_ms if end_ms is not None else start_ms
        if value is None:
            continue
        try:
            max_end_ms = max(max_end_ms, int(value))
        except (TypeError, ValueError):
            continue
    if max_end_ms <= 0:
        return 1
    return max(1, int((max_end_ms + 59_999) // 60_000))


def _usable_live_transcripts(transcripts: list[TranscriptSegment] | None, source_file_path: str) -> list[TranscriptSegment]:
    del source_file_path
    if not transcripts:
        return []
    cleaned = [
        segment
        for segment in transcripts
        if str(segment.text or "").strip()
    ]
    if not cleaned:
        return []
    has_timing = any((segment.end_ms or segment.start_ms or 0) > 0 for segment in cleaned)
    if not has_timing:
        return []
    return sorted(cleaned, key=lambda item: (item.start_ms is None, item.start_ms or 0, item.end_ms or 0))


def _normalize_recognition_language(value: str | None) -> str:
    clean = str(value or "").strip().lower()
    if clean in {"en", "en-us", "english"}:
        return "en-US"
    if clean in {"auto", "mixed", "zh-en"}:
        return "auto"
    return "zh-CN"


def _wav_duration_ms(source_file_path: str) -> int | None:
    try:
        with wave.open(source_file_path, "rb") as wav:
            frame_rate = wav.getframerate()
            frame_count = wav.getnframes()
        if frame_rate <= 0:
            return None
        return int(frame_count / frame_rate * 1000)
    except Exception:
        return None


def _extract_voiceprints_safely(user_id: str, task, source_file_path: str, transcripts: list, schedule_id: str | None) -> None:
    try:
        enriched = extract_voiceprints_with_asr_service(task.id, source_file_path, transcripts)
        if not enriched:
            return
        voiceprints.store_task_speaker_embeddings(user_id, task.id, enriched)
        participant_names = voiceprints.participant_names_for_task(user_id, schedule_id, fallback_text=None)
        identified, matched_count, _ = voiceprints.identify_task_speakers(
            user_id,
            task,
            enriched,
            participant_names=participant_names,
        )
        if matched_count <= 0:
            return
        current = repositories.get_result(task.id)
        if current is None:
            return
        repositories.save_result(
            current.model_copy(
                update={
                    "transcripts": identified,
                    "generated_at": repositories.now_iso(),
                }
            )
        )
        updated = repositories.get_result(task.id)
        if updated is not None:
            _refresh_knowledge_after_voiceprint_update(user_id, task, updated)
        logger.info("任务 %s 后台声纹识别命中 %s 个说话人", task.id, matched_count)
    except Exception:
        logger.exception("任务 %s 后台声纹抽取失败，已跳过", task.id)


def _apply_voiceprints_before_minutes(user_id: str, task, source_file_path: str, schedule_id: str | None, transcripts: list) -> list:
    try:
        participant_names = voiceprints.participant_names_for_task(user_id, schedule_id, fallback_text=None)
        identified, matched_count, profile_count, extracted = voiceprints.identify_task_speakers_from_audio(
            user_id,
            task,
            source_file_path,
            transcripts,
            participant_names=participant_names,
            require_profiles=False,
        )
        if extracted:
            logger.info("任务 %s 说话人分离完成，声纹命中 %s/%s 个档案", task.id, matched_count, profile_count)
            return identified
    except Exception:
        logger.exception("任务 %s 说话人分离失败", task.id)
        raise HTTPException(status_code=502, detail="说话人分离暂时失败，请稍后重试")
    raise HTTPException(status_code=502, detail="说话人分离暂时失败，请稍后重试")


def _start_voiceprint_identification_job(user_id: str, task, source_file_path: str, schedule_id: str | None, transcripts: list) -> dict:
    job = {
        "event": threading.Event(),
        "transcripts": None,
        "extracted": False,
    }

    def worker() -> None:
        try:
            participant_names = voiceprints.participant_names_for_task(user_id, schedule_id, fallback_text=None)
            identified, matched_count, profile_count, extracted = voiceprints.identify_task_speakers_from_audio(
                user_id,
                task,
                source_file_path,
                transcripts,
                participant_names=participant_names,
                require_profiles=True,
            )
            job["transcripts"] = identified
            job["extracted"] = extracted
            if extracted:
                logger.info("任务 %s 并行声纹识别完成，命中 %s/%s 个档案", task.id, matched_count, profile_count)
        except Exception:
            logger.exception("任务 %s 并行声纹识别失败，继续使用匿名说话人", task.id)
        finally:
            job["event"].set()

    threading.Thread(target=worker, daemon=True).start()
    return job


def _consume_voiceprint_job_if_ready(transcripts: list, job: dict) -> tuple[list, bool]:
    event = job["event"]
    if not event.wait(timeout=3.0):
        return transcripts, False
    if job.get("extracted") and job.get("transcripts"):
        return job["transcripts"], True
    return transcripts, False


def _finish_voiceprint_job_safely(job: dict, user_id: str, task, source_file_path: str, result: MeetingProcessingResult, schedule_id: str | None) -> None:
    try:
        event = job["event"]
        event.wait()
        if job.get("extracted"):
            identified = job.get("transcripts")
            if identified:
                current = repositories.get_result(task.id)
                if current is not None:
                    updated = current.model_copy(
                        update={
                            "transcripts": identified,
                            "generated_at": repositories.now_iso(),
                        }
                    )
                    repositories.save_result(updated)
                    _refresh_knowledge_after_voiceprint_update(user_id, task, updated)
                    logger.info("任务 %s 后台声纹识别结果已写回", task.id)
            return
        _extract_voiceprints_safely(user_id, task, source_file_path, result.transcripts, schedule_id)
    except Exception:
        logger.exception("任务 %s 后台声纹结果写回失败，已跳过", task.id)


def _refresh_knowledge_after_voiceprint_update(user_id: str, task, result: MeetingProcessingResult) -> None:
    try:
        if task.knowledge_scope == KnowledgeScope.cloud and not task.is_private:
            reindex_meeting_result_reusing_embeddings(task.title, result)
        else:
            repositories.delete_knowledge_for_task(task.id)
            clear_knowledge_cache(user_id)
    except Exception:
        logger.exception("任务 %s 声纹写回后的知识库索引刷新失败", task.id)


def _update_processing_progress(task_id: str, progress_percent: float, progress_label: str, progress_stage: str) -> None:
    _raise_if_cancelled(task_id)
    task_runtime.update_progress(task_id, progress_percent, progress_label, progress_stage)
    repositories.update_task_status(
        task_id,
        MeetingTaskStatus.processing,
        None,
        progress_percent=progress_percent,
        progress_label=progress_label,
        progress_stage=progress_stage,
    )


def _apply_voiceprints_safely(user_id: str, task, schedule_id: str | None, transcripts: list) -> list:
    try:
        voiceprints.store_task_speaker_embeddings(user_id, task.id, transcripts)
        participant_names = voiceprints.participant_names_for_task(user_id, schedule_id, fallback_text=None)
        identified, matched_count, _ = voiceprints.identify_task_speakers(
            user_id,
            task,
            transcripts,
            participant_names=participant_names,
        )
        if matched_count > 0:
            logger.info("任务 %s 声纹识别命中 %s 个说话人", task.id, matched_count)
        return identified
    except Exception:
        logger.exception("任务 %s 声纹处理失败，已跳过", task.id)
        return _strip_transcript_voiceprints(transcripts)


def _strip_transcript_voiceprints(transcripts: list) -> list:
    return [
        segment.model_copy(update={"voiceprint_embedding": None, "voiceprint_quality": None})
        for segment in transcripts
    ]


def _raise_if_cancelled(task_id: str) -> None:
    if task_runtime.is_cancelled(task_id):
        raise task_runtime.TaskCanceledError("任务已终止")
    try:
        task = repositories.get_task(task_id)
    except Exception:
        return
    if task.status == MeetingTaskStatus.canceled:
        raise task_runtime.TaskCanceledError("任务已终止")


def _mark_task_finished_safely(task_id: str) -> None:
    try:
        if repositories.get_task(task_id).status == MeetingTaskStatus.canceled:
            return
        repositories.update_task_status(
            task_id,
            MeetingTaskStatus.finished,
            progress_percent=100,
            progress_label="处理完成",
            progress_stage="finished",
        )
    except Exception:
        logger.exception("会议完成状态后台写库失败：%s", task_id)


def regenerate_minutes(task_id: str, transcripts, meeting_note: str | None = None) -> MeetingProcessingResult:
    task = repositories.get_task(task_id)
    previous = repositories.get_result(task_id)
    if previous is None:
        raise HTTPException(status_code=404, detail="任务结果不存在")
    if not transcripts:
        raise HTTPException(status_code=422, detail="转写内容不能为空")
    try:
        minutes = generate_minutes_with_ai_service(
            task_id=task.id,
            title=task.title,
            transcripts=transcripts,
            meeting_note=meeting_note,
        )
        summary = str(minutes.get("summary", "")).strip()
        if not summary:
            raise HTTPException(status_code=502, detail="LLM 未返回会议摘要")
        result = MeetingProcessingResult(
            task_id=task.id,
            source_file_path=previous.source_file_path,
            summary=summary,
            topics=topics_from_ai_response(minutes.get("topics", [])),
            decisions=[str(item) for item in minutes.get("decisions", [])],
            todos=todos_from_ai_response_for_task(task.id, minutes.get("todos", [])),
            risks=risks_from_ai_response(minutes.get("risks", [])),
            transcripts=transcripts,
            generated_at=repositories.now_iso(),
        )
        repositories.save_result(result)
        if task.knowledge_scope == KnowledgeScope.cloud and not task.is_private:
            index_meeting_result(task.title, result)
        else:
            repositories.delete_knowledge_for_task(task.id)
            clear_knowledge_cache(task.user_id)
        repositories.update_task_status(task.id, MeetingTaskStatus.finished)
        return result
    except Exception:
        logger.exception("任务 %s 重新生成纪要失败，保留原任务状态和原结果", task.id)
        raise


def regenerate_local_minutes(
    task_id: str,
    title: str,
    source_file_path: str,
    transcripts,
    participants: str | None = None,
    tags: list[str] | None = None,
    meeting_note: str | None = None,
) -> MeetingProcessingResult:
    if not transcripts:
        raise HTTPException(status_code=422, detail="转写内容不能为空")
    minutes = generate_minutes_with_ai_service(
        task_id=task_id,
        title=title,
        transcripts=transcripts,
        meeting_note=meeting_note,
    )
    summary = str(minutes.get("summary", "")).strip()
    if not summary:
        raise HTTPException(status_code=502, detail="LLM 未返回会议摘要")
    return MeetingProcessingResult(
        task_id=task_id,
        source_file_path=source_file_path,
        participants=participants,
        tags=tags or [],
        summary=summary,
        topics=topics_from_ai_response(minutes.get("topics", [])),
        decisions=[str(item) for item in minutes.get("decisions", [])],
        todos=todos_from_ai_response_for_task(task_id, minutes.get("todos", [])),
        risks=risks_from_ai_response(minutes.get("risks", [])),
        transcripts=transcripts,
        generated_at=repositories.now_iso(),
    )
