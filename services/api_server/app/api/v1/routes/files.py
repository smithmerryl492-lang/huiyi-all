from pathlib import Path

from fastapi import APIRouter, Depends, File, UploadFile

from app import membership_repositories, repositories
from app.schemas import KnowledgeScope, MeetingTaskSource, TaskSyncScope, UploadResponse
from app.services import task_runtime
from app.services.auth import require_current_user_id
from app.storage import save_upload_file


router = APIRouter()


@router.post("/upload", response_model=UploadResponse)
async def upload_file(
    file: UploadFile = File(...),
    source: MeetingTaskSource = MeetingTaskSource.import_file,
    user_id: str = Depends(require_current_user_id),
    persist_to_cloud: bool = True,
    is_private: bool = False,
    device_id: str | None = None,
    client_task_id: str | None = None,
    confirmed: bool = False,
    created_at_millis: int | None = None,
) -> UploadResponse:
    if not persist_to_cloud and source in {MeetingTaskSource.import_file, MeetingTaskSource.recording}:
        membership_repositories.require_transcription_quota(user_id)
    stored_path, size = await save_upload_file(file)
    try:
        if persist_to_cloud and client_task_id:
            existing = repositories.get_cloud_task_item_by_client_id(user_id, client_task_id)
            if existing is not None:
                Path(stored_path).unlink(missing_ok=True)
                task = repositories.update_task_for_user(
                    existing["task"].id,
                    user_id,
                    confirmed=confirmed,
                    created_at_millis=created_at_millis,
                    is_private=is_private,
                    knowledge_scope=KnowledgeScope.excluded if is_private else KnowledgeScope.cloud,
                )
                return UploadResponse(file=existing["file"], task=task)
        record, task = repositories.create_file_and_task_with_options(
            original_name=file.filename or "upload",
            stored_path=stored_path,
            content_type=file.content_type or "application/octet-stream",
            size_bytes=size,
            source=source,
            user_id=user_id,
            sync_scope=TaskSyncScope.cloud if persist_to_cloud else TaskSyncScope.local_processing,
            knowledge_scope=KnowledgeScope.excluded if is_private or not persist_to_cloud else KnowledgeScope.cloud,
            is_private=is_private,
            device_id=device_id,
            client_task_id=client_task_id if persist_to_cloud else None,
            confirmed=confirmed,
            created_at_millis=created_at_millis,
        )
    except Exception:
        Path(stored_path).unlink(missing_ok=True)
        raise
    task_runtime.start(user_id, task, record)
    return UploadResponse(file=record, task=task)
