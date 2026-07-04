from __future__ import annotations

import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request


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

from app import repositories


API_URL = "http://127.0.0.1:8080/api/v1/knowledge/ask"
PREFIX = "QA知识库测试"
CASE_DELAY_SECONDS = float(os.getenv("HUIXIAO_KNOWLEDGE_QA_CASE_DELAY", "8"))


CASES = [
    ("你好", ["查询会议"], []),
    ("今天有没有开过会", ["今天", "2 场"], ["QA知识库测试-项目A周会-今天", "QA知识库测试-客户B跟进-今天"]),
    ("今天开过哪些会议", ["今天", "项目A", "客户B"], ["QA知识库测试-项目A周会-今天", "QA知识库测试-客户B跟进-今天"]),
    ("最近有几场会议，有哪些人参加", ["3 场", "张三", "客户B"], ["张三", "客户B"]),
    ("我本周答应了哪些事", ["张三", "李四", "赵敏"], ["AI 待办"]),
    ("张三最近负责了什么", ["张三", "支付回调"], ["AI 待办"]),
    ("项目A最近有什么风险", ["支付回调", "后台录音"], ["AI 风险"]),
    ("客户B最近最关心哪些问题", ["私有化", "数据保留", "钉钉"], ["客户B"]),
    ("我们什么时候决定延期上线，原因是什么", ["延期", "支付回调", "后台录音"], ["AI 决策", "AI 议题", "说话人"]),
    ("最近三次项目周会提到的主要风险是什么", ["项目A", "支付回调", "后台录音"], ["AI 风险"]),
]


def main() -> None:
    task_ids = [task.id for task in repositories.list_tasks() if task.title.startswith(PREFIX)]
    if not task_ids:
        raise SystemExit("No QA knowledge tasks found. Run seed_knowledge_qa_data.py first.")
    failures = 0
    for question, expected_answer_terms, expected_source_terms in CASES:
        result = ask(question, task_ids)
        answer = result.get("answer", "")
        sources = result.get("sources", [])
        source_text = json.dumps(sources, ensure_ascii=False)
        answer_ok = all(term in answer for term in expected_answer_terms)
        source_ok = not expected_source_terms if not sources else any(term in source_text for term in expected_source_terms)
        status = "PASS" if answer_ok and source_ok else "FAIL"
        if status == "FAIL":
            failures += 1
        print(f"\n[{status}] {question}")
        print(answer)
        print("sources:", [f"{item.get('title')}|{item.get('speaker')}|{item.get('timestamp')}" for item in sources[:3]])
        time.sleep(CASE_DELAY_SECONDS)
    if failures:
        raise SystemExit(f"{failures} knowledge QA checks failed")


def ask(question: str, task_ids: list[str]) -> dict:
    payload = json.dumps({"question": question, "limit": 8, "task_ids": task_ids}, ensure_ascii=False).encode("utf-8")
    last_error = ""
    for attempt in range(3):
        request = urllib.request.Request(API_URL, data=payload, headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            last_error = exc.read().decode("utf-8", errors="ignore")
            if exc.code != 502 or attempt == 2:
                raise RuntimeError(f"knowledge ask failed: HTTP {exc.code} {last_error}") from exc
        time.sleep(2 + attempt * 2)
    raise RuntimeError(f"knowledge ask failed: {last_error}")


if __name__ == "__main__":
    main()
