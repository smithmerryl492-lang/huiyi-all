from __future__ import annotations

import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi import HTTPException

from app.services.errors import (
    AI_USER_MESSAGE,
    ASR_USER_MESSAGE,
    SERVER_MAINTENANCE_MESSAGE,
    user_message_from_exception,
    user_message_from_text,
)


class ErrorMessageTest(unittest.TestCase):
    def test_connection_error_is_server_maintenance(self) -> None:
        message = user_message_from_text("Failed to connect to /43.154.197.96:28080")

        self.assertEqual(message, SERVER_MAINTENANCE_MESSAGE)

    def test_ai_and_asr_errors_are_user_facing(self) -> None:
        self.assertEqual(user_message_from_text("AI 服务不可用：Connection refused"), AI_USER_MESSAGE)
        self.assertEqual(user_message_from_text("ASR 服务不可用：Connection refused"), ASR_USER_MESSAGE)

    def test_safe_business_message_is_preserved(self) -> None:
        self.assertEqual(user_message_from_text("验证码错误"), "验证码错误")

    def test_http_500_keeps_safe_downstream_message(self) -> None:
        error = HTTPException(status_code=502, detail=AI_USER_MESSAGE)

        self.assertEqual(user_message_from_exception(error), AI_USER_MESSAGE)


if __name__ == "__main__":
    unittest.main()
