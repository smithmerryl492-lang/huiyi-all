from fastapi import APIRouter, Depends, HTTPException

from app import repositories
from app.schemas import CloudBootstrapResponse, ScheduledMeetingCloud, TaskSyncScope
from app.services.auth import require_current_user_id
from app.services.knowledge import clear_knowledge_cache


router = APIRouter()


@router.get("/{user_id}/bootstrap", response_model=CloudBootstrapResponse)
def legacy_bootstrap(user_id: str, current_user_id: str = Depends(require_current_user_id)) -> CloudBootstrapResponse:
    _ensure_same_user(user_id, current_user_id)
    return bootstrap(current_user_id)


@router.get("/bootstrap", response_model=CloudBootstrapResponse)
def bootstrap(user_id: str = Depends(require_current_user_id)) -> CloudBootstrapResponse:
    repositories.cleanup_stale_local_processing_tasks(user_id)
    repositories.mark_stale_processing_tasks_failed(user_id)
    return CloudBootstrapResponse(
        user_id=user_id,
        tasks=repositories.list_user_task_items(user_id, sync_scope=TaskSyncScope.cloud, require_result=True),
        schedules=repositories.list_scheduled_meetings(user_id),
    )


@router.put("/{user_id}/schedules/{schedule_id}", response_model=ScheduledMeetingCloud)
def legacy_upsert_schedule(user_id: str, schedule_id: str, request: ScheduledMeetingCloud, current_user_id: str = Depends(require_current_user_id)) -> ScheduledMeetingCloud:
    _ensure_same_user(user_id, current_user_id)
    return upsert_schedule(schedule_id, request, current_user_id)


@router.put("/schedules/{schedule_id}", response_model=ScheduledMeetingCloud)
def upsert_schedule(schedule_id: str, request: ScheduledMeetingCloud, user_id: str = Depends(require_current_user_id)) -> ScheduledMeetingCloud:
    payload = request.model_dump()
    payload["id"] = schedule_id
    return ScheduledMeetingCloud(**repositories.upsert_scheduled_meeting(user_id, payload))


@router.delete("/{user_id}/schedules/{schedule_id}")
def legacy_delete_schedule(user_id: str, schedule_id: str, current_user_id: str = Depends(require_current_user_id)) -> dict:
    _ensure_same_user(user_id, current_user_id)
    return delete_schedule(schedule_id, current_user_id)


@router.delete("/schedules/{schedule_id}")
def delete_schedule(schedule_id: str, user_id: str = Depends(require_current_user_id)) -> dict:
    repositories.delete_scheduled_meeting(user_id, schedule_id)
    return {"message": "预约会议已删除"}


@router.delete("/{user_id}/all")
def legacy_clear_user_cloud_data(user_id: str, current_user_id: str = Depends(require_current_user_id)) -> dict:
    _ensure_same_user(user_id, current_user_id)
    return clear_user_cloud_data(current_user_id)


@router.delete("/all")
def clear_user_cloud_data(user_id: str = Depends(require_current_user_id)) -> dict:
    repositories.clear_user_data(user_id)
    clear_knowledge_cache(user_id)
    return {"message": "当前用户云端会议、预约和索引已删除"}


def _ensure_same_user(path_user_id: str, current_user_id: str) -> None:
    if path_user_id != current_user_id:
        raise HTTPException(status_code=403, detail="无权访问其他用户数据")
