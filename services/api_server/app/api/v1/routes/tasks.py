import threading
import logging
import time
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Response
from fastapi.responses import FileResponse

from app import membership_repositories, repositories
from app.schemas import (
    ListResponse,
    LocalRegenerateMinutesRequest,
    MeetingProcessingResult,
    MeetingTask,
    MeetingTaskStatus,
    RegenerateMinutesRequest,
    ResultUpdateRequest,
    TaskUpdateRequest,
    TaskProcessingContextRequest,
    TaskDetail,
)
from app.services.knowledge import clear_knowledge_cache, index_meeting_result
from app.services.asr_client import cancel_asr_task
from app.services.processing import process_task as run_task_processing
from app.services.processing import regenerate_local_minutes as run_local_minutes_regeneration
from app.services.processing import regenerate_minutes as run_minutes_regeneration
from app.services import processing_scheduler, task_runtime, voiceprints
from app.services.auth import require_current_user_id
from app.services.errors import user_message_from_exception
from app.services.temp_audio_links import verify_temp_audio_signature


router = APIRouter()
_processing_lock = threading.Lock()
logger = logging.getLogger(__name__)
TRANSIENT_PROCESSING_MESSAGES = (
    "智能处理暂时失败",
    "服务器维护中",
    "请求超时",
    "请稍后重试",
    "处理暂未完成",
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


@router.get("", response_model=ListResponse)
def list_tasks(user_id: str = Depends(require_current_user_id)) -> ListResponse:
    repositories.mark_stale_processing_tasks_failed(user_id)
    items = [item["task"] for item in repositories.list_user_task_items(user_id)]
    return ListResponse(items=items, total=len(items))


@router.get("/{task_id}", response_model=TaskDetail)
def get_task(task_id: str, user_id: str = Depends(require_current_user_id)) -> TaskDetail:
    repositories.mark_stale_processing_tasks_failed(user_id)
    return TaskDetail(**_get_fresh_task_detail(task_id, user_id))


def _runtime_payload(runtime_detail) -> dict:
    return {"task": runtime_detail.task, "file": runtime_detail.file, "result": runtime_detail.result}


def _get_fresh_task_detail(task_id: str, user_id: str) -> dict:
    db_detail = repositories.get_task_detail_for_user(task_id, user_id)
    runtime_detail = task_runtime.get(task_id, user_id)
    if runtime_detail is None:
        return db_detail

    db_status = db_detail["task"].status
    runtime_status = runtime_detail.task.status
    if db_status in {MeetingTaskStatus.finished, MeetingTaskStatus.failed, MeetingTaskStatus.canceled}:
        return db_detail
    if runtime_status == MeetingTaskStatus.waiting_process and db_status != MeetingTaskStatus.waiting_process:
        return db_detail
    return _runtime_payload(runtime_detail)


@router.patch("/{task_id}", response_model=MeetingTask)
def update_task(task_id: str, request: TaskUpdateRequest, user_id: str = Depends(require_current_user_id)) -> MeetingTask:
    task = repositories.update_task_for_user(
        task_id,
        user_id,
        title=request.title,
        confirmed=request.confirmed,
        created_at_millis=request.created_at_millis,
        is_private=request.is_private,
        knowledge_scope=request.knowledge_scope,
    )
    result = repositories.get_result(task.id)
    if result is not None:
        index_meeting_result(task.title, result)
    return task


@router.post("/{task_id}/process", response_model=TaskDetail)
def process_task(task_id: str, request: TaskProcessingContextRequest | None = None, user_id: str = Depends(require_current_user_id)) -> TaskDetail:
    detail = _get_fresh_task_detail(task_id, user_id)
    task = detail["task"]
    if task.status == MeetingTaskStatus.processing:
        return TaskDetail(**detail)
    if task.status == MeetingTaskStatus.finished:
        return get_task(task_id, user_id)
    if task.status == MeetingTaskStatus.canceled:
        return get_task(task_id, user_id)
    if task.status == MeetingTaskStatus.failed:
        raise HTTPException(status_code=409, detail="任务已失败，请使用重试接口重新处理")
    if task.status == MeetingTaskStatus.waiting_process:
        with _processing_lock:
            detail = _get_fresh_task_detail(task_id, user_id)
            task = detail["task"]
            if task.status == MeetingTaskStatus.processing:
                return TaskDetail(**detail)
            if task.status == MeetingTaskStatus.finished:
                return get_task(task_id, user_id)
            if task.status == MeetingTaskStatus.canceled:
                return get_task(task_id, user_id)
            if task.status == MeetingTaskStatus.failed:
                raise HTTPException(status_code=409, detail="任务已失败，请使用重试接口重新处理")
            if task.status == MeetingTaskStatus.waiting_process:
                membership_repositories.require_transcription_quota(user_id)
                runtime_task = task.model_copy(
                    update={
                        "status": MeetingTaskStatus.processing,
                        "error_message": None,
                        "progress_percent": 0.0,
                        "progress_label": "正在准备处理",
                        "progress_stage": "preparing",
                    }
                )
                repositories.update_task_status(
                    task.id,
                    MeetingTaskStatus.processing,
                    None,
                    progress_percent=0.0,
                    progress_label="正在准备处理",
                    progress_stage="preparing",
                )
                task_runtime.start(
                    user_id,
                    runtime_task,
                    detail["file"],
                    meeting_note=(request.meeting_note if request else None),
                    schedule_id=(request.schedule_id if request else None),
                    recognition_language=(request.recognition_language if request else None),
                    transcripts=(request.transcripts if request else None),
                )
                processing_scheduler.submit(task_id, _run_task_processing_safely)
    return get_task(task_id, user_id)


@router.post("/{task_id}/retry", response_model=TaskDetail)
def retry_task(task_id: str, request: TaskProcessingContextRequest | None = None, user_id: str = Depends(require_current_user_id)) -> TaskDetail:
    with _processing_lock:
        detail = _get_fresh_task_detail(task_id, user_id)
        task = detail["task"]
        if task.status == MeetingTaskStatus.processing:
            return TaskDetail(**detail)
        if task.status == MeetingTaskStatus.finished:
            return TaskDetail(**detail)
        membership_repositories.require_transcription_quota(user_id)
        runtime_task = task.model_copy(
            update={
                "status": MeetingTaskStatus.processing,
                "error_message": None,
                "progress_percent": 0.0,
                "progress_label": "正在准备处理",
                "progress_stage": "preparing",
            }
        )
        repositories.update_task_status(
            task.id,
            MeetingTaskStatus.processing,
            None,
            progress_percent=0.0,
            progress_label="正在准备处理",
            progress_stage="preparing",
        )
        task_runtime.start(
            user_id,
            runtime_task,
            detail["file"],
            meeting_note=(request.meeting_note if request else None),
            schedule_id=(request.schedule_id if request else None),
            recognition_language=(request.recognition_language if request else None),
            transcripts=(request.transcripts if request else None),
        )
        processing_scheduler.submit(task_id, _run_task_processing_safely)
    return get_task(task_id, user_id)


def _run_task_processing_safely(task_id: str) -> None:
    try:
        run_task_processing(task_id)
    except task_runtime.TaskCanceledError:
        logger.info("会议处理任务已终止：%s", task_id)
    except Exception as exc:
        logger.exception("会议处理任务异常结束：%s", task_id)
        try:
            user_message = user_message_from_exception(exc)
            if _is_transient_processing_message(user_message):
                repositories.update_task_status(
                    task_id,
                    MeetingTaskStatus.waiting_process,
                    "处理暂未完成，稍后可继续",
                    progress_label="可继续处理",
                    progress_stage="waiting_retry",
                )
                return
            repositories.update_task_status(
                task_id,
                MeetingTaskStatus.failed,
                user_message,
                progress_label="处理失败",
                progress_stage="failed",
            )
        except Exception:
            logger.exception("写入会议处理失败状态失败：%s", task_id)


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


@router.post("/{task_id}/cancel", response_model=MeetingTask)
def cancel_task(task_id: str, user_id: str = Depends(require_current_user_id)) -> MeetingTask:
    task = repositories.get_task_for_user(task_id, user_id)
    task_runtime.request_cancel(task_id, user_id)
    cancel_asr_task(task_id)
    if task.status in {MeetingTaskStatus.finished, MeetingTaskStatus.canceled}:
        return task
    return repositories.update_task_status(
        task.id,
        MeetingTaskStatus.canceled,
        "任务已终止",
        progress_percent=task.progress_percent,
        progress_label="已终止",
        progress_stage="canceled",
    )


@router.get("/{task_id}/result", response_model=MeetingProcessingResult)
def get_task_result(task_id: str, user_id: str = Depends(require_current_user_id)) -> MeetingProcessingResult:
    repositories.require_task_owner(task_id, user_id)
    result = repositories.get_result(task_id)
    if result is None:
        raise HTTPException(status_code=404, detail="任务结果不存在")
    return result


@router.get("/{task_id}/audio")
def download_task_audio(task_id: str, user_id: str = Depends(require_current_user_id)) -> FileResponse:
    task = repositories.get_task_for_user(task_id, user_id)
    file = repositories.get_file_for_user(task.file_id, user_id)
    source = Path(file.stored_path)
    if not source.exists() or not source.is_file():
        raise HTTPException(status_code=404, detail="云端音频文件不存在")
    return FileResponse(
        source,
        media_type=file.content_type or "application/octet-stream",
        filename=file.original_name,
    )


@router.get("/{task_id}/audio-temp")
def download_task_audio_temp(task_id: str, expires: int, signature: str) -> FileResponse:
    if not verify_temp_audio_signature(task_id, expires, signature):
        raise HTTPException(status_code=403, detail="临时音频链接已失效")
    task = repositories.get_task(task_id)
    file = repositories.get_file(task.file_id)
    source = Path(file.stored_path)
    if not source.exists() or not source.is_file():
        raise HTTPException(status_code=404, detail="音频文件不存在")
    return FileResponse(
        source,
        media_type=file.content_type or "application/octet-stream",
        filename=file.original_name,
    )


@router.put("/{task_id}/result", response_model=MeetingProcessingResult)
def update_task_result(task_id: str, request: ResultUpdateRequest, user_id: str = Depends(require_current_user_id)) -> MeetingProcessingResult:
    task = repositories.get_task_for_user(task_id, user_id)
    current = repositories.get_result(task.id)
    should_identify_synced_result = False
    if current is None:
        file = repositories.get_file_for_user(task.file_id, user_id)
        if request.summary is None or request.decisions is None or request.todos is None or request.transcripts is None:
            raise HTTPException(status_code=422, detail="首次同步会议结果时必须提供摘要、决策、待办和转写")
        result = MeetingProcessingResult(
            task_id=task.id,
            source_file_path=file.stored_path,
            participants=request.participants,
            tags=request.tags or [],
            summary=request.summary,
            topics=request.topics or [],
            decisions=request.decisions,
            todos=request.todos,
            risks=request.risks or [],
            transcripts=request.transcripts,
            generated_at=repositories.now_iso(),
        )
        should_identify_synced_result = _has_anonymous_speaker(result)
    else:
        result = current.model_copy(
            update={
                key: value
                for key, value in {
                    "summary": request.summary,
                    "participants": request.participants,
                    "tags": request.tags,
                    "topics": request.topics,
                    "decisions": request.decisions,
                    "todos": request.todos,
                    "risks": request.risks,
                    "transcripts": request.transcripts,
                    "generated_at": repositories.now_iso(),
                }.items()
                if value is not None
            }
        )
    repositories.save_result(result)
    index_meeting_result(task.title, result)
    repositories.update_task_status(
        task.id,
        MeetingTaskStatus.finished,
        progress_percent=100,
        progress_label="处理完成",
        progress_stage="finished",
    )
    if should_identify_synced_result:
        threading.Thread(
            target=_identify_initial_synced_result_safely,
            args=(user_id, task, result),
            daemon=True,
        ).start()
    return result


def _identify_initial_synced_result_safely(user_id: str, task: MeetingTask, result: MeetingProcessingResult) -> None:
    if not _has_anonymous_speaker(result):
        return
    started = time.perf_counter()
    try:
        participant_names = voiceprints.participant_names_for_task(user_id, None, fallback_text=result.participants)
        identified, matched_count, profile_count, extracted = voiceprints.identify_task_speakers_from_audio(
            user_id,
            task,
            result.source_file_path,
            result.transcripts,
            participant_names=participant_names,
            require_profiles=True,
        )
        if not extracted or matched_count <= 0:
            return
        logger.info(
            "云端初次同步任务 %s 声纹识别命中 %s/%s 个档案，耗时 %.2fs",
            task.id,
            matched_count,
            profile_count,
            time.perf_counter() - started,
        )
        updated = result.model_copy(
            update={
                "transcripts": identified,
                "generated_at": repositories.now_iso(),
            }
        )
        repositories.save_result(updated)
        index_meeting_result(task.title, updated)
    except Exception:
        logger.exception("云端初次同步任务 %s 声纹识别失败，保留原始结果", task.id)


def _has_anonymous_speaker(result: MeetingProcessingResult) -> bool:
    return any(str(segment.speaker or "").strip().startswith("说话人") for segment in result.transcripts)


@router.post("/{task_id}/regenerate-minutes", response_model=MeetingProcessingResult)
def regenerate_task_minutes(task_id: str, request: RegenerateMinutesRequest, user_id: str = Depends(require_current_user_id)) -> MeetingProcessingResult:
    repositories.require_task_owner(task_id, user_id)
    return run_minutes_regeneration(task_id, request.transcripts, request.meeting_note)


@router.post("/regenerate-local-minutes", response_model=MeetingProcessingResult)
def regenerate_local_task_minutes(request: LocalRegenerateMinutesRequest) -> MeetingProcessingResult:
    return run_local_minutes_regeneration(
        task_id=request.task_id,
        title=request.title,
        source_file_path=request.source_file_path,
        participants=request.participants,
        tags=request.tags,
        meeting_note=request.meeting_note,
        transcripts=request.transcripts,
    )


@router.get("/{task_id}/export")
def export_task_result(task_id: str, format: str = "markdown", include_transcript: bool = False, user_id: str = Depends(require_current_user_id)) -> Response:
    repositories.require_task_owner(task_id, user_id)
    normalized = "txt" if format.lower() == "txt" else "markdown"
    text = repositories.export_result_text(task_id, normalized, include_transcript=include_transcript)
    media_type = "text/plain; charset=utf-8" if normalized == "txt" else "text/markdown; charset=utf-8"
    suffix = "txt" if normalized == "txt" else "md"
    return Response(
        content=text,
        media_type=media_type,
        headers={"Content-Disposition": f'attachment; filename="{task_id}.{suffix}"'},
    )


@router.delete("/{task_id}")
def delete_task(task_id: str, user_id: str = Depends(require_current_user_id)) -> dict:
    repositories.get_task_for_user(task_id, user_id)
    task_runtime.request_cancel(task_id, user_id)
    cancel_asr_task(task_id)
    repositories.delete_user_task_tree(task_id, user_id)
    clear_knowledge_cache(user_id)
    task_runtime.remove(task_id)
    return {"message": "会议任务、结果、知识库索引和服务端临时文件已删除"}
