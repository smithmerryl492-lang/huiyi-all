import logging
from concurrent.futures import Future, ThreadPoolExecutor
from typing import Callable

from app.core.config import settings


logger = logging.getLogger(__name__)

_EXECUTOR = ThreadPoolExecutor(
    max_workers=settings.task_processing_concurrency,
    thread_name_prefix="meeting-processing",
)


def submit(task_id: str, runner: Callable[[str], None]) -> None:
    future = _EXECUTOR.submit(runner, task_id)
    future.add_done_callback(lambda item: _log_done(task_id, item))


def _log_done(task_id: str, future: Future) -> None:
    try:
        future.result()
    except Exception:
        logger.exception("会议处理 worker 异常退出：%s", task_id)
