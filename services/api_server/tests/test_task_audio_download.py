from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path


if not os.getenv("HUIXIAO_DATABASE_URL", "").strip():
    raise unittest.SkipTest("HUIXIAO_DATABASE_URL is required; local databases are not supported.")
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi import HTTPException

from app.db import init_db
from app import repositories
from app.api.v1.routes.tasks import download_task_audio
from app.schemas import MeetingTaskSource


class TaskAudioDownloadTest(unittest.TestCase):
    def setUp(self) -> None:
        init_db()
        self.temp_dir = tempfile.TemporaryDirectory()
        self.audio_path = Path(self.temp_dir.name) / "meeting-audio.mp4"
        self.audio_bytes = b"huixiao-audio-bytes"
        self.audio_path.write_bytes(self.audio_bytes)
        repositories.ensure_user_exists("audio-user", "+8613800000001")
        repositories.ensure_user_exists("other-user", "+8613800000002")

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def _create_task(self, user_id: str = "audio-user") -> str:
        _, task = repositories.create_file_and_task_with_options(
            original_name="meeting-audio.mp4",
            stored_path=str(self.audio_path),
            content_type="audio/mp4",
            size_bytes=len(self.audio_bytes),
            source=MeetingTaskSource.import_file,
            user_id=user_id,
        )
        return task.id

    def test_owner_can_download_task_audio(self) -> None:
        task_id = self._create_task()

        response = download_task_audio(task_id, user_id="audio-user")

        self.assertEqual(Path(response.path).read_bytes(), self.audio_bytes)
        self.assertEqual(response.media_type, "audio/mp4")

    def test_other_user_cannot_download_task_audio(self) -> None:
        task_id = self._create_task()

        with self.assertRaises(HTTPException) as context:
            download_task_audio(task_id, user_id="other-user")
        self.assertEqual(context.exception.status_code, 404)

    def test_missing_physical_audio_returns_404(self) -> None:
        task_id = self._create_task()
        self.audio_path.unlink()

        with self.assertRaises(HTTPException) as context:
            download_task_audio(task_id, user_id="audio-user")
        self.assertEqual(context.exception.status_code, 404)
        self.assertEqual(context.exception.detail, "云端音频文件不存在")


if __name__ == "__main__":
    unittest.main()
