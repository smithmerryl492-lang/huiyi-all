from fastapi import APIRouter

from app.api.v1.routes import admin, auth, data, files, health, knowledge, live, membership, payments, sync, tasks, voiceprints


api_router = APIRouter()
api_router.include_router(health.router, tags=["health"])
api_router.include_router(admin.router, prefix="/admin", tags=["admin"])
api_router.include_router(auth.router, prefix="/auth", tags=["auth"])
api_router.include_router(files.router, prefix="/files", tags=["files"])
api_router.include_router(tasks.router, prefix="/tasks", tags=["tasks"])
api_router.include_router(knowledge.router, prefix="/knowledge", tags=["knowledge"])
api_router.include_router(live.router, prefix="/live", tags=["live"])
api_router.include_router(membership.router, prefix="/membership", tags=["membership"])
api_router.include_router(payments.router, prefix="/payments", tags=["payments"])
api_router.include_router(sync.router, prefix="/sync", tags=["sync"])
api_router.include_router(data.router, prefix="/data", tags=["data"])
api_router.include_router(voiceprints.router, prefix="/voiceprints", tags=["voiceprints"])
