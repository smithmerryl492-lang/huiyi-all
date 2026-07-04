import subprocess
import tempfile
from pathlib import Path

import soundfile as sf

from app.core.config import settings


def prepare_audio(source_path: Path) -> tuple[Path, tempfile.TemporaryDirectory[str] | None]:
    suffix = source_path.suffix.lower()
    if suffix == ".wav":
        try:
            info = sf.info(str(source_path))
            if info.samplerate == 16000 and info.channels == 1:
                return source_path, None
        except Exception:
            pass

    temp_dir = tempfile.TemporaryDirectory(prefix="huixiao_asr_")
    temp_path = Path(temp_dir.name) / "input_16k_mono.wav"
    command = [
        settings.ffmpeg_path,
        "-y",
        "-i",
        str(source_path),
        "-vn",
        "-ac",
        "1",
        "-ar",
        "16000",
        "-f",
        "wav",
        str(temp_path),
    ]
    try:
        subprocess.run(command, check=True, capture_output=True, timeout=settings.audio_prepare_timeout_seconds)
    except FileNotFoundError as exc:
        temp_dir.cleanup()
        raise RuntimeError("ASR 音频预处理需要 ffmpeg，请安装或配置 HUIXIAO_FFMPEG_PATH") from exc
    except subprocess.TimeoutExpired as exc:
        temp_dir.cleanup()
        raise RuntimeError(f"音频预处理超时：超过 {settings.audio_prepare_timeout_seconds} 秒仍未完成") from exc
    except subprocess.CalledProcessError as exc:
        temp_dir.cleanup()
        stderr = (exc.stderr or b"").decode("utf-8", errors="replace")
        raise RuntimeError(f"音频预处理失败：{stderr[-1000:]}") from exc

    return temp_path, temp_dir
