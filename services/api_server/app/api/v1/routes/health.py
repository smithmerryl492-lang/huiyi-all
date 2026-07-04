from fastapi import APIRouter

from app.core.config import settings
from app.db import engine


router = APIRouter()


@router.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "service": "api_server",
        "data_dir": str(settings.data_dir),
        "database": engine.dialect.name,
        "asr_service_url": settings.asr_service_url,
        "asr_live_ws_url": settings.asr_live_ws_url,
        "ai_service_url": settings.ai_service_url,
        "model_connected": None,
        "dependency_checks": "not_run",
    }
