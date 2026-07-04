from __future__ import annotations

import sys
import unittest
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.schemas import KnowledgeScope, LocalKnowledgeSource
from app.services import knowledge


def _current_week_start() -> datetime:
    now = datetime.now(knowledge.CHINA_TZ)
    return (now - timedelta(days=now.weekday())).replace(hour=0, minute=0, second=0, microsecond=0)


def _source(chunk_id: str, text: str, chunk_type: str = "todo") -> LocalKnowledgeSource:
    return LocalKnowledgeSource(
        chunk_id=chunk_id,
        task_id=f"task-{chunk_id}",
        title=f"会议-{chunk_id}",
        text=text,
        chunk_type=chunk_type,
        meeting_date="本机",
        created_at=datetime.now(knowledge.CHINA_TZ).isoformat(),
        speaker="AI 待办" if chunk_type == "todo" else "AI 纪要",
        score=1.0,
    )


class KnowledgeSelfTodoTest(unittest.TestCase):
    def test_this_week_self_todos_keep_identity_and_due_range(self) -> None:
        week_start = _current_week_start()
        this_week = (week_start + timedelta(days=1)).strftime("%Y-%m-%d")
        last_week = (week_start - timedelta(days=1)).strftime("%Y-%m-%d")
        next_week = (week_start + timedelta(days=7)).strftime("%Y-%m-%d")
        sources = [
            _source("todo-self-this-week", f"本周当前用户待办。负责人：说话人1。截止时间：{this_week}。状态：待处理。依据：测试"),
            _source("todo-self-week-label", "本周范围标签待办。负责人：说话人1。截止时间：本周内。状态：待处理。依据：测试"),
            _source("todo-other-this-week", f"其他人本周待办。负责人：张三。截止时间：{this_week}。状态：待处理。依据：测试"),
            _source("todo-self-last-week", f"上周当前用户待办。负责人：说话人1。截止时间：{last_week}。状态：待处理。依据：测试"),
            _source("todo-self-next-week", f"下周当前用户待办。负责人：说话人1。截止时间：{next_week}。状态：待处理。依据：测试"),
            _source("todo-self-unconfirmed", "待确认当前用户待办。负责人：说话人1。截止时间：待确认。状态：待处理。依据：测试"),
            _source("summary-self-this-week", "非待办摘要里提到了说话人1和本周当前用户待办。", "summary"),
        ]

        result = knowledge.answer_question(
            "本周我有哪些待办？",
            limit=8,
            user_id="user-1",
            scope=KnowledgeScope.local,
            local_sources=sources,
            user_name="说话人1",
        )

        answer = result["answer"]
        self.assertIn("本周当前用户待办", answer)
        self.assertIn("本周范围标签待办", answer)
        self.assertNotIn("其他人本周待办", answer)
        self.assertNotIn("上周当前用户待办", answer)
        self.assertNotIn("下周当前用户待办", answer)
        self.assertNotIn("待确认当前用户待办", answer)
        self.assertNotIn("非待办摘要", answer)
        self.assertEqual({source.chunk_id for source in result["sources"]}, {"todo-self-this-week", "todo-self-week-label"})

    def test_self_todo_without_identity_does_not_expand_to_all_todos(self) -> None:
        week_start = _current_week_start()
        this_week = (week_start + timedelta(days=1)).strftime("%Y-%m-%d")
        sources = [
            _source("todo-self-this-week", f"当前用户待办。负责人：说话人1。截止时间：{this_week}。状态：待处理。依据：测试"),
            _source("todo-other-this-week", f"其他人待办。负责人：张三。截止时间：{this_week}。状态：待处理。依据：测试"),
        ]

        result = knowledge.answer_question(
            "本周我有哪些待办？",
            limit=8,
            user_id="user-1",
            scope=KnowledgeScope.local,
            local_sources=sources,
            user_name=None,
        )

        self.assertEqual(result["sources"], [])
        self.assertIn("没有找到分配给你的待办", result["answer"])

    def test_named_person_week_todo_plan_keeps_person_and_time(self) -> None:
        plan = knowledge._build_query_plan("本周张三有哪些待办？", "说话人1", [])

        self.assertTrue(plan.fast_path)
        self.assertEqual(plan.intent, "todo_lookup")
        self.assertEqual(plan.participant_terms, ["张三"])
        self.assertEqual(plan.content_types, {"todo"})
        self.assertEqual(plan.time_label, "本周")

    def test_self_meeting_plan_uses_same_identity_rule(self) -> None:
        plan = knowledge._build_query_plan("本周我参加了哪些会议？", "说话人1", [])

        self.assertTrue(plan.fast_path)
        self.assertEqual(plan.intent, "participant_meetings")
        self.assertEqual(plan.participant_terms, ["说话人1"])
        self.assertEqual(plan.time_label, "本周")

    def test_self_reference_alone_is_not_forced_into_structured_lookup(self) -> None:
        with patch.object(knowledge, "plan_with_ai_service", return_value={"intent": "general", "content_types": []}):
            plan = knowledge._build_query_plan("我是谁？", "说话人1", [])

        self.assertFalse(plan.fast_path)
        self.assertEqual(plan.intent, "general")
        self.assertEqual(plan.participant_terms, [])


if __name__ == "__main__":
    unittest.main()
