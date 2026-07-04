from __future__ import annotations

import os
from pathlib import Path
from datetime import UTC, datetime, timedelta


def require_database_url() -> None:
    if not os.getenv("HUIXIAO_DATABASE_URL", "").strip():
        services_env = Path(__file__).resolve().parents[2] / ".env"
        if services_env.exists():
            for line in services_env.read_text(encoding="utf-8").splitlines():
                stripped = line.strip()
                if not stripped or stripped.startswith("#") or "=" not in stripped:
                    continue
                key, value = stripped.split("=", 1)
                key = key.strip()
                if key not in os.environ:
                    os.environ[key] = value.strip().strip('"').strip("'")
    if not os.getenv("HUIXIAO_DATABASE_URL", "").strip():
        raise SystemExit("HUIXIAO_DATABASE_URL is required and must point to the shared remote database.")


require_database_url()

from sqlalchemy import update

from app import repositories
from app.db import connect, files_table, tasks_table
from app.schemas import (
    MeetingProcessingResult,
    MeetingTaskSource,
    MeetingTaskStatus,
    RiskItem,
    TodoItem,
    TopicItem,
    TranscriptSegment,
)
from app.services.knowledge import index_meeting_result


PREFIX = "QA知识库测试"


def main() -> None:
    cleanup()
    task_ids = [
        seed_meeting(
            title=f"{PREFIX}-项目A周会-今天",
            created_at=datetime(2026, 5, 23, 9, 10, tzinfo=UTC),
            summary="项目A本周完成灰度方案评审，仍存在支付回调稳定性和安卓后台录音风险。",
            transcripts=[
                TranscriptSegment(speaker="张三", text="我负责周三前补齐支付回调重试方案，并把异常订单监控接入看板。", timestamp="00:12", start_ms=12000, end_ms=21000),
                TranscriptSegment(speaker="李四", text="安卓后台录音在锁屏后偶发中断，我今天把复现日志整理给研发。", timestamp="00:38", start_ms=38000, end_ms=47000),
                TranscriptSegment(speaker="王五", text="本周先不上线语音声纹识别，等实时转写稳定后再排期。", timestamp="01:05", start_ms=65000, end_ms=74000),
            ],
            topics=[
                TopicItem(id="topic-a-1", title="灰度上线准备", summary="支付回调、异常订单监控和后台录音稳定性是上线前重点。", source="张三和李四发言", source_timestamp="00:12-00:47"),
            ],
            decisions=[
                "项目A本周只做灰度发布，不开放声纹识别能力。",
            ],
            todos=[
                TodoItem(id="todo-a-1", title="张三周三前补齐支付回调重试方案", source="张三发言", done=False, source_timestamp="00:12"),
                TodoItem(id="todo-a-2", title="李四今天整理安卓后台录音中断复现日志", source="李四发言", done=False, source_timestamp="00:38"),
            ],
            risks=[
                RiskItem(id="risk-a-1", title="支付回调稳定性不足", level="高", description="异常订单可能无法及时恢复。", recommendation="补齐重试方案和监控看板。", source="张三发言", source_timestamp="00:12"),
                RiskItem(id="risk-a-2", title="安卓后台录音中断", level="中", description="锁屏后偶发中断会影响实时转写完整性。", recommendation="先收集复现日志再修复。", source="李四发言", source_timestamp="00:38"),
            ],
        ),
        seed_meeting(
            title=f"{PREFIX}-客户B跟进-今天",
            created_at=datetime(2026, 5, 23, 14, 30, tzinfo=UTC),
            summary="客户B关注私有化部署、数据保留周期和钉钉分享审批。",
            transcripts=[
                TranscriptSegment(speaker="客户B", text="我们最关心私有化部署，尤其是音频文件能不能只保留在客户侧。", timestamp="00:08", start_ms=8000, end_ms=18000),
                TranscriptSegment(speaker="赵敏", text="我明天下午前给客户B一版私有化部署报价和数据保留方案。", timestamp="00:42", start_ms=42000, end_ms=52000),
                TranscriptSegment(speaker="客户B", text="钉钉分享需要审批流，不能任何人都能外发会议纪要。", timestamp="01:10", start_ms=70000, end_ms=82000),
            ],
            topics=[
                TopicItem(id="topic-b-1", title="客户B私有化部署", summary="客户希望音频尽量保留在客户侧，并明确数据保留周期。", source="客户B发言", source_timestamp="00:08"),
            ],
            decisions=[
                "先给客户B输出私有化部署报价和数据保留方案，再讨论钉钉审批流。",
            ],
            todos=[
                TodoItem(id="todo-b-1", title="赵敏明天下午前给客户B私有化部署报价", source="赵敏发言", done=False, source_timestamp="00:42"),
            ],
            risks=[
                RiskItem(id="risk-b-1", title="客户B外发审批要求未确认", level="中", description="钉钉分享若没有审批流，可能无法进入客户试点。", recommendation="补充分享审批方案。", source="客户B发言", source_timestamp="01:10"),
            ],
        ),
        seed_meeting(
            title=f"{PREFIX}-项目A周会-上周",
            created_at=datetime(2026, 5, 16, 10, 0, tzinfo=UTC),
            summary="项目A上周决定延期上线，原因是支付回调和后台录音恢复能力未达标。",
            transcripts=[
                TranscriptSegment(speaker="王五", text="我建议延期上线一周，支付回调失败和后台录音恢复都还没达到验收标准。", timestamp="00:20", start_ms=20000, end_ms=33000),
                TranscriptSegment(speaker="张三", text="同意延期，我负责把支付回调失败率压到千分之一以内。", timestamp="00:45", start_ms=45000, end_ms=55000),
            ],
            topics=[
                TopicItem(id="topic-c-1", title="延期上线", summary="支付回调和后台录音恢复能力未达标。", source="王五发言", source_timestamp="00:20"),
            ],
            decisions=[
                "项目A延期上线一周，先修复支付回调失败和后台录音恢复问题。",
            ],
            todos=[
                TodoItem(id="todo-c-1", title="张三负责把支付回调失败率压到千分之一以内", source="张三发言", done=False, source_timestamp="00:45"),
            ],
            risks=[
                RiskItem(id="risk-c-1", title="延期上线", level="高", description="核心稳定性未达标导致上线延期。", recommendation="优先修复支付回调和录音恢复。", source="王五发言", source_timestamp="00:20"),
            ],
        ),
    ]
    print("\n".join(task_ids))


def cleanup() -> None:
    for task in repositories.list_tasks():
        if task.title.startswith(PREFIX):
            repositories.delete_task_tree(task.id, delete_file=False)


def seed_meeting(
    title: str,
    created_at: datetime,
    summary: str,
    transcripts: list[TranscriptSegment],
    topics: list[TopicItem],
    decisions: list[str],
    todos: list[TodoItem],
    risks: list[RiskItem],
) -> str:
    file_record = repositories.create_file_record(
        original_name=f"{title}.wav",
        stored_path=f"seed://{title}.wav",
        content_type="audio/wav",
        size_bytes=1,
    )
    task = repositories.create_task(file_record, MeetingTaskSource.import_file)
    with connect() as conn:
        conn.execute(
            update(tasks_table)
            .where(tasks_table.c.id == task.id)
            .values(title=title, status=MeetingTaskStatus.finished.value, created_at=created_at.isoformat(), updated_at=created_at.isoformat())
        )
        conn.execute(update(files_table).where(files_table.c.id == file_record.id).values(created_at=created_at.isoformat()))

    result = MeetingProcessingResult(
        task_id=task.id,
        source_file_path=file_record.stored_path,
        summary=summary,
        topics=topics,
        decisions=decisions,
        todos=todos,
        risks=risks,
        transcripts=transcripts,
        generated_at=created_at.isoformat(),
    )
    repositories.save_result(result)
    index_meeting_result(title, result)
    return task.id


if __name__ == "__main__":
    main()
