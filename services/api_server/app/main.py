from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from app import admin_repositories
from app.api.v1.router import api_router
from app.core.config import settings
from app.db import init_db
from app.services.aliyun_realtime import start_realtime_session_warmer


def create_app() -> FastAPI:
    app = FastAPI(
        title="会晓 AI API Server",
        version="0.1.0",
        description="会晓 AI App 统一后端入口。当前已接入独立 ASR 服务和独立 AI 服务。",
    )

    @app.on_event("startup")
    def startup() -> None:
        init_db()
        admin_repositories.ensure_admin_defaults()
        start_realtime_session_warmer()

    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.include_router(api_router, prefix=settings.api_prefix)
    admin_static_dir = Path(__file__).resolve().parent / "admin_static"
    if admin_static_dir.exists():
        app.mount("/admin", StaticFiles(directory=admin_static_dir, html=True), name="admin")

    @app.get("/")
    def root() -> dict:
        return {
            "service": "huixiao-api-server",
            "status": "ok",
            "docs": "/docs",
            "api": settings.api_prefix,
        }

    return app


app = create_app()
