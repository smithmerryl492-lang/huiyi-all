from __future__ import annotations

import os
from pathlib import Path


def load_services_env() -> None:
    services_env = Path(__file__).resolve().parents[2] / ".env"
    if not services_env.exists():
        return
    for line in services_env.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        key = key.strip()
        if key not in os.environ:
            os.environ[key] = value.strip().strip('"').strip("'")


load_services_env()

if not os.getenv("HUIXIAO_DATABASE_URL", "").strip():
    raise SystemExit("HUIXIAO_DATABASE_URL is required and must point to the shared remote database.")

from app import repositories
from app.services.knowledge import index_meeting_result


def main() -> None:
    if os.getenv("HUIXIAO_REBUILD_KNOWLEDGE_INDEX", "").lower() not in {"1", "true", "yes"}:
        raise SystemExit("Refusing to rebuild. Set HUIXIAO_REBUILD_KNOWLEDGE_INDEX=true to confirm.")

    items = repositories.list_finished_results_for_reindex()
    deleted = repositories.clear_knowledge_chunks()
    rebuilt = 0
    for task, result in items:
        index_meeting_result(task.title, result)
        rebuilt += 1
        print(f"indexed {rebuilt}/{len(items)} {task.id} {task.title}")
    print(f"deleted_chunks={deleted} rebuilt_meetings={rebuilt}")


if __name__ == "__main__":
    main()
