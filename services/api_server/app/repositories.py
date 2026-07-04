import json
import shutil
import time
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import HTTPException
from sqlalchemy import delete, func, insert, select, update
from sqlalchemy.dialects.mysql import insert as mysql_insert
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.exc import OperationalError

from app.core.config import settings
from app.db import (
    connect,
    engine,
    files_table,
    knowledge_chunks_table,
    meeting_speaker_embeddings_table,
    results_table,
    scheduled_meetings_table,
    sms_verification_codes_table,
    speaker_profile_samples_table,
    speaker_profiles_table,
    tasks_table,
    users_table,
)
from app.schemas import (
    FileRecord,
    MeetingProcessingResult,
    MeetingTask,
    MeetingTaskSource,
    MeetingTaskStatus,
    KnowledgeScope,
    RiskItem,
    TaskSyncScope,
    TodoItem,
    TopicItem,
    TranscriptSegment,
    SpeakerProfile,
)

DEFAULT_USER_ID = "user-336496"


def now_iso() -> str:
    return datetime.now(UTC).isoformat()


def _timestamp_to_iso(value: Any) -> str:
    if isinstance(value, datetime):
        timestamp = value if value.tzinfo is not None else value.replace(tzinfo=UTC)
        return timestamp.astimezone(UTC).isoformat()
    return str(value)


def _db_retry(operation):
    for attempt in range(3):
        try:
            return operation()
        except OperationalError:
            engine.dispose()
            if attempt >= 2:
                raise
            time.sleep(0.8 * (attempt + 1))
    raise RuntimeError("数据库操作失败")


def upsert_user_by_phone(phone_e164: str) -> dict[str, str | None]:
    clean_phone = phone_e164.strip()
    if not clean_phone:
        raise HTTPException(status_code=422, detail="手机号不能为空")
    timestamp = now_iso()

    def operation():
        with connect() as conn:
            row = conn.execute(select(users_table).where(users_table.c.phone_e164 == clean_phone)).fetchone()
            if row is not None:
                data = _mapping(row)
                conn.execute(
                    update(users_table)
                    .where(users_table.c.id == data["id"])
                    .values(last_login_at=timestamp, phone_verified_at=data.get("phone_verified_at") or timestamp, updated_at=timestamp)
                )
                return {
                    "user_id": data["id"],
                    "username": data["username"],
                    "display_name": data["display_name"],
                    "phone_e164": data.get("phone_e164"),
                }
            user_id = f"user-{uuid.uuid4()}"
            display_name = _display_name_for_phone(clean_phone)
            conn.execute(
                insert(users_table).values(
                    id=user_id,
                    username=clean_phone,
                    phone_e164=clean_phone,
                    display_name=display_name,
                    phone_verified_at=timestamp,
                    last_login_at=timestamp,
                    created_at=timestamp,
                    updated_at=timestamp,
                )
            )
            return {
                "user_id": user_id,
                "username": clean_phone,
                "display_name": display_name,
                "phone_e164": clean_phone,
            }

    return _db_retry(operation)


def get_user_by_id(user_id: str) -> dict[str, Any] | None:
    def operation():
        with connect() as conn:
            row = conn.execute(select(users_table).where(users_table.c.id == user_id)).fetchone()
            return _mapping(row) if row is not None else None

    return _db_retry(operation)


def get_user_by_phone(phone_e164: str) -> dict[str, Any] | None:
    clean_phone = phone_e164.strip()
    if not clean_phone:
        return None

    def operation():
        with connect() as conn:
            row = conn.execute(select(users_table).where(users_table.c.phone_e164 == clean_phone)).fetchone()
            return _mapping(row) if row is not None else None

    return _db_retry(operation)


def update_user_phone(user_id: str, new_phone_e164: str) -> dict[str, Any]:
    clean_phone = new_phone_e164.strip()
    if not clean_phone:
        raise HTTPException(status_code=422, detail="手机号不能为空")
    timestamp = now_iso()

    def operation():
        with connect() as conn:
            row = conn.execute(select(users_table).where(users_table.c.id == user_id)).fetchone()
            if row is None:
                raise HTTPException(status_code=404, detail="用户不存在")
            data = _mapping(row)
            existing = conn.execute(
                select(users_table).where((users_table.c.phone_e164 == clean_phone) & (users_table.c.id != user_id))
            ).fetchone()
            if existing is not None:
                raise HTTPException(status_code=409, detail="该手机号已绑定其他账号")

            old_display_name = str(data.get("display_name") or "")
            old_generated_name = _display_name_for_phone(str(data.get("phone_e164") or ""))
            values = {
                "username": clean_phone,
                "phone_e164": clean_phone,
                "phone_verified_at": timestamp,
                "updated_at": timestamp,
            }
            if not old_display_name or old_display_name == old_generated_name:
                values["display_name"] = _display_name_for_phone(clean_phone)
            conn.execute(update(users_table).where(users_table.c.id == user_id).values(**values))
            updated = conn.execute(select(users_table).where(users_table.c.id == user_id)).fetchone()
            return _mapping(updated)

    return _db_retry(operation)


def set_user_password(user_id: str, password_hash: str) -> dict[str, Any]:
    timestamp = now_iso()

    def operation():
        with connect() as conn:
            row = conn.execute(select(users_table).where(users_table.c.id == user_id)).fetchone()
            if row is None:
                raise HTTPException(status_code=404, detail="用户不存在")
            data = _mapping(row)
            values = {
                "password_hash": password_hash,
                "password_updated_at": timestamp,
                "password_failed_attempts": 0,
                "password_locked_until": None,
                "updated_at": timestamp,
            }
            if not data.get("password_set_at"):
                values["password_set_at"] = timestamp
            conn.execute(update(users_table).where(users_table.c.id == user_id).values(**values))
            updated = conn.execute(select(users_table).where(users_table.c.id == user_id)).fetchone()
            return _mapping(updated)

    return _db_retry(operation)


def mark_password_login_success(user_id: str) -> dict[str, Any]:
    timestamp = now_iso()

    def operation():
        with connect() as conn:
            conn.execute(
                update(users_table)
                .where(users_table.c.id == user_id)
                .values(
                    last_login_at=timestamp,
                    password_failed_attempts=0,
                    password_locked_until=None,
                    updated_at=timestamp,
                )
            )
            row = conn.execute(select(users_table).where(users_table.c.id == user_id)).fetchone()
            if row is None:
                raise HTTPException(status_code=404, detail="用户不存在")
            return _mapping(row)

    return _db_retry(operation)


def record_password_login_failure(user_id: str) -> dict[str, Any]:
    timestamp = now_iso()
    now = datetime.now(UTC)
    lock_until = (now + timedelta(minutes=15)).isoformat()

    def operation():
        with connect() as conn:
            row = conn.execute(select(users_table).where(users_table.c.id == user_id)).fetchone()
            if row is None:
                raise HTTPException(status_code=404, detail="用户不存在")
            data = _mapping(row)
            previous_lock = _parse_iso_or_none(str(data.get("password_locked_until") or ""))
            previous_attempts = 0 if previous_lock is not None and previous_lock <= now else int(data.get("password_failed_attempts") or 0)
            attempts = previous_attempts + 1
            conn.execute(
                update(users_table)
                .where(users_table.c.id == user_id)
                .values(
                    password_failed_attempts=attempts,
                    password_locked_until=lock_until if attempts >= 5 else None,
                    updated_at=timestamp,
                )
            )
            updated = conn.execute(select(users_table).where(users_table.c.id == user_id)).fetchone()
            return _mapping(updated)

    return _db_retry(operation)


def _parse_iso_or_none(value: str) -> datetime | None:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=UTC)
    return parsed.astimezone(UTC)


def _display_name_for_phone(phone_e164: str) -> str:
    digits = "".join(ch for ch in phone_e164 if ch.isdigit())
    if len(digits) >= 11:
        local = digits[-11:]
        return f"用户 {local[:3]}****{local[-4:]}"
    return "用户"


def create_sms_verification_code(phone_e164: str, scene: str, code_hash: str, expires_at: str, request_ip: str | None) -> str:
    code_id = str(uuid.uuid4())
    timestamp = now_iso()

    def operation():
        with connect() as conn:
            conn.execute(
                insert(sms_verification_codes_table).values(
                    id=code_id,
                    phone_e164=phone_e164,
                    scene=scene,
                    code_hash=code_hash,
                    expires_at=expires_at,
                    consumed_at=None,
                    attempts=0,
                    request_ip=request_ip,
                    created_at=timestamp,
                    updated_at=timestamp,
                )
            )
        return code_id

    return _db_retry(operation)


def get_latest_sms_verification_code(phone_e164: str, scene: str) -> dict[str, Any] | None:
    def operation():
        with connect() as conn:
            row = conn.execute(
                select(sms_verification_codes_table)
                .where(
                    (sms_verification_codes_table.c.phone_e164 == phone_e164)
                    & (sms_verification_codes_table.c.scene == scene)
                    & (sms_verification_codes_table.c.consumed_at.is_(None))
                )
                .order_by(sms_verification_codes_table.c.created_at.desc())
                .limit(1)
            ).fetchone()
            return _mapping(row) if row is not None else None

    return _db_retry(operation)


def count_sms_verification_codes(phone_e164: str | None = None, request_ip: str | None = None, since_iso: str | None = None) -> int:
    def operation():
        with connect() as conn:
            query = select(func.count()).select_from(sms_verification_codes_table)
            if phone_e164 is not None:
                query = query.where(sms_verification_codes_table.c.phone_e164 == phone_e164)
            if request_ip is not None:
                query = query.where(sms_verification_codes_table.c.request_ip == request_ip)
            if since_iso is not None:
                query = query.where(sms_verification_codes_table.c.created_at >= since_iso)
            return int(conn.execute(query).scalar() or 0)

    return _db_retry(operation)


def increment_sms_verification_attempts(code_id: str) -> None:
    timestamp = now_iso()

    def operation():
        with connect() as conn:
            conn.execute(
                update(sms_verification_codes_table)
                .where(sms_verification_codes_table.c.id == code_id)
                .values(
                    attempts=sms_verification_codes_table.c.attempts + 1,
                    updated_at=timestamp,
                )
            )

    _db_retry(operation)


def consume_sms_verification_code(code_id: str) -> None:
    timestamp = now_iso()

    def operation():
        with connect() as conn:
            conn.execute(
                update(sms_verification_codes_table)
                .where(sms_verification_codes_table.c.id == code_id)
                .values(consumed_at=timestamp, updated_at=timestamp)
            )

    _db_retry(operation)


def create_file_record(
    original_name: str,
    stored_path: str,
    content_type: str,
    size_bytes: int,
    user_id: str = DEFAULT_USER_ID,
) -> FileRecord:
    ensure_user_exists(user_id)
    record = FileRecord(
        id=str(uuid.uuid4()),
        original_name=original_name,
        stored_path=stored_path,
        content_type=content_type,
        size_bytes=size_bytes,
        created_at=now_iso(),
    )
    payload = record.model_dump()
    payload["user_id"] = user_id
    with connect() as conn:
        conn.execute(insert(files_table).values(**payload))
    return record


def create_file_and_task_with_options(
    original_name: str,
    stored_path: str,
    content_type: str,
    size_bytes: int,
    source: MeetingTaskSource,
    user_id: str = DEFAULT_USER_ID,
    sync_scope: TaskSyncScope = TaskSyncScope.cloud,
    knowledge_scope: KnowledgeScope = KnowledgeScope.cloud,
    is_private: bool = False,
    device_id: str | None = None,
    client_task_id: str | None = None,
    confirmed: bool = False,
    created_at_millis: int | None = None,
) -> tuple[FileRecord, MeetingTask]:
    timestamp = now_iso()
    record = FileRecord(
        id=str(uuid.uuid4()),
        original_name=original_name,
        stored_path=stored_path,
        content_type=content_type,
        size_bytes=size_bytes,
        created_at=timestamp,
    )
    task = MeetingTask(
        id=str(uuid.uuid4()),
        file_id=record.id,
        client_task_id=client_task_id,
        title=record.original_name,
        source=source,
        status=MeetingTaskStatus.waiting_process,
        progress_percent=0.0,
        progress_label="待处理",
        progress_stage="waiting",
        sync_scope=sync_scope,
        knowledge_scope=knowledge_scope,
        is_private=is_private,
        device_id=device_id,
        confirmed=confirmed,
        created_at_millis=created_at_millis,
        created_at=timestamp,
        updated_at=timestamp,
    )
    file_payload = record.model_dump()
    file_payload["user_id"] = user_id
    task_payload = task.model_dump()
    task_payload["user_id"] = user_id
    task_payload["source"] = task.source.value
    task_payload["status"] = task.status.value
    task_payload["sync_scope"] = sync_scope.value
    task_payload["knowledge_scope"] = knowledge_scope.value
    try:
        with connect() as conn:
            _ensure_user_exists_in_conn(conn, user_id)
            conn.execute(insert(files_table).values(**file_payload))
            conn.execute(insert(tasks_table).values(**task_payload))
    except Exception:
        _cleanup_upload_records(record.id, task.id)
        raise
    return record, task


def create_task(file: FileRecord, source: MeetingTaskSource, user_id: str = DEFAULT_USER_ID) -> MeetingTask:
    ensure_user_exists(user_id)
    timestamp = now_iso()
    task = MeetingTask(
        id=str(uuid.uuid4()),
        file_id=file.id,
        client_task_id=None,
        title=file.original_name,
        source=source,
        status=MeetingTaskStatus.waiting_process,
        progress_percent=0.0,
        progress_label="待处理",
        progress_stage="waiting",
        created_at=timestamp,
        updated_at=timestamp,
    )
    payload = task.model_dump()
    payload["user_id"] = user_id
    payload["source"] = task.source.value
    payload["status"] = task.status.value
    with connect() as conn:
        conn.execute(insert(tasks_table).values(**payload))
    return task


def create_task_with_options(
    file: FileRecord,
    source: MeetingTaskSource,
    user_id: str = DEFAULT_USER_ID,
    sync_scope: TaskSyncScope = TaskSyncScope.cloud,
    knowledge_scope: KnowledgeScope = KnowledgeScope.cloud,
    is_private: bool = False,
    device_id: str | None = None,
    client_task_id: str | None = None,
    confirmed: bool = False,
    created_at_millis: int | None = None,
) -> MeetingTask:
    with connect() as conn:
        _ensure_user_exists_in_conn(conn, user_id)
        file_row = conn.execute(
            select(files_table).where((files_table.c.id == file.id) & (files_table.c.user_id == user_id))
        ).fetchone()
        if file_row is None:
            raise HTTPException(status_code=404, detail="文件不存在或无权访问")
        timestamp = now_iso()
        task = MeetingTask(
            id=str(uuid.uuid4()),
            file_id=file.id,
            client_task_id=client_task_id,
            title=file.original_name,
            source=source,
            status=MeetingTaskStatus.waiting_process,
            progress_percent=0.0,
            progress_label="待处理",
            progress_stage="waiting",
            sync_scope=sync_scope,
            knowledge_scope=knowledge_scope,
            is_private=is_private,
            device_id=device_id,
            confirmed=confirmed,
            created_at_millis=created_at_millis,
            created_at=timestamp,
            updated_at=timestamp,
        )
        payload = task.model_dump()
        payload["user_id"] = user_id
        payload["source"] = task.source.value
        payload["status"] = task.status.value
        payload["sync_scope"] = sync_scope.value
        payload["knowledge_scope"] = knowledge_scope.value
        conn.execute(insert(tasks_table).values(**payload))
    return task


def list_tasks() -> list[MeetingTask]:
    def operation():
        with connect() as conn:
            return conn.execute(select(tasks_table).order_by(tasks_table.c.created_at.desc())).fetchall()

    rows = _db_retry(operation)
    return [_task_from_row(row) for row in rows]


def list_user_task_items(
    user_id: str,
    sync_scope: TaskSyncScope | None = None,
    require_result: bool = False,
) -> list[dict[str, Any]]:
    ensure_user_exists(user_id)

    def load_tasks_and_files():
        with connect() as conn:
            stmt = select(tasks_table).where(tasks_table.c.user_id == user_id)
            if sync_scope is not None:
                stmt = stmt.where(tasks_table.c.sync_scope == sync_scope.value)
            rows = conn.execute(stmt.order_by(tasks_table.c.created_at.desc())).fetchall()
            file_ids = [row._mapping["file_id"] for row in rows]
            file_rows = conn.execute(select(files_table).where(files_table.c.id.in_(file_ids))).fetchall() if file_ids else []
            return rows, file_rows

    rows, file_rows = _db_retry(load_tasks_and_files)
    files_by_id = {row._mapping["id"]: _file_from_row(row) for row in file_rows}
    results_by_task_id = _db_retry(lambda: _get_results_for_task_ids([row._mapping["id"] for row in rows]))
    items: list[dict[str, Any]] = []
    for row in rows:
        task = _task_from_row(row)
        result = results_by_task_id.get(task.id)
        if require_result and result is None:
            continue
        if result is not None and task.status == MeetingTaskStatus.waiting_process:
            task = task.model_copy(
                update={
                    "status": MeetingTaskStatus.finished,
                    "progress_percent": 100.0,
                    "progress_label": "处理完成",
                    "progress_stage": "finished",
                }
            )
        items.append(
            {
                "task": task,
                "file": files_by_id.get(task.file_id),
                "result": result,
            }
        )
    return [item for item in items if item["file"] is not None]


def cleanup_stale_local_processing_tasks(user_id: str) -> int:
    ensure_user_exists(user_id)
    short_cutoff = (datetime.now(UTC) - timedelta(minutes=10)).isoformat()
    long_cutoff = (datetime.now(UTC) - timedelta(hours=24)).isoformat()

    def operation():
        with connect() as conn:
            rows = conn.execute(
                select(tasks_table)
                .where(
                    (tasks_table.c.user_id == user_id)
                    & (tasks_table.c.sync_scope == TaskSyncScope.local_processing.value)
                )
            ).fetchall()
            if not rows:
                return []
            task_ids = [row._mapping["id"] for row in rows]
            result_rows = conn.execute(select(results_table.c.task_id).where(results_table.c.task_id.in_(task_ids))).fetchall()
            task_ids_with_result = {row._mapping["task_id"] for row in result_rows}
            stale_tasks = []
            for row in rows:
                task = _task_from_row(row)
                has_result = task.id in task_ids_with_result
                cutoff = long_cutoff if has_result or task.status in {MeetingTaskStatus.finished, MeetingTaskStatus.processing} else short_cutoff
                if _timestamp_to_iso(row._mapping["updated_at"]) < cutoff:
                    stale_tasks.append(task)
            if not stale_tasks:
                return []
            stale_task_ids = [task.id for task in stale_tasks]
            stale_file_ids = [task.file_id for task in stale_tasks]
            file_rows = conn.execute(select(files_table).where(files_table.c.id.in_(stale_file_ids))).fetchall()
            files = [_file_from_row(row) for row in file_rows]
            conn.execute(delete(knowledge_chunks_table).where(knowledge_chunks_table.c.task_id.in_(stale_task_ids)))
            conn.execute(delete(meeting_speaker_embeddings_table).where(meeting_speaker_embeddings_table.c.task_id.in_(stale_task_ids)))
            conn.execute(delete(results_table).where(results_table.c.task_id.in_(stale_task_ids)))
            conn.execute(delete(tasks_table).where((tasks_table.c.user_id == user_id) & (tasks_table.c.id.in_(stale_task_ids))))
            conn.execute(delete(files_table).where((files_table.c.user_id == user_id) & (files_table.c.id.in_(stale_file_ids))))
            return files

    files = _db_retry(operation)
    for file in files:
        try:
            from pathlib import Path

            Path(file.stored_path).unlink(missing_ok=True)
        except Exception:
            pass
    return len(files)


def mark_stale_processing_tasks_failed(user_id: str) -> int:
    ensure_user_exists(user_id)
    timeout_seconds = max(0, settings.stale_processing_timeout_seconds)
    if timeout_seconds <= 0:
        return 0
    cutoff = (datetime.now(UTC) - timedelta(seconds=timeout_seconds)).isoformat()
    timestamp = now_iso()

    def operation():
        with connect() as conn:
            result = conn.execute(
                update(tasks_table)
                .where(
                    (tasks_table.c.user_id == user_id)
                    & (tasks_table.c.status == MeetingTaskStatus.processing.value)
                    & (tasks_table.c.updated_at < cutoff)
                )
                .values(
                    status=MeetingTaskStatus.failed.value,
                    error_message="处理异常中断，可重新处理或删除",
                    progress_label="处理异常中断",
                    progress_stage="failed",
                    updated_at=timestamp,
                )
            )
            return result.rowcount or 0

    return _db_retry(operation)


def _get_result_once(task_id: str) -> MeetingProcessingResult | None:
    with connect() as conn:
        row = conn.execute(select(results_table).where(results_table.c.task_id == task_id)).fetchone()
    if row is None:
        return None
    return _result_from_row(row)


def _get_results_for_task_ids(task_ids: list[str]) -> dict[str, MeetingProcessingResult]:
    clean_ids = [task_id for task_id in task_ids if task_id]
    if not clean_ids:
        return {}
    with connect() as conn:
        rows = conn.execute(select(results_table).where(results_table.c.task_id.in_(clean_ids))).fetchall()
    return {_mapping(row)["task_id"]: _result_from_row(row) for row in rows}


def get_task(task_id: str) -> MeetingTask:
    def operation():
        with connect() as conn:
            return conn.execute(select(tasks_table).where(tasks_table.c.id == task_id)).fetchone()

    row = _db_retry(operation)
    if row is None:
        raise HTTPException(status_code=404, detail="任务不存在")
    return _task_from_row(row)


def get_task_for_user(task_id: str, user_id: str) -> MeetingTask:
    ensure_user_exists(user_id)
    def operation():
        with connect() as conn:
            return conn.execute(
                select(tasks_table).where((tasks_table.c.id == task_id) & (tasks_table.c.user_id == user_id))
            ).fetchone()

    row = _db_retry(operation)
    if row is None:
        raise HTTPException(status_code=404, detail="任务不存在或无权访问")
    return _task_from_row(row)


def get_cloud_task_item_by_client_id(user_id: str, client_task_id: str) -> dict[str, Any] | None:
    ensure_user_exists(user_id)
    clean_client_task_id = client_task_id.strip()
    if not clean_client_task_id:
        return None

    def operation():
        with connect() as conn:
            task_row = conn.execute(
                select(tasks_table).where(
                    (tasks_table.c.user_id == user_id)
                    & (tasks_table.c.client_task_id == clean_client_task_id)
                    & (tasks_table.c.sync_scope == TaskSyncScope.cloud.value)
                )
            ).fetchone()
            if task_row is None:
                return None
            task = _task_from_row(task_row)
            file_row = conn.execute(
                select(files_table).where((files_table.c.id == task.file_id) & (files_table.c.user_id == user_id))
            ).fetchone()
            result_row = conn.execute(select(results_table).where(results_table.c.task_id == task.id)).fetchone()
            return task, file_row, result_row

    item = _db_retry(operation)
    if item is None:
        return None
    task, file_row, result_row = item
    if file_row is None:
        return None
    return {
        "task": task,
        "file": _file_from_row(file_row),
        "result": _result_from_row(result_row) if result_row is not None else None,
    }


def get_file(file_id: str) -> FileRecord:
    def operation():
        with connect() as conn:
            return conn.execute(select(files_table).where(files_table.c.id == file_id)).fetchone()

    row = _db_retry(operation)
    if row is None:
        raise HTTPException(status_code=404, detail="文件不存在")
    return _file_from_row(row)


def get_file_for_user(file_id: str, user_id: str) -> FileRecord:
    ensure_user_exists(user_id)
    def operation():
        with connect() as conn:
            return conn.execute(
                select(files_table).where((files_table.c.id == file_id) & (files_table.c.user_id == user_id))
            ).fetchone()

    row = _db_retry(operation)
    if row is None:
        raise HTTPException(status_code=404, detail="文件不存在或无权访问")
    return _file_from_row(row)


def get_file_by_task(task_id: str) -> FileRecord:
    task = get_task(task_id)
    return get_file(task.file_id)


def get_task_detail_for_user(task_id: str, user_id: str) -> dict[str, Any]:
    ensure_user_exists(user_id)

    def operation():
        with connect() as conn:
            task_row = conn.execute(
                select(tasks_table).where((tasks_table.c.id == task_id) & (tasks_table.c.user_id == user_id))
            ).fetchone()
            if task_row is None:
                raise HTTPException(status_code=404, detail="任务不存在或无权访问")
            task = _task_from_row(task_row)
            file_row = conn.execute(
                select(files_table).where((files_table.c.id == task.file_id) & (files_table.c.user_id == user_id))
            ).fetchone()
            if file_row is None:
                raise HTTPException(status_code=404, detail="文件不存在或无权访问")
            result_row = conn.execute(select(results_table).where(results_table.c.task_id == task.id)).fetchone()
            return task, file_row, result_row

    task, file_row, result_row = _db_retry(operation)
    return {
        "task": task,
        "file": _file_from_row(file_row),
        "result": _result_from_row(result_row) if result_row is not None else None,
    }


def require_task_owner(task_id: str, user_id: str) -> MeetingTask:
    return get_task_for_user(task_id, user_id)


def ensure_user_exists(user_id: str, phone_e164: str | None = None) -> None:
    def operation():
        with connect() as conn:
            row = _get_user_row(conn, user_id)
            if row is not None:
                return row
            clean_phone = phone_e164.strip() if phone_e164 else ""
            if not clean_phone:
                return None
            phone_row = conn.execute(
                select(users_table).where((users_table.c.phone_e164 == clean_phone) | (users_table.c.username == clean_phone))
            ).fetchone()
            if phone_row is not None:
                return phone_row if _mapping(phone_row)["id"] == user_id else None
            timestamp = now_iso()
            display_name = _display_name_for_phone(clean_phone)
            conn.execute(
                insert(users_table).values(
                    id=user_id,
                    username=clean_phone,
                    phone_e164=clean_phone,
                    display_name=display_name,
                    phone_verified_at=timestamp,
                    last_login_at=timestamp,
                    created_at=timestamp,
                    updated_at=timestamp,
                )
            )
            return _get_user_row(conn, user_id)

    row = _db_retry(operation)
    if row is None:
        raise HTTPException(status_code=404, detail="用户不存在")


def _get_user_row(conn, user_id: str):
    return conn.execute(select(users_table).where(users_table.c.id == user_id)).fetchone()


def _ensure_user_exists_in_conn(conn, user_id: str) -> None:
    if _get_user_row(conn, user_id) is None:
        raise HTTPException(status_code=404, detail="用户不存在")


def _cleanup_upload_records(file_id: str, task_id: str) -> None:
    try:
        with connect() as conn:
            conn.execute(delete(knowledge_chunks_table).where(knowledge_chunks_table.c.task_id == task_id))
            conn.execute(delete(results_table).where(results_table.c.task_id == task_id))
            conn.execute(delete(tasks_table).where(tasks_table.c.id == task_id))
            conn.execute(delete(files_table).where(files_table.c.id == file_id))
    except Exception:
        pass


def upsert_scheduled_meeting(user_id: str, meeting: dict[str, Any]) -> dict[str, Any]:
    ensure_user_exists(user_id)
    timestamp = now_iso()
    values = {
        "id": meeting["id"],
        "user_id": user_id,
        "time": meeting["time"],
        "title": meeting["title"],
        "participants": meeting["participants"],
        "note": meeting.get("note") or "",
        "duration_label": meeting["duration_label"],
        "reminder_label": meeting.get("reminder_label") or "提前 5 分钟提醒",
        "start_at_millis": meeting.get("start_at_millis"),
        "end_at_millis": meeting.get("end_at_millis"),
        "created_at_millis": meeting["created_at_millis"],
        "status": meeting.get("status") or "pending",
        "calendar_event_id": meeting.get("calendar_event_id"),
        "updated_at": timestamp,
    }
    with connect() as conn:
        conn.execute(
            delete(scheduled_meetings_table).where(
                (scheduled_meetings_table.c.id == meeting["id"])
                & (scheduled_meetings_table.c.user_id == user_id)
            )
        )
        conn.execute(insert(scheduled_meetings_table).values(**values))
    return values


def delete_scheduled_meeting(user_id: str, schedule_id: str) -> None:
    ensure_user_exists(user_id)
    with connect() as conn:
        conn.execute(
            delete(scheduled_meetings_table).where(
                (scheduled_meetings_table.c.id == schedule_id)
                & (scheduled_meetings_table.c.user_id == user_id)
            )
        )


def list_scheduled_meetings(user_id: str) -> list[dict[str, Any]]:
    ensure_user_exists(user_id)
    def operation():
        with connect() as conn:
            return conn.execute(
                select(scheduled_meetings_table)
                .where(scheduled_meetings_table.c.user_id == user_id)
                .order_by(scheduled_meetings_table.c.start_at_millis.asc())
            ).fetchall()

    rows = _db_retry(operation)
    return [_schedule_from_row(row) for row in rows]


def get_scheduled_meeting(user_id: str, schedule_id: str) -> dict[str, Any] | None:
    ensure_user_exists(user_id)
    clean_id = str(schedule_id or "").strip()
    if not clean_id:
        return None

    def operation():
        with connect() as conn:
            return conn.execute(
                select(scheduled_meetings_table)
                .where((scheduled_meetings_table.c.id == clean_id) & (scheduled_meetings_table.c.user_id == user_id))
            ).fetchone()

    row = _db_retry(operation)
    return _schedule_from_row(row) if row is not None else None


def replace_task_speaker_embeddings(user_id: str, task_id: str, speakers: list[dict[str, Any]]) -> None:
    ensure_user_exists(user_id)
    timestamp = now_iso()
    rows = [
        {
            "id": item.get("id") or str(uuid.uuid4()),
            "user_id": user_id,
            "task_id": task_id,
            "speaker_key": item["speaker_key"],
            "speaker_name": item["speaker_name"],
            "embedding_json": json.dumps(item["embedding"], ensure_ascii=False),
            "quality": float(item.get("quality") or 0.0),
            "segment_count": int(item.get("segment_count") or 0),
            "duration_ms": item.get("duration_ms"),
            "created_at": timestamp,
            "updated_at": timestamp,
        }
        for item in speakers
        if item.get("speaker_key") and item.get("embedding")
    ]

    def operation():
        with connect() as conn:
            conn.execute(
                delete(meeting_speaker_embeddings_table)
                .where((meeting_speaker_embeddings_table.c.user_id == user_id) & (meeting_speaker_embeddings_table.c.task_id == task_id))
            )
            if rows:
                conn.execute(insert(meeting_speaker_embeddings_table), rows)

    _db_retry(operation)


def list_task_speaker_embeddings(user_id: str, task_id: str) -> list[dict[str, Any]]:
    ensure_user_exists(user_id)

    def operation():
        with connect() as conn:
            return conn.execute(
                select(meeting_speaker_embeddings_table)
                .where((meeting_speaker_embeddings_table.c.user_id == user_id) & (meeting_speaker_embeddings_table.c.task_id == task_id))
            ).fetchall()

    return [_speaker_embedding_from_row(row) for row in _db_retry(operation)]


def list_speaker_profiles(user_id: str, active_only: bool = False) -> list[dict[str, Any]]:
    ensure_user_exists(user_id)

    def operation():
        with connect() as conn:
            stmt = select(speaker_profiles_table).where(speaker_profiles_table.c.user_id == user_id)
            if active_only:
                stmt = stmt.where(speaker_profiles_table.c.active == True)
            return conn.execute(stmt.order_by(speaker_profiles_table.c.updated_at.desc())).fetchall()

    return [_speaker_profile_from_row(row) for row in _db_retry(operation)]


def get_speaker_profile(user_id: str, profile_id: str) -> dict[str, Any]:
    ensure_user_exists(user_id)

    def operation():
        with connect() as conn:
            return conn.execute(
                select(speaker_profiles_table)
                .where((speaker_profiles_table.c.id == profile_id) & (speaker_profiles_table.c.user_id == user_id))
            ).fetchone()

    row = _db_retry(operation)
    if row is None:
        raise HTTPException(status_code=404, detail="声纹档案不存在")
    return _speaker_profile_from_row(row)


def update_speaker_profile(user_id: str, profile_id: str, display_name: str | None = None, active: bool | None = None) -> SpeakerProfile:
    existing = get_speaker_profile(user_id, profile_id)
    values: dict[str, Any] = {"updated_at": now_iso()}
    if display_name is not None:
        clean_name = display_name.strip()
        if not clean_name:
            raise HTTPException(status_code=422, detail="声纹名称不能为空")
        values["display_name"] = clean_name
    if active is not None:
        values["active"] = bool(active)

    def operation():
        with connect() as conn:
            conn.execute(
                update(speaker_profiles_table)
                .where((speaker_profiles_table.c.id == profile_id) & (speaker_profiles_table.c.user_id == user_id))
                .values(**values)
            )
            if display_name is not None:
                conn.execute(
                    update(meeting_speaker_embeddings_table)
                    .where((meeting_speaker_embeddings_table.c.user_id == user_id) & (meeting_speaker_embeddings_table.c.speaker_key == profile_id))
                    .values(speaker_name=values["display_name"], updated_at=values["updated_at"])
                )
            return conn.execute(
                select(speaker_profiles_table)
                .where((speaker_profiles_table.c.id == profile_id) & (speaker_profiles_table.c.user_id == user_id))
            ).fetchone()

    row = _db_retry(operation)
    data = _speaker_profile_from_row(row)
    return SpeakerProfile(**{key: data[key] for key in ["id", "display_name", "sample_count", "active", "created_at", "updated_at"]})


def delete_speaker_profile(user_id: str, profile_id: str) -> None:
    get_speaker_profile(user_id, profile_id)

    def operation():
        with connect() as conn:
            conn.execute(delete(speaker_profile_samples_table).where(speaker_profile_samples_table.c.profile_id == profile_id))
            conn.execute(delete(speaker_profiles_table).where((speaker_profiles_table.c.id == profile_id) & (speaker_profiles_table.c.user_id == user_id)))

    _db_retry(operation)


def upsert_speaker_profile_sample(
    user_id: str,
    display_name: str,
    embedding: list[float],
    quality: float,
    task_id: str | None = None,
    speaker_key: str | None = None,
    speaker_name: str | None = None,
    profile_id: str | None = None,
    duration_ms: int | None = None,
) -> SpeakerProfile:
    ensure_user_exists(user_id)
    clean_name = display_name.strip()
    if not clean_name:
        raise HTTPException(status_code=422, detail="声纹名称不能为空")
    vector = _normalized_vector(embedding)
    if not vector:
        raise HTTPException(status_code=422, detail="该说话人没有可用于保存的声纹样本")
    timestamp = now_iso()
    sample_id = str(uuid.uuid4())

    def operation():
        with connect() as conn:
            profile_row = None
            if profile_id:
                profile_row = conn.execute(
                    select(speaker_profiles_table)
                    .where((speaker_profiles_table.c.id == profile_id) & (speaker_profiles_table.c.user_id == user_id))
                ).fetchone()
                if profile_row is None:
                    raise HTTPException(status_code=404, detail="声纹档案不存在")
            else:
                profile_rows = conn.execute(
                    select(speaker_profiles_table).where(speaker_profiles_table.c.user_id == user_id)
                ).fetchall()
                for row in profile_rows:
                    if str(row._mapping["display_name"]).strip().lower() == clean_name.lower():
                        profile_row = row
                        break
                if profile_row is None and len(profile_rows) >= settings.max_speaker_profiles_per_user:
                    raise HTTPException(status_code=409, detail=f"每个账号最多保存 {settings.max_speaker_profiles_per_user} 个声纹档案")

            if profile_row is None:
                target_profile_id = f"vp-{uuid.uuid4()}"
                conn.execute(
                    insert(speaker_profiles_table).values(
                        id=target_profile_id,
                        user_id=user_id,
                        display_name=clean_name,
                        centroid_json=json.dumps(vector, ensure_ascii=False),
                        sample_count=0,
                        active=True,
                        created_at=timestamp,
                        updated_at=timestamp,
                    )
                )
            else:
                target_profile_id = profile_row._mapping["id"]

            conn.execute(
                insert(speaker_profile_samples_table).values(
                    id=sample_id,
                    profile_id=target_profile_id,
                    user_id=user_id,
                    task_id=task_id,
                    speaker_key=speaker_key,
                    speaker_name=speaker_name,
                    embedding_json=json.dumps(vector, ensure_ascii=False),
                    quality=float(quality or 0.0),
                    duration_ms=duration_ms,
                    created_at=timestamp,
                )
            )

            sample_rows = conn.execute(
                select(speaker_profile_samples_table)
                .where((speaker_profile_samples_table.c.profile_id == target_profile_id) & (speaker_profile_samples_table.c.user_id == user_id))
                .order_by(speaker_profile_samples_table.c.created_at.desc())
            ).fetchall()
            keep_rows = sample_rows[: settings.max_speaker_samples_per_profile]
            drop_rows = sample_rows[settings.max_speaker_samples_per_profile :]
            if drop_rows:
                conn.execute(
                    delete(speaker_profile_samples_table)
                    .where(speaker_profile_samples_table.c.id.in_([row._mapping["id"] for row in drop_rows]))
                )
            centroid = _centroid_from_sample_rows(keep_rows)
            conn.execute(
                update(speaker_profiles_table)
                .where((speaker_profiles_table.c.id == target_profile_id) & (speaker_profiles_table.c.user_id == user_id))
                .values(
                    display_name=clean_name,
                    centroid_json=json.dumps(centroid, ensure_ascii=False),
                    sample_count=len(keep_rows),
                    active=True,
                    updated_at=timestamp,
                )
            )
            return conn.execute(
                select(speaker_profiles_table)
                .where((speaker_profiles_table.c.id == target_profile_id) & (speaker_profiles_table.c.user_id == user_id))
            ).fetchone()

    row = _db_retry(operation)
    data = _speaker_profile_from_row(row)
    return SpeakerProfile(**{key: data[key] for key in ["id", "display_name", "sample_count", "active", "created_at", "updated_at"]})


def update_task_status(
    task_id: str,
    status: MeetingTaskStatus,
    error_message: str | None = None,
    progress_percent: float | None = None,
    progress_label: str | None = None,
    progress_stage: str | None = None,
) -> MeetingTask:
    updated_at = now_iso()
    values: dict[str, Any] = {
        "status": status.value,
        "error_message": error_message,
        "updated_at": updated_at,
    }
    if progress_percent is not None:
        values["progress_percent"] = max(0.0, min(float(progress_percent), 100.0))
    if progress_label is not None:
        values["progress_label"] = progress_label
    if progress_stage is not None:
        values["progress_stage"] = progress_stage
    def operation():
        with connect() as conn:
            conn.execute(
                update(tasks_table)
                .where(tasks_table.c.id == task_id)
                .values(**values)
            )
            return conn.execute(select(tasks_table).where(tasks_table.c.id == task_id)).fetchone()

    row = _db_retry(operation)
    if row is None:
        raise HTTPException(status_code=404, detail="任务不存在")
    return _task_from_row(row)


def update_task_for_user(
    task_id: str,
    user_id: str,
    title: str | None = None,
    confirmed: bool | None = None,
    created_at_millis: int | None = None,
    is_private: bool | None = None,
    knowledge_scope: KnowledgeScope | None = None,
) -> MeetingTask:
    values: dict[str, Any] = {"updated_at": now_iso()}
    clean_title = (title or "").strip()
    if title is not None:
        if not 2 <= len(clean_title) <= 80:
            raise HTTPException(status_code=422, detail="会议标题需为 2-80 个字符")
        values["title"] = clean_title
    if confirmed is not None:
        values["confirmed"] = confirmed
    if created_at_millis is not None:
        values["created_at_millis"] = created_at_millis
    if is_private is not None:
        values["is_private"] = is_private
        if is_private and knowledge_scope is None:
            values["knowledge_scope"] = KnowledgeScope.excluded.value
    if knowledge_scope is not None:
        values["knowledge_scope"] = knowledge_scope.value
    if len(values) == 1:
        return get_task_for_user(task_id, user_id)

    def operation():
        with connect() as conn:
            conn.execute(
                update(tasks_table)
                .where((tasks_table.c.id == task_id) & (tasks_table.c.user_id == user_id))
                .values(**values)
            )
            return conn.execute(
                select(tasks_table).where((tasks_table.c.id == task_id) & (tasks_table.c.user_id == user_id))
            ).fetchone()

    row = _db_retry(operation)
    if row is None:
        raise HTTPException(status_code=404, detail="任务不存在")
    return _task_from_row(row)


def update_task_progress(
    task_id: str,
    progress_percent: float,
    progress_label: str,
    progress_stage: str,
) -> MeetingTask:
    updated_at = now_iso()
    def operation():
        with connect() as conn:
            conn.execute(
                update(tasks_table)
                .where(tasks_table.c.id == task_id)
                .values(
                    progress_percent=max(0.0, min(float(progress_percent), 100.0)),
                    progress_label=progress_label,
                    progress_stage=progress_stage,
                    updated_at=updated_at,
                )
            )
            return conn.execute(select(tasks_table).where(tasks_table.c.id == task_id)).fetchone()

    row = _db_retry(operation)
    if row is None:
        raise HTTPException(status_code=404, detail="任务不存在")
    return _task_from_row(row)


def save_result(result: MeetingProcessingResult) -> MeetingProcessingResult:
    values = {
        "task_id": result.task_id,
        "source_file_path": result.source_file_path,
        "participants": result.participants,
        "tags_json": json.dumps(result.tags, ensure_ascii=False),
        "summary": result.summary,
        "topics_json": json.dumps([item.model_dump() for item in result.topics], ensure_ascii=False),
        "decisions_json": json.dumps(result.decisions, ensure_ascii=False),
        "todos_json": json.dumps([item.model_dump() for item in result.todos], ensure_ascii=False),
        "risks_json": json.dumps([item.model_dump() for item in result.risks], ensure_ascii=False),
        "transcripts_json": json.dumps([item.model_dump() for item in result.transcripts], ensure_ascii=False),
        "generated_at": result.generated_at,
    }
    def operation():
        with connect() as conn:
            dialect = conn.dialect.name
            if dialect == "postgresql":
                stmt = pg_insert(results_table).values(**values)
                stmt = stmt.on_conflict_do_update(
                    index_elements=[results_table.c.task_id],
                    set_=values,
                )
            elif dialect in {"mysql", "mariadb"}:
                stmt = mysql_insert(results_table).values(**values)
                stmt = stmt.on_duplicate_key_update(**values)
            else:
                conn.execute(delete(results_table).where(results_table.c.task_id == result.task_id))
                stmt = insert(results_table).values(**values)
            conn.execute(stmt)

    _db_retry(operation)
    return result


def replace_knowledge_chunks(
    task_id: str,
    title: str,
    chunks: list[dict[str, Any]],
) -> None:
    timestamp = now_iso()
    rows_to_insert = [
        {
            "id": item.get("id") or str(uuid.uuid4()),
            "task_id": task_id,
            "chunk_type": item["chunk_type"],
            "title": title,
            "text": item["text"],
            "speaker": item.get("speaker"),
            "timestamp": item.get("timestamp"),
            "start_ms": item.get("start_ms"),
            "end_ms": item.get("end_ms"),
            "knowledge_scope": KnowledgeScope.cloud.value,
            "is_private": False,
            "embedding_json": json.dumps(item["embedding"], ensure_ascii=False),
            "created_at": timestamp,
        }
        for item in chunks
    ]

    def operation():
        with connect() as conn:
            task_row = conn.execute(select(tasks_table).where(tasks_table.c.id == task_id)).fetchone()
            if task_row is None:
                raise HTTPException(status_code=404, detail="任务不存在")
            task = _task_from_row(task_row)
            user_id = task_row._mapping["user_id"]
            conn.execute(delete(knowledge_chunks_table).where(knowledge_chunks_table.c.task_id == task_id))
            if task.knowledge_scope != KnowledgeScope.cloud or task.is_private:
                return
            if not rows_to_insert:
                return
            conn.execute(
                insert(knowledge_chunks_table),
                [{**item, "user_id": user_id} for item in rows_to_insert],
            )

    _db_retry(operation)


def list_knowledge_chunks() -> list[dict[str, Any]]:
    def operation():
        with connect() as conn:
            return conn.execute(
                select(*_knowledge_chunk_query_columns(), tasks_table.c.created_at.label("task_created_at"))
                .select_from(knowledge_chunks_table.outerjoin(tasks_table, knowledge_chunks_table.c.task_id == tasks_table.c.id))
            ).fetchall()

    rows = _db_retry(operation)
    items: list[dict[str, Any]] = []
    for row in rows:
        data = _mapping(row)
        data["embedding"] = _json_vector(data.pop("embedding_json", "[]"))
        items.append(data)
    return items


def list_knowledge_chunks_for_task(task_id: str, include_embeddings: bool = True) -> list[dict[str, Any]]:
    def operation():
        with connect() as conn:
            return conn.execute(
                select(*_knowledge_chunk_query_columns(include_embeddings), tasks_table.c.created_at.label("task_created_at"))
                .select_from(knowledge_chunks_table.outerjoin(tasks_table, knowledge_chunks_table.c.task_id == tasks_table.c.id))
                .where(knowledge_chunks_table.c.task_id == task_id)
                .order_by(knowledge_chunks_table.c.created_at.asc())
            ).fetchall()

    rows = _db_retry(operation)
    items: list[dict[str, Any]] = []
    for row in rows:
        data = _mapping(row)
        if include_embeddings:
            data["embedding"] = _json_vector(data.pop("embedding_json", "[]"))
        else:
            data["embedding"] = []
        items.append(data)
    return items


def list_knowledge_chunks_for_user(user_id: str, include_embeddings: bool = True) -> list[dict[str, Any]]:
    ensure_user_exists(user_id)
    def operation():
        with connect() as conn:
            return conn.execute(
                select(*_knowledge_chunk_query_columns(include_embeddings), tasks_table.c.created_at.label("task_created_at"))
                .select_from(knowledge_chunks_table.outerjoin(tasks_table, knowledge_chunks_table.c.task_id == tasks_table.c.id))
                .where(
                    (knowledge_chunks_table.c.user_id == user_id)
                    & (knowledge_chunks_table.c.knowledge_scope == KnowledgeScope.cloud.value)
                    & (knowledge_chunks_table.c.is_private == False)
                )
            ).fetchall()

    rows = _db_retry(operation)
    items: list[dict[str, Any]] = []
    for row in rows:
        data = _mapping(row)
        if include_embeddings:
            data["embedding"] = _json_vector(data.pop("embedding_json", "[]"))
        else:
            data["embedding"] = []
        items.append(data)
    return items


def _knowledge_chunk_query_columns(include_embeddings: bool = True):
    columns = [
        knowledge_chunks_table.c.id,
        knowledge_chunks_table.c.user_id,
        knowledge_chunks_table.c.task_id,
        knowledge_chunks_table.c.chunk_type,
        knowledge_chunks_table.c.title,
        knowledge_chunks_table.c.text,
        knowledge_chunks_table.c.speaker,
        knowledge_chunks_table.c.timestamp,
        knowledge_chunks_table.c.start_ms,
        knowledge_chunks_table.c.end_ms,
        knowledge_chunks_table.c.knowledge_scope,
        knowledge_chunks_table.c.is_private,
        knowledge_chunks_table.c.created_at,
    ]
    if include_embeddings:
        columns.insert(-1, knowledge_chunks_table.c.embedding_json)
    return columns


def clear_knowledge_chunks() -> int:
    def operation():
        with connect() as conn:
            count = conn.execute(select(func.count()).select_from(knowledge_chunks_table)).scalar_one()
            conn.execute(delete(knowledge_chunks_table))
            return int(count or 0)

    return _db_retry(operation)


def list_finished_results_for_reindex() -> list[tuple[MeetingTask, MeetingProcessingResult]]:
    items: list[tuple[MeetingTask, MeetingProcessingResult]] = []
    for task in sorted(list_tasks(), key=lambda item: item.created_at):
        if task.status != MeetingTaskStatus.finished:
            continue
        result = get_result(task.id)
        if result is not None:
            items.append((task, result))
    return items


def task_user_id(task_id: str) -> str:
    def operation():
        with connect() as conn:
            return conn.execute(select(tasks_table.c.user_id).where(tasks_table.c.id == task_id)).fetchone()

    row = _db_retry(operation)
    if row is None:
        raise HTTPException(status_code=404, detail="任务不存在")
    return row._mapping["user_id"]


def delete_knowledge_for_task(task_id: str) -> None:
    def operation():
        with connect() as conn:
            conn.execute(delete(knowledge_chunks_table).where(knowledge_chunks_table.c.task_id == task_id))

    _db_retry(operation)


def delete_task_tree(task_id: str, delete_file: bool = True) -> None:
    def operation():
        with connect() as conn:
            task_row = conn.execute(select(tasks_table).where(tasks_table.c.id == task_id)).fetchone()
            if task_row is None:
                raise HTTPException(status_code=404, detail="任务不存在")
            task = _task_from_row(task_row)
            file_row = conn.execute(select(files_table).where(files_table.c.id == task.file_id)).fetchone()
            if file_row is None:
                raise HTTPException(status_code=404, detail="文件不存在")
            file = _file_from_row(file_row)
            conn.execute(delete(knowledge_chunks_table).where(knowledge_chunks_table.c.task_id == task_id))
            conn.execute(delete(results_table).where(results_table.c.task_id == task_id))
            conn.execute(delete(tasks_table).where(tasks_table.c.id == task_id))
            conn.execute(delete(files_table).where(files_table.c.id == task.file_id))
            return file

    file = _db_retry(operation)
    if delete_file:
        try:
            from pathlib import Path

            Path(file.stored_path).unlink(missing_ok=True)
        except Exception:
            pass


def delete_user_task_tree(task_id: str, user_id: str, delete_file: bool = True) -> None:
    def operation():
        with connect() as conn:
            _ensure_user_exists_in_conn(conn, user_id)
            task_row = conn.execute(
                select(tasks_table).where((tasks_table.c.id == task_id) & (tasks_table.c.user_id == user_id))
            ).fetchone()
            if task_row is None:
                raise HTTPException(status_code=404, detail="任务不存在或无权访问")
            task = _task_from_row(task_row)
            file_row = conn.execute(
                select(files_table).where((files_table.c.id == task.file_id) & (files_table.c.user_id == user_id))
            ).fetchone()
            if file_row is None:
                raise HTTPException(status_code=404, detail="文件不存在或无权访问")
            file = _file_from_row(file_row)
            conn.execute(delete(knowledge_chunks_table).where(knowledge_chunks_table.c.task_id == task_id))
            conn.execute(delete(meeting_speaker_embeddings_table).where(meeting_speaker_embeddings_table.c.task_id == task_id))
            conn.execute(delete(results_table).where(results_table.c.task_id == task_id))
            conn.execute(delete(tasks_table).where((tasks_table.c.id == task_id) & (tasks_table.c.user_id == user_id)))
            conn.execute(delete(files_table).where((files_table.c.id == task.file_id) & (files_table.c.user_id == user_id)))
            return file

    file = _db_retry(operation)
    if delete_file:
        try:
            from pathlib import Path

            Path(file.stored_path).unlink(missing_ok=True)
        except Exception:
            pass


def get_result(task_id: str) -> MeetingProcessingResult | None:
    def operation():
        with connect() as conn:
            return conn.execute(select(results_table).where(results_table.c.task_id == task_id)).fetchone()

    row = _db_retry(operation)
    if row is None:
        return None
    return _result_from_row(row)


def _result_from_row(row) -> MeetingProcessingResult:
    return _result_from_mapping(_mapping(row))


def _result_from_mapping(data: dict[str, Any]) -> MeetingProcessingResult:
    return MeetingProcessingResult(
        task_id=data["task_id"],
        source_file_path=data["source_file_path"],
        participants=data.get("participants"),
        tags=json.loads(data.get("tags_json") or "[]"),
        summary=data["summary"],
        topics=[TopicItem(**item) for item in json.loads(data.get("topics_json") or "[]")],
        decisions=json.loads(data["decisions_json"]),
        todos=[TodoItem(**item) for item in json.loads(data["todos_json"])],
        risks=[RiskItem(**item) for item in json.loads(data.get("risks_json") or "[]")],
        transcripts=[TranscriptSegment(**item) for item in json.loads(data["transcripts_json"])],
        generated_at=data["generated_at"],
    )


def export_result_text(task_id: str, format: str = "markdown", include_transcript: bool = False) -> str:
    task = get_task(task_id)
    result = get_result(task_id)
    if result is None:
        raise HTTPException(status_code=404, detail="任务结果不存在")
    if format == "txt":
        return _export_txt(task, result, include_transcript=include_transcript)
    return _export_markdown(task, result, include_transcript=include_transcript)


def _export_markdown(task: MeetingTask, result: MeetingProcessingResult, include_transcript: bool = False) -> str:
    lines = [
        f"# {task.title}",
        "",
        f"- 任务 ID：{task.id}",
        f"- 生成时间：{result.generated_at}",
        f"- 参会人：{result.participants or '未填写'}",
        *([f"- 标签：{'、'.join(result.tags)}"] if result.tags else []),
        "",
        "## 摘要",
        "",
        result.summary,
        "",
        "## 议题",
        "",
        *[f"- {item.title}{f'：{item.summary}' if item.summary else ''}（来源：{item.source_timestamp or '无时间'} {item.source or '无来源'}）" for item in result.topics],
        "",
        "## 决策",
        "",
        *[f"- {item}" for item in result.decisions],
        "",
        "## 待办",
        "",
        *[f"- [{'x' if item.done else ' '}] {item.title}（负责人：{item.assignee or '待确认'}；截止：{item.due_at or '待确认'}；状态：{item.status}；来源：{item.source_timestamp or '无时间'} {item.source}）" for item in result.todos],
        "",
        "## 风险",
        "",
        *[f"- {item.title}{f'（{item.level}）' if item.level else ''}：{item.description or item.recommendation}（来源：{item.source_timestamp or '无时间'} {item.source or '无来源'}）" for item in result.risks],
    ]
    if include_transcript:
        lines.extend([
            "",
            "## 转写原文",
            "",
            *[f"- {item.timestamp} {item.speaker}：{item.text}" for item in result.transcripts],
            "",
        ])
    return "\n".join(lines)


def _export_txt(task: MeetingTask, result: MeetingProcessingResult, include_transcript: bool = False) -> str:
    lines = [
        task.title,
        f"生成时间：{result.generated_at}",
        f"参会人：{result.participants or '未填写'}",
        *([f"标签：{'、'.join(result.tags)}"] if result.tags else []),
        "",
        "摘要",
        result.summary,
        "",
        "议题",
        *[f"- {item.title}{f'：{item.summary}' if item.summary else ''} 来源：{item.source_timestamp or '无时间'} {item.source or '无来源'}" for item in result.topics],
        "",
        "决策",
        *[f"- {item}" for item in result.decisions],
        "",
        "待办",
        *[f"- {item.title} 负责人：{item.assignee or '待确认'} 截止：{item.due_at or '待确认'} 状态：{item.status} 来源：{item.source_timestamp or '无时间'} {item.source}" for item in result.todos],
        "",
        "风险",
        *[f"- {item.title}{f'（{item.level}）' if item.level else ''}：{item.description or item.recommendation} 来源：{item.source_timestamp or '无时间'} {item.source or '无来源'}" for item in result.risks],
    ]
    if include_transcript:
        lines.extend([
            "",
            "转写原文",
            *[f"{item.timestamp} {item.speaker}：{item.text}" for item in result.transcripts],
            "",
        ])
    return "\n".join(lines)


def clear_server_data() -> None:
    def operation():
        with connect() as conn:
            conn.execute(delete(knowledge_chunks_table))
            conn.execute(delete(meeting_speaker_embeddings_table))
            conn.execute(delete(speaker_profile_samples_table))
            conn.execute(delete(speaker_profiles_table))
            conn.execute(delete(results_table))
            conn.execute(delete(tasks_table))
            conn.execute(delete(files_table))
            conn.execute(delete(scheduled_meetings_table))

    _db_retry(operation)
    shutil.rmtree(settings.upload_dir, ignore_errors=True)
    settings.upload_dir.mkdir(parents=True, exist_ok=True)


def clear_user_data(user_id: str) -> None:
    ensure_user_exists(user_id)
    def operation():
        with connect() as conn:
            task_rows = conn.execute(select(tasks_table.c.id, tasks_table.c.file_id).where(tasks_table.c.user_id == user_id)).fetchall()
            task_ids = [row._mapping["id"] for row in task_rows]
            file_rows = conn.execute(select(files_table.c.stored_path).where(files_table.c.user_id == user_id)).fetchall()
            conn.execute(delete(knowledge_chunks_table).where(knowledge_chunks_table.c.user_id == user_id))
            conn.execute(delete(meeting_speaker_embeddings_table).where(meeting_speaker_embeddings_table.c.user_id == user_id))
            conn.execute(delete(speaker_profile_samples_table).where(speaker_profile_samples_table.c.user_id == user_id))
            conn.execute(delete(speaker_profiles_table).where(speaker_profiles_table.c.user_id == user_id))
            if task_ids:
                conn.execute(delete(results_table).where(results_table.c.task_id.in_(task_ids)))
                conn.execute(delete(tasks_table).where(tasks_table.c.user_id == user_id))
            conn.execute(delete(files_table).where(files_table.c.user_id == user_id))
            conn.execute(delete(scheduled_meetings_table).where(scheduled_meetings_table.c.user_id == user_id))
            return file_rows

    file_rows = _db_retry(operation)
    for row in file_rows:
        try:
            from pathlib import Path

            Path(row._mapping["stored_path"]).unlink(missing_ok=True)
        except Exception:
            pass


def _mapping(row) -> dict[str, Any]:
    return dict(row._mapping)


def _json_vector(value: str | None) -> list[float]:
    try:
        data = json.loads(value or "[]")
    except json.JSONDecodeError:
        return []
    if not isinstance(data, list):
        return []
    vector: list[float] = []
    for item in data:
        try:
            vector.append(float(item))
        except (TypeError, ValueError):
            continue
    return vector


def _normalized_vector(values: list[float] | tuple[float, ...] | None) -> list[float]:
    if not values:
        return []
    vector = [float(value) for value in values if value is not None]
    norm = sum(value * value for value in vector) ** 0.5
    if norm <= 1e-6:
        return []
    return [round(value / norm, 6) for value in vector]


def _centroid_from_sample_rows(rows) -> list[float]:
    vectors: list[list[float]] = []
    weights: list[float] = []
    for row in rows:
        data = _mapping(row)
        vector = _normalized_vector(json.loads(data["embedding_json"]))
        if not vector:
            continue
        vectors.append(vector)
        weights.append(max(float(data.get("quality") or 0.0), 0.05))
    if not vectors:
        return []
    dimensions = len(vectors[0])
    sums = [0.0] * dimensions
    total_weight = 0.0
    for vector, weight in zip(vectors, weights, strict=False):
        if len(vector) != dimensions:
            continue
        total_weight += weight
        for index, value in enumerate(vector):
            sums[index] += value * weight
    if total_weight <= 1e-6:
        return []
    return _normalized_vector([value / total_weight for value in sums])


def _speaker_profile_from_row(row) -> dict[str, Any]:
    data = _mapping(row)
    data["centroid"] = _normalized_vector(json.loads(data.get("centroid_json") or "[]"))
    data.pop("centroid_json", None)
    data["sample_count"] = int(data.get("sample_count") or 0)
    data["active"] = bool(data.get("active"))
    return data


def _speaker_embedding_from_row(row) -> dict[str, Any]:
    data = _mapping(row)
    data["embedding"] = _normalized_vector(json.loads(data.get("embedding_json") or "[]"))
    data.pop("embedding_json", None)
    data["quality"] = float(data.get("quality") or 0.0)
    data["segment_count"] = int(data.get("segment_count") or 0)
    return data


def _file_from_row(row) -> FileRecord:
    data = _mapping(row)
    return FileRecord(
        id=data["id"],
        original_name=data["original_name"],
        stored_path=data["stored_path"],
        content_type=data["content_type"],
        size_bytes=data["size_bytes"],
        created_at=data["created_at"],
    )


def _task_from_row(row) -> MeetingTask:
    return _task_from_mapping(_mapping(row))


def _task_from_mapping(data: dict[str, Any]) -> MeetingTask:
    status = MeetingTaskStatus(data["status"])
    progress_percent = float(data.get("progress_percent") or 0.0)
    progress_label = data.get("progress_label")
    progress_stage = data.get("progress_stage")
    if status == MeetingTaskStatus.finished and progress_percent < 100:
        progress_percent = 100.0
        progress_label = progress_label or "处理完成"
        progress_stage = progress_stage or "finished"
    elif status == MeetingTaskStatus.failed and not progress_label:
        progress_label = "处理失败"
        progress_stage = progress_stage or "failed"
    return MeetingTask(
        id=data["id"],
        file_id=data["file_id"],
        client_task_id=data.get("client_task_id"),
        title=data["title"],
        source=MeetingTaskSource(data["source"]),
        status=status,
        error_message=data["error_message"],
        progress_percent=progress_percent,
        progress_label=progress_label,
        progress_stage=progress_stage,
        sync_scope=TaskSyncScope(data.get("sync_scope") or TaskSyncScope.cloud.value),
        knowledge_scope=KnowledgeScope(data.get("knowledge_scope") or KnowledgeScope.cloud.value),
        is_private=bool(data.get("is_private") or False),
        device_id=data.get("device_id"),
        confirmed=bool(data.get("confirmed") or False),
        created_at_millis=data.get("created_at_millis"),
        created_at=data["created_at"],
        updated_at=data["updated_at"],
    )


def _schedule_from_row(row) -> dict[str, Any]:
    data = _mapping(row)
    return {
        "id": data["id"],
        "time": data["time"],
        "title": data["title"],
        "participants": data["participants"],
        "note": data.get("note") or "",
        "duration_label": data["duration_label"],
        "reminder_label": data["reminder_label"],
        "start_at_millis": data.get("start_at_millis"),
        "end_at_millis": data.get("end_at_millis"),
        "created_at_millis": data["created_at_millis"],
        "status": data["status"],
        "calendar_event_id": data.get("calendar_event_id"),
    }
