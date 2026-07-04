from fastapi import APIRouter, Depends

from app import membership_repositories
from app.schemas import KnowledgeAskRequest, KnowledgeAskResponse
from app.services.auth import require_current_user_id
from app.services.knowledge import answer_question


router = APIRouter()


@router.post("/ask", response_model=KnowledgeAskResponse)
def ask_knowledge(request: KnowledgeAskRequest, user_id: str = Depends(require_current_user_id)) -> KnowledgeAskResponse:
    membership_repositories.require_knowledge_quota(user_id)
    result = answer_question(
        request.question,
        request.limit,
        request.task_ids,
        request.context_task_ids,
        user_id,
        request.scope,
        request.local_sources,
        request.user_name,
        request.context_messages,
    )
    membership_repositories.consume_knowledge_qa(user_id, 1)
    return KnowledgeAskResponse(
        question=request.question,
        answer=result["answer"],
        sources=result["sources"],
        model=result.get("model"),
    )
