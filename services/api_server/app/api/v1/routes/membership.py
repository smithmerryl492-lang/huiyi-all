from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends

from app import membership_repositories
from app.services.auth import require_current_user_id_unchecked


router = APIRouter()


@router.get("/me")
def get_my_membership(user_id: str = Depends(require_current_user_id_unchecked)) -> dict[str, Any]:
    return membership_repositories.get_user_membership_profile(user_id)
