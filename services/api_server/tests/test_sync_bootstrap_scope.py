from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path


if not os.getenv("HUIXIAO_DATABASE_URL", "").strip():
    raise unittest.SkipTest("HUIXIAO_DATABASE_URL is required; local databases are not supported.")
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.db import init_db
from app import repositories
from app.api.v1.routes.sync import bootstrap
from app.schemas import MeetingProcessingResult, MeetingTaskSource, TaskSyncScope, TranscriptSegment


class SyncBootstrapScopeTest(unittest.TestCase):
    def setUp(self) -> None:
        init_db()
        self.temp_dir = tempfile.TemporaryDirectory()
        self.cloud_audio = Path(self.temp_dir.name) / "cloud.wav"
        self.cloud_shell_audio = Path(self.temp_dir.name) / "cloud-shell.wav"
        self.local_audio = Path(self.temp_dir.name) / "local.wav"
        self.cloud_audio.write_bytes(b"cloud-audio")
        self.cloud_shell_audio.write_bytes(b"cloud-shell-audio")
        self.local_audio.write_bytes(b"local-audio")
        repositories.ensure_user_exists("sync-user", "+8613800000003")

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def _create_task(self, path: Path, scope: TaskSyncScope) -> str:
        _, task = repositories.create_file_and_task_with_options(
            original_name=path.name,
            stored_path=str(path),
            content_type="audio/wav",
            size_bytes=path.stat().st_size,
            source=MeetingTaskSource.import_file,
            user_id="sync-user",
            sync_scope=scope,
        )
        return task.id

    def _save_minimal_result(self, task_id: str, path: Path) -> None:
        repositories.save_result(
            MeetingProcessingResult(
                task_id=task_id,
                source_file_path=str(path),
                summary="会议摘要",
                decisions=[],
                todos=[],
                risks=[],
                transcripts=[
                    TranscriptSegment(
                        speaker="说话人",
                        text="会议内容",
                        timestamp="00:00",
                    )
                ],
                generated_at=repositories.now_iso(),
            )
        )

    def test_bootstrap_only_returns_cloud_tasks_with_results(self) -> None:
        cloud_task_id = self._create_task(self.cloud_audio, TaskSyncScope.cloud)
        cloud_shell_task_id = self._create_task(self.cloud_shell_audio, TaskSyncScope.cloud)
        local_task_id = self._create_task(self.local_audio, TaskSyncScope.local_processing)
        self._save_minimal_result(cloud_task_id, self.cloud_audio)

        response = bootstrap(user_id="sync-user")

        returned_ids = {item.task.id for item in response.tasks}
        self.assertIn(cloud_task_id, returned_ids)
        self.assertNotIn(cloud_shell_task_id, returned_ids)
        self.assertNotIn(local_task_id, returned_ids)

        all_items = repositories.list_user_task_items("sync-user")
        self.assertEqual({item["task"].id for item in all_items}, {cloud_task_id, cloud_shell_task_id, local_task_id})


if __name__ == "__main__":
    unittest.main()
