from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def read_text(relative_path: str) -> str:
    return (ROOT / relative_path).read_text(encoding="utf-8")


def fail(message: str) -> None:
    print(f"ASR safety check failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def assert_missing(relative_path: str) -> None:
    if (ROOT / relative_path).exists():
        fail(f"{relative_path} must not exist in the active ASR path")


def assert_not_contains(relative_path: str, pattern: str, description: str) -> None:
    text = read_text(relative_path)
    if re.search(pattern, text, flags=re.MULTILINE):
        fail(f"{relative_path} still contains {description}")


def main() -> None:
    assert_missing("services/asr_service/app/inference/dolphin_engine.py")
    assert_not_contains(
        "services/asr_service/app/main.py",
        r"dolphin_engine|HUIXIAO_DOLPHIN|dolphin_model|funasr_shim_path",
        "legacy Dolphin ASR health/import wiring",
    )
    assert_not_contains(
        "services/asr_service/app/inference/funasr_file_engine.py",
        r"dolphin_engine",
        "legacy Dolphin ASR import",
    )
    assert_not_contains(
        "deploy/funasr_wss_server.py",
        r"parser\.add_argument\(\s*[\"']--asr_model[\"']",
        "unused offline ASR model override",
    )
    assert_not_contains(
        "services/asr_service/app/inference/funasr_live_gateway.py",
        r"LiveAudioPreprocessor|_normalize_live_pcm_frame|[\"']hotwords[\"']\s*:",
        "live preprocessing or default hotword injection",
    )
    for relative_path in [
        "services/asr_service/app/core/config.py",
        "services/asr_service/Dockerfile",
        "deploy/docker-compose.test.yml",
        "infra/docker-compose.yml",
    ]:
        assert_not_contains(
            relative_path,
            r"HUIXIAO_DOLPHIN|HUIXIAO_FUNASR_SHIM|Dolphin_poc/models/base\.cn|/opt/dolphin|/models/base\.cn",
            "legacy Dolphin runtime configuration",
        )

    print("ASR safety check passed.")


if __name__ == "__main__":
    main()
