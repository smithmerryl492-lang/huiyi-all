from pathlib import Path

from fastapi import APIRouter, Depends, File, Form, UploadFile

from app import repositories
from app.schemas import (
    SpeakerProfile,
    SpeakerProfileDeleteResponse,
    SpeakerProfileEnrollRequest,
    SpeakerProfileUpdateRequest,
)
from app.services import voiceprints
from app.services.auth import require_current_user_id
from app.storage import save_upload_file


router = APIRouter()


@router.get("/profiles", response_model=list[SpeakerProfile])
def list_profiles(user_id: str = Depends(require_current_user_id)) -> list[SpeakerProfile]:
    return [
        SpeakerProfile(**{key: item[key] for key in ["id", "display_name", "sample_count", "active", "created_at", "updated_at"]})
        for item in repositories.list_speaker_profiles(user_id)
    ]


@router.post("/profiles/from-task", response_model=SpeakerProfile)
def enroll_profile_from_task(request: SpeakerProfileEnrollRequest, user_id: str = Depends(require_current_user_id)) -> SpeakerProfile:
    return voiceprints.enroll_profile_from_task_speaker(
        user_id=user_id,
        task_id=request.task_id,
        speaker_id=request.speaker_id,
        speaker_name=request.speaker_name,
        display_name=request.display_name,
        profile_id=request.profile_id,
    )


@router.post("/profiles/from-audio", response_model=SpeakerProfile)
async def enroll_profile_from_audio(
    display_name: str = Form(...),
    profile_id: str | None = Form(default=None),
    file: UploadFile = File(...),
    user_id: str = Depends(require_current_user_id),
) -> SpeakerProfile:
    stored_path, _ = await save_upload_file(file)
    try:
        return voiceprints.enroll_profile_from_audio_file(
            user_id=user_id,
            display_name=display_name,
            source_file_path=stored_path,
            profile_id=profile_id,
        )
    finally:
        Path(stored_path).unlink(missing_ok=True)


@router.patch("/profiles/{profile_id}", response_model=SpeakerProfile)
def update_profile(profile_id: str, request: SpeakerProfileUpdateRequest, user_id: str = Depends(require_current_user_id)) -> SpeakerProfile:
    return repositories.update_speaker_profile(user_id, profile_id, display_name=request.display_name, active=request.active)


@router.delete("/profiles/{profile_id}", response_model=SpeakerProfileDeleteResponse)
def delete_profile(profile_id: str, user_id: str = Depends(require_current_user_id)) -> SpeakerProfileDeleteResponse:
    repositories.delete_speaker_profile(user_id, profile_id)
    return SpeakerProfileDeleteResponse(message="声纹档案已删除")
