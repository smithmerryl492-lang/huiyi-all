import threading
from dataclasses import dataclass

from app.schemas import FileRecord, MeetingProcessingResult, MeetingTask, MeetingTaskStatus


class TaskCanceledError(Exception):
    pass


@dataclass
class RuntimeTaskDetail:
    user_id: str
    task: MeetingTask
    file: FileRecord
    result: MeetingProcessingResult | None = None
    meeting_note: str | None = None
    schedule_id: str | None = None
    recognition_language: str | None = None
    transcripts: list | None = None


_LOCK = threading.Lock()
_DETAILS: dict[str, RuntimeTaskDetail] = {}
_CANCELED_TASK_IDS: set[str] = set()


def start(
    user_id: str,
    task: MeetingTask,
    file: FileRecord,
    meeting_note: str | None = None,
    schedule_id: str | None = None,
    recognition_language: str | None = None,
    transcripts: list | None = None,
) -> None:
    with _LOCK:
        _CANCELED_TASK_IDS.discard(task.id)
        _DETAILS[task.id] = RuntimeTaskDetail(
            user_id=user_id,
            task=task,
            file=file,
            meeting_note=meeting_note,
            schedule_id=schedule_id,
            recognition_language=recognition_language,
            transcripts=transcripts,
        )


def get(task_id: str, user_id: str) -> RuntimeTaskDetail | None:
    with _LOCK:
        detail = _DETAILS.get(task_id)
        if detail is None or detail.user_id != user_id:
            return None
        return RuntimeTaskDetail(
            user_id=detail.user_id,
            task=detail.task,
            file=detail.file,
            result=detail.result,
            meeting_note=detail.meeting_note,
            schedule_id=detail.schedule_id,
            recognition_language=detail.recognition_language,
            transcripts=detail.transcripts,
        )


def get_any(task_id: str) -> RuntimeTaskDetail | None:
    with _LOCK:
        detail = _DETAILS.get(task_id)
        if detail is None:
            return None
        return RuntimeTaskDetail(
            user_id=detail.user_id,
            task=detail.task,
            file=detail.file,
            result=detail.result,
            meeting_note=detail.meeting_note,
            schedule_id=detail.schedule_id,
            recognition_language=detail.recognition_language,
            transcripts=detail.transcripts,
        )


def update_progress(
    task_id: str,
    progress_percent: float,
    progress_label: str,
    progress_stage: str,
    status: MeetingTaskStatus = MeetingTaskStatus.processing,
) -> None:
    with _LOCK:
        detail = _DETAILS.get(task_id)
        if detail is None:
            return
        if detail.task.status == MeetingTaskStatus.canceled:
            return
        detail.task = detail.task.model_copy(
            update={
                "status": status,
                "error_message": None,
                "progress_percent": max(0.0, min(float(progress_percent), 100.0)),
                "progress_label": progress_label,
                "progress_stage": progress_stage,
            }
        )


def request_cancel(task_id: str, user_id: str) -> RuntimeTaskDetail | None:
    with _LOCK:
        _CANCELED_TASK_IDS.add(task_id)
        detail = _DETAILS.get(task_id)
        if detail is None or detail.user_id != user_id:
            return None
        detail.task = detail.task.model_copy(
            update={
                "status": MeetingTaskStatus.canceled,
                "error_message": "任务已终止",
                "progress_label": "已终止",
                "progress_stage": "canceled",
            }
        )
        return RuntimeTaskDetail(
            user_id=detail.user_id,
            task=detail.task,
            file=detail.file,
            result=detail.result,
            meeting_note=detail.meeting_note,
            schedule_id=detail.schedule_id,
            recognition_language=detail.recognition_language,
            transcripts=detail.transcripts,
        )


def is_cancelled(task_id: str) -> bool:
    with _LOCK:
        if task_id in _CANCELED_TASK_IDS:
            return True
        detail = _DETAILS.get(task_id)
        return detail is not None and detail.task.status == MeetingTaskStatus.canceled


def finish(task_id: str, result: MeetingProcessingResult) -> None:
    with _LOCK:
        detail = _DETAILS.get(task_id)
        if detail is None:
            return
        if detail.task.status == MeetingTaskStatus.canceled:
            return
        detail.task = detail.task.model_copy(
            update={
                "status": MeetingTaskStatus.finished,
                "error_message": None,
                "progress_percent": 100.0,
                "progress_label": "处理完成",
                "progress_stage": "finished",
            }
        )
        detail.result = result


def fail(task_id: str, error_message: str) -> None:
    with _LOCK:
        detail = _DETAILS.get(task_id)
        if detail is None:
            return
        if detail.task.status == MeetingTaskStatus.canceled:
            return
        detail.task = detail.task.model_copy(
            update={
                "status": MeetingTaskStatus.failed,
                "error_message": error_message,
                "progress_label": "处理失败",
                "progress_stage": "failed",
            }
        )


def remove(task_id: str) -> None:
    with _LOCK:
        _DETAILS.pop(task_id, None)
