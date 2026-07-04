from fastapi import APIRouter, HTTPException

from app.core.config import settings
from app.repositories import clear_server_data
from app.schemas import MessageResponse


router = APIRouter()


@router.delete("/local", response_model=MessageResponse)
def clear_local_data() -> MessageResponse:
    if not settings.allow_server_data_clear:
        raise HTTPException(status_code=403, detail="服务端清库接口未启用")
    clear_server_data()
    return MessageResponse(message="服务端本地任务、结果和临时文件已清理")

