import uuid
from pathlib import Path

from fastapi import HTTPException, UploadFile

from app.core.config import settings


SUPPORTED_UPLOAD_SUFFIXES = {"mp3", "m4a", "wav", "aac", "mp4", "mov"}


def safe_upload_suffix(name: str | None, content_type: str | None) -> str:
    suffix = Path(name or "").suffix.lower().lstrip(".")
    suffix = "".join(char for char in suffix if char.isascii() and char.isalnum())
    if suffix:
        return suffix
    return {
        "audio/wav": "wav",
        "audio/x-wav": "wav",
        "audio/mpeg": "mp3",
        "audio/mp4": "m4a",
        "audio/aac": "aac",
        "audio/x-aac": "aac",
        "video/mp4": "mp4",
        "video/quicktime": "mov",
    }.get(content_type or "", "bin")


async def save_upload_file(upload: UploadFile) -> tuple[str, int]:
    suffix = safe_upload_suffix(upload.filename, upload.content_type)
    if suffix not in SUPPORTED_UPLOAD_SUFFIXES:
        raise HTTPException(status_code=400, detail="不支持的文件格式，请上传 mp3、m4a、wav、aac、mp4 或 mov")
    target = settings.upload_dir / f"{uuid.uuid4().hex}.{suffix}"
    size = 0
    max_size = settings.max_upload_mb * 1024 * 1024

    with target.open("wb") as output:
        while True:
            chunk = await upload.read(1024 * 1024)
            if not chunk:
                break
            size += len(chunk)
            if size > max_size:
                target.unlink(missing_ok=True)
                raise HTTPException(status_code=413, detail="文件超过服务器上传限制")
            output.write(chunk)

    if size == 0:
        target.unlink(missing_ok=True)
        raise HTTPException(status_code=400, detail="上传文件为空")

    return str(Path(target).resolve()), size
