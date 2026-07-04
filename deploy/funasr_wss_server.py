import asyncio
import base64
import json
import websockets
import time
import numpy as np
import argparse
import ssl
import os
import wave
import functools
from concurrent.futures import ThreadPoolExecutor
from scipy.spatial.distance import cosine

import torch  # 保留不影响


def to_python(obj):
    """递归地把 numpy / torch 等类型转成纯 Python，可 JSON 序列化。"""
    try:
        import numpy as np  # noqa
        import torch  # noqa
    except Exception:
        np = None
        torch = None

    if np is not None and isinstance(obj, np.generic):
        return obj.item()
    if np is not None and isinstance(obj, np.ndarray):
        return obj.tolist()
    if torch is not None and isinstance(obj, torch.Tensor):
        return obj.cpu().tolist()

    if isinstance(obj, dict):
        return {k: to_python(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [to_python(v) for v in obj]

    return obj


parser = argparse.ArgumentParser()
parser.add_argument("--host", type=str, default="0.0.0.0", required=False, help="host ip")
parser.add_argument("--port", type=int, default=10095, required=False, help="grpc server port")

parser.add_argument(
    "--asr_model_online",
    type=str,
    default="iic/speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-online",
    help="model from modelscope",
)
parser.add_argument("--asr_model_online_revision", type=str, default="v2.0.4", help="")

parser.add_argument(
    "--vad_model",
    type=str,
    default="iic/speech_fsmn_vad_zh-cn-16k-common-pytorch",
    help="model from modelscope",
)
parser.add_argument("--vad_model_revision", type=str, default="v2.0.4", help="")

parser.add_argument(
    "--punc_model",
    type=str,
    default="iic/punc_ct-transformer_zh-cn-common-vad_realtime-vocab272727",
    help="model from modelscope",
)
parser.add_argument("--punc_model_revision", type=str, default="v2.0.4", help="")

parser.add_argument("--ngpu", type=int, default=1, help="0 for cpu, 1 for gpu")
parser.add_argument("--device", type=str, default="cuda", help="cuda, cpu")
parser.add_argument("--ncpu", type=int, default=4, help="cpu cores")

parser.add_argument(
    "--certfile",
    type=str,
    default="../../ssl_key/server.crt",
    required=False,
    help="certfile for ssl",
)
parser.add_argument(
    "--keyfile",
    type=str,
    default="../../ssl_key/server.key",
    required=False,
    help="keyfile for ssl",
)

# ====== 保存 2pass 离线阶段送入 ASR 的音频片段（排查 VAD 切分）======
parser.add_argument(
    "--save_offline_segments",
    action="store_true",
    help="Save each offline (2pass) audio segment sent to offline ASR as wav for debugging VAD split.",
)
parser.add_argument(
    "--save_offline_segments_dir",
    type=str,
    default="./offline_segments",
    help="Directory to save offline wav segments when --save_offline_segments is enabled.",
)
parser.add_argument(
    "--vad_max_end_silence_time",
    type=int,
    default=800,
    help="FSMN-VAD end silence in ms. Higher values avoid fragmenting normal short pauses.",
)
parser.add_argument(
    "--offline_endpoint_delay_ms",
    type=int,
    default=300,
    help="Hold a VAD endpoint briefly before offline ASR so short pauses can be merged.",
)
parser.add_argument(
    "--offline_merge_silence_ms",
    type=int,
    default=260,
    help="Merge a new speech start into the current offline segment if the VAD gap is this short.",
)
parser.add_argument(
    "--offline_pre_roll_ms",
    type=int,
    default=240,
    help="Keep audio before the VAD speech start to avoid clipping utterance onsets.",
)

# ====== 并发控制：核心新增 ======
parser.add_argument(
    "--worker_threads",
    type=int,
    default=max(4, (os.cpu_count() or 4)),
    help="ThreadPoolExecutor max_workers. Used to offload blocking inference so event loop won't be blocked.",
)
parser.add_argument("--concurrent_vad", type=int, default=4, help="Max concurrent VAD generate() calls.")
parser.add_argument("--concurrent_asr_online", type=int, default=4, help="Max concurrent streaming ASR generate() calls.")
parser.add_argument("--concurrent_asr_offline", type=int, default=2, help="Max concurrent offline ASR generate() calls.")
parser.add_argument("--concurrent_punc", type=int, default=1, help="Max concurrent punctuation generate() calls.")
parser.add_argument("--concurrent_sv", type=int, default=1, help="Max concurrent speaker verification generate() calls.")
parser.add_argument("--max_speakers", type=int, default=15, help="Max speakers for CAMPPlus spectral clustering.")
parser.add_argument(
    "--speaker_cluster_threshold",
    type=float,
    default=0.62,
    help="Cosine similarity threshold for file-level CAMPPlus speaker clustering.",
)
parser.add_argument(
    "--speaker_db_reload_sec",
    type=int,
    default=5,
    help="Reload speaker_db.json at most once every N seconds (avoid frequent disk IO).",
)

args = parser.parse_args()

websocket_users = set()
FILE_CANCELLED_TASK_IDS = set()
SPEAKER_DB_PATH = os.path.join(os.path.dirname(__file__), "speaker_db.json")


class FileTaskCancelled(Exception):
    pass


def _ensure_dir(p: str):
    try:
        os.makedirs(p, exist_ok=True)
    except Exception:
        pass


def _clean_task_id(task_id) -> str:
    return str(task_id or "").strip()


def _request_file_task_cancel(task_id) -> str:
    clean_task_id = _clean_task_id(task_id)
    if clean_task_id:
        FILE_CANCELLED_TASK_IDS.add(clean_task_id)
    return clean_task_id


def _clear_file_task_cancel(task_id) -> None:
    clean_task_id = _clean_task_id(task_id)
    if clean_task_id:
        FILE_CANCELLED_TASK_IDS.discard(clean_task_id)


def _is_file_task_cancelled(task_id) -> bool:
    clean_task_id = _clean_task_id(task_id)
    return bool(clean_task_id and clean_task_id in FILE_CANCELLED_TASK_IDS)


def _raise_if_file_task_cancelled(task_id) -> None:
    if _is_file_task_cancelled(task_id):
        raise FileTaskCancelled("任务已终止")


async def _send_file_cancelled_result(websocket, segment_index: int, request_start_ms: int, task_id: str):
    await websocket.send(
        json.dumps(
            {
                "type": "file_pcm_result",
                "mode": "file-offline",
                "is_final": True,
                "segment_index": segment_index,
                "segment_start_ms": request_start_ms,
                "task_id": task_id,
                "text": "",
                "canceled": True,
                "error": "任务已终止",
            },
            ensure_ascii=False,
        )
    )


def _pcm_duration_ms(pcm_bytes: bytes, fs: int, ch: int = 1, sampwidth: int = 2) -> int:
    """根据 fs/ch/sampwidth 计算 PCM 时长，避免写死 16k -> 32 bytes/ms。"""
    if not pcm_bytes:
        return 0
    bytes_per_ms = (fs * ch * sampwidth) / 1000.0
    if bytes_per_ms <= 0:
        return 0
    return int(len(pcm_bytes) / bytes_per_ms)


def _slice_pcm_ms(pcm_bytes: bytes, start_ms: int, end_ms: int, fs: int, padding_ms: int = 120) -> tuple[bytes, int]:
    duration_ms = _pcm_duration_ms(pcm_bytes, fs=fs, ch=1, sampwidth=2)
    padded_start = max(0, int(start_ms) - int(padding_ms))
    padded_end = min(duration_ms, int(end_ms) + int(padding_ms))
    start_byte = int(padded_start * fs * 2 / 1000)
    end_byte = int(padded_end * fs * 2 / 1000)
    start_byte -= start_byte % 2
    end_byte -= end_byte % 2
    return pcm_bytes[start_byte:end_byte], padded_start


def _trim_active_pcm(pcm_bytes: bytes, fs: int, frame_ms: int = 100, padding_frames: int = 5) -> bytes:
    if not pcm_bytes or len(pcm_bytes) < 2:
        return b""
    if len(pcm_bytes) % 2 == 1:
        pcm_bytes = pcm_bytes[:-1]
    samples = np.frombuffer(pcm_bytes, dtype=np.int16).astype(np.float32)
    if len(samples) == 0:
        return b""
    peak = float(np.max(np.abs(samples)))
    if peak < 80.0:
        return b""
    frame_samples = max(1, int(fs * frame_ms / 1000))
    frame_count = len(samples) // frame_samples
    if frame_count <= 0:
        return pcm_bytes if peak >= 120.0 else b""
    framed = samples[: frame_count * frame_samples].reshape(frame_count, frame_samples)
    rms = np.sqrt(np.mean(np.square(framed), axis=1))
    max_rms = float(np.max(rms)) if len(rms) else 0.0
    if max_rms < 60.0:
        return b""
    threshold = max(45.0, max_rms * 0.08, float(np.percentile(rms, 70)) * 0.35)
    active = np.where(rms >= threshold)[0]
    if len(active) == 0:
        return b""
    start_frame = max(0, int(active[0]) - padding_frames)
    end_frame = min(frame_count, int(active[-1]) + padding_frames + 1)
    start_sample = start_frame * frame_samples
    end_sample = end_frame * frame_samples
    trimmed = samples[start_sample:end_sample]
    if len(trimmed) == 0:
        return b""
    return np.clip(trimmed, -32768, 32767).astype(np.int16).tobytes()


def _has_audible_pcm(pcm_bytes: bytes) -> bool:
    if not pcm_bytes or len(pcm_bytes) < 2:
        return False
    if len(pcm_bytes) % 2 == 1:
        pcm_bytes = pcm_bytes[:-1]
    samples = np.frombuffer(pcm_bytes, dtype=np.int16).astype(np.float32)
    if len(samples) == 0:
        return False
    peak = float(np.max(np.abs(samples)))
    if peak < 80.0:
        return False
    rms = float(np.sqrt(np.mean(np.square(samples))))
    nonzero_ratio = float(np.count_nonzero(samples) / len(samples))
    return rms >= 8.0 and nonzero_ratio >= 0.002


def _safe_int(v, default):
    try:
        return int(v)
    except Exception:
        return default


# ========= speaker db：加缓存，避免每段都读盘 =========
_SPEAKER_DB_CACHE = {}
_SPEAKER_DB_CACHE_TS = 0.0


def _load_speaker_db_sync():
    if not os.path.exists(SPEAKER_DB_PATH):
        return {}
    try:
        with open(SPEAKER_DB_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
            return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def get_speaker_db_cached(now_ts: float, reload_sec: int):
    global _SPEAKER_DB_CACHE, _SPEAKER_DB_CACHE_TS
    if (now_ts - _SPEAKER_DB_CACHE_TS) >= max(1, int(reload_sec)):
        _SPEAKER_DB_CACHE = _load_speaker_db_sync()
        _SPEAKER_DB_CACHE_TS = now_ts
    return _SPEAKER_DB_CACHE or {}


def _save_wav_sync(out_path: str, audio_bytes: bytes, fs: int, ch: int, sampwidth: int):
    with wave.open(out_path, "wb") as wf:
        wf.setnchannels(ch)
        wf.setsampwidth(sampwidth)
        wf.setframerate(fs)
        wf.writeframes(audio_bytes)


def save_offline_wav_segment_sync(websocket, audio_bytes: bytes, reason: str = "offline"):
    """
    保存离线阶段送入 ASR 的音频片段，方便人工试听排查 VAD 切分是否正确。
    约定：audio_bytes 为 单声道 PCM16 little-endian（默认 16k）。
    （注意：这是同步函数，外层会放线程池执行）
    """
    if not getattr(websocket, "save_offline_segments", False):
        return
    if "2pass" not in (getattr(websocket, "mode", "") or ""):
        return
    if not audio_bytes:
        return

    fs = int(getattr(websocket, "audio_fs", 16000) or 16000)
    ch = 1
    sampwidth = 2  # int16

    # int16 对齐
    if len(audio_bytes) % 2 == 1:
        audio_bytes = audio_bytes[:-1]
        if not audio_bytes:
            return

    seg_idx = int(getattr(websocket, "offline_seg_idx", 0))
    websocket.offline_seg_idx = seg_idx + 1

    duration_ms = _pcm_duration_ms(audio_bytes, fs=fs, ch=ch, sampwidth=sampwidth)

    base_dir = getattr(websocket, "offline_save_dir", args.save_offline_segments_dir)
    _ensure_dir(base_dir)

    wav_name = (getattr(websocket, "wav_name", "microphone") or "microphone").replace("/", "_")
    ts = int(time.time() * 1000)
    fname = f"{wav_name}_{ts}_seg{seg_idx:04d}_{reason}_{duration_ms}ms.wav"
    out_path = os.path.join(base_dir, fname)

    try:
        _save_wav_sync(out_path, audio_bytes, fs=fs, ch=ch, sampwidth=sampwidth)
        print(f"[SAVE_OFFLINE_SEG] {out_path} ({duration_ms} ms, {len(audio_bytes)} bytes)")
    except Exception as e:
        print(f"[SAVE_OFFLINE_SEG] failed: {e}")


print("model loading")
from funasr import AutoModel  # noqa
from funasr.models.campplus.cluster_backend import SpectralCluster  # noqa

# ====== 离线 ASR ======
OFFLINE_ASR_MODEL = "paraformer-zh"
OFFLINE_ASR_MODEL_REVISION = "v2.0.4"
# 离线 ASR 只在 runtime 进程加载。模型替换必须走隔离实验，不能恢复 ASR service 内重复加载模型。
model_asr = AutoModel(
    model=OFFLINE_ASR_MODEL,
    model_revision=OFFLINE_ASR_MODEL_REVISION,
    ngpu=args.ngpu,
    ncpu=args.ncpu,
    device=args.device,
    disable_pbar=True,
    disable_log=True,
)

# streaming asr
model_asr_streaming = AutoModel(
    model=args.asr_model_online,
    model_revision=args.asr_model_online_revision,
    ngpu=args.ngpu,
    ncpu=args.ncpu,
    device=args.device,
    disable_pbar=True,
    disable_log=True,
)

# vad
model_vad = AutoModel(
    model=args.vad_model,
    model_revision=args.vad_model_revision,
    ngpu=args.ngpu,
    ncpu=args.ncpu,
    device=args.device,
    disable_pbar=True,
    disable_log=True,
)

# punc
if args.punc_model != "":
    model_punc = AutoModel(
        model=args.punc_model,
        model_revision=args.punc_model_revision,
        ngpu=args.ngpu,
        ncpu=args.ncpu,
        device=args.device,
        disable_pbar=True,
        disable_log=True,
    )
else:
    model_punc = None

# sv
model_sv = AutoModel(
    model="iic/speech_campplus_sv_zh-cn_16k-common",
    ngpu=args.ngpu,
    device=args.device,
    disable_pbar=True,
    disable_log=True,
)
model_spk_cluster = SpectralCluster(min_num_spks=1, max_num_spks=max(1, int(args.max_speakers)))

print("model loaded! (now supports multi-client with non-blocking inference)")


# ====== 线程池 + 并发阈值（核心）======
EXECUTOR = ThreadPoolExecutor(max_workers=int(args.worker_threads))

SEM_VAD = asyncio.Semaphore(max(1, int(args.concurrent_vad)))
SEM_ASR_ONLINE = asyncio.Semaphore(max(1, int(args.concurrent_asr_online)))
SEM_ASR_OFFLINE = asyncio.Semaphore(max(1, int(args.concurrent_asr_offline)))
SEM_PUNC = asyncio.Semaphore(max(1, int(args.concurrent_punc)))
SEM_SV = asyncio.Semaphore(max(1, int(args.concurrent_sv)))
SEM_WAV = asyncio.Semaphore(max(1, 4))  # 保存 wav 一般不需要太大


async def run_blocking(fn, *a, sem: asyncio.Semaphore | None = None, **kw):
    """
    把阻塞函数丢线程池执行，避免卡 event loop。
    sem 用于限流（避免 GPU / 模型被打爆）。
    """
    loop = asyncio.get_running_loop()
    call = functools.partial(fn, *a, **kw)
    if sem is None:
        return await loop.run_in_executor(EXECUTOR, call)
    async with sem:
        return await loop.run_in_executor(EXECUTOR, call)


def _generate_sync(model, audio_or_text, status_dict):
    # 注意：status_dict 里包含 cache，会被 generate 更新
    return model.generate(input=audio_or_text, **status_dict)


async def ws_reset(websocket):
    print("ws reset now, total num is ", len(websocket_users))

    websocket.status_dict_asr_online["cache"] = {}
    websocket.status_dict_asr_online["is_final"] = True
    websocket.status_dict_vad["cache"] = {}
    websocket.status_dict_vad["is_final"] = True
    websocket.status_dict_punc["cache"] = {}

    await websocket.close()


async def clear_websocket():
    for websocket in list(websocket_users):
        await ws_reset(websocket)
    websocket_users.clear()


async def ws_serve(websocket, path=None):
    # websockets 新版本不会传 path，这里做兼容
    if path is None:
        path = getattr(websocket, "path", None)
    frames = []
    frames_asr = []
    frames_asr_online = []
    global websocket_users
    websocket_users.add(websocket)

    websocket.status_dict_asr = {}  # hotword 等
    websocket.status_dict_asr_online = {"cache": {}, "is_final": False}
    websocket.send_lock = asyncio.Lock()
    websocket.live_refine_tasks = set()
    vad_max_end_silence_time = max(300, int(args.vad_max_end_silence_time))
    endpoint_delay_ms = max(0, int(args.offline_endpoint_delay_ms))
    merge_silence_ms = max(0, int(args.offline_merge_silence_ms))
    pre_roll_ms = max(0, int(args.offline_pre_roll_ms))

    websocket.status_dict_vad = {
        "cache": {},
        "is_final": False,
        "max_end_silence_time": vad_max_end_silence_time,
    }
    websocket.status_dict_punc = {"cache": {}}

    websocket.chunk_interval = 10
    websocket.vad_pre_idx = 0
    websocket.current_speech_start_ms = 0
    speech_start = False
    speech_end_i = -1
    pending_speech_end_i = -1
    pending_endpoint_seen_ms = -1

    websocket.wav_name = "microphone"
    websocket.mode = "2pass"
    websocket.is_speaking = True  # ✅ 默认初始化，避免 AttributeError
    websocket.pending_file_pcm_request = None

    # 保存离线片段
    websocket.audio_fs = 16000
    websocket.offline_seg_idx = 0
    websocket.spk_embeddings = []
    websocket.save_offline_segments = bool(args.save_offline_segments)
    websocket.offline_save_dir = args.save_offline_segments_dir
    if websocket.save_offline_segments:
        _ensure_dir(websocket.offline_save_dir)
        print(f"[SAVE_OFFLINE_SEG] enabled, dir={websocket.offline_save_dir}")

    print("new user connected", flush=True)

    try:
        async for message in websocket:
            # ========== 1) 先处理“文本配置消息” ==========
            if isinstance(message, str):
                try:
                    messagejson = json.loads(message)
                except Exception as e:
                    print("bad json message:", e, message[:200])
                    continue

                log_message = messagejson
                if messagejson.get("type") == "file_pcm_transcribe" and "audio_b64" in messagejson:
                    log_message = dict(messagejson)
                    log_message["audio_b64"] = f"<base64:{len(str(messagejson.get('audio_b64') or ''))} chars>"
                print("=============messagejson============", log_message)

                if messagejson.get("type") == "file_pcm_transcribe":
                    await async_file_pcm_asr(websocket, messagejson)
                    continue

                if messagejson.get("type") == "file_pcm_transcribe_binary":
                    previous_request = getattr(websocket, "pending_file_pcm_request", None)
                    if previous_request is not None:
                        await _send_file_pcm_error(websocket, previous_request, "missing file pcm binary frame")
                    websocket.pending_file_pcm_request = messagejson
                    continue

                if messagejson.get("type") == "file_pcm_cancel":
                    task_id = _request_file_task_cancel(messagejson.get("task_id"))
                    await websocket.send(
                        json.dumps(
                            {
                                "type": "file_pcm_cancel_ack",
                                "task_id": task_id,
                                "canceled": bool(task_id),
                            },
                            ensure_ascii=False,
                        )
                    )
                    continue

                if "is_speaking" in messagejson:
                    websocket.is_speaking = bool(messagejson["is_speaking"])
                    websocket.status_dict_asr_online["is_final"] = (not websocket.is_speaking)

                if "chunk_interval" in messagejson:
                    websocket.chunk_interval = _safe_int(
                        messagejson["chunk_interval"], websocket.chunk_interval
                    )

                if "wav_name" in messagejson:
                    websocket.wav_name = messagejson.get("wav_name") or websocket.wav_name

                if "chunk_size" in messagejson:
                    chunk_size = messagejson["chunk_size"]
                    if isinstance(chunk_size, str):
                        chunk_size = [x.strip() for x in chunk_size.split(",") if x.strip()]
                    websocket.status_dict_asr_online["chunk_size"] = [int(x) for x in chunk_size]

                if "encoder_chunk_look_back" in messagejson:
                    websocket.status_dict_asr_online["encoder_chunk_look_back"] = messagejson[
                        "encoder_chunk_look_back"
                    ]

                if "decoder_chunk_look_back" in messagejson:
                    websocket.status_dict_asr_online["decoder_chunk_look_back"] = messagejson[
                        "decoder_chunk_look_back"
                    ]

                if "hotwords" in messagejson:
                    hotword_data = messagejson["hotwords"]
                    websocket.status_dict_asr["hotword"] = hotword_data
                    websocket.status_dict_asr_online["hotword"] = hotword_data
                    print(f"热词已更新: {hotword_data}")

                if "mode" in messagejson:
                    websocket.mode = messagejson["mode"] or websocket.mode

                if "audio_fs" in messagejson:
                    websocket.audio_fs = _safe_int(messagejson["audio_fs"], 16000)

                continue

            # ========== 2) 处理“二进制音频消息” ==========
            pending_file_request = getattr(websocket, "pending_file_pcm_request", None)
            if pending_file_request is not None:
                websocket.pending_file_pcm_request = None
                expected_bytes = _safe_int(pending_file_request.get("audio_bytes"), -1)
                if expected_bytes >= 0 and expected_bytes != len(message):
                    await _send_file_pcm_error(
                        websocket,
                        pending_file_request,
                        f"file pcm binary length mismatch: expected {expected_bytes}, got {len(message)}",
                    )
                    continue
                await async_file_pcm_asr_bytes(websocket, pending_file_request, message)
                continue

            if "chunk_size" not in websocket.status_dict_asr_online:
                print("[WARN] chunk_size not set yet, skip audio frame (send config first).")
                continue

            try:
                websocket.status_dict_vad["chunk_size"] = int(
                    websocket.status_dict_asr_online["chunk_size"][1] * 60 / websocket.chunk_interval
                )
            except Exception as e:
                print("[WARN] set vad chunk_size failed:", e)
                continue

            pcm = message
            frames.append(pcm)

            duration_ms = _pcm_duration_ms(pcm, fs=websocket.audio_fs, ch=1, sampwidth=2)
            websocket.vad_pre_idx += duration_ms

            # online asr
            frames_asr_online.append(pcm)
            websocket.status_dict_asr_online["is_final"] = (speech_end_i != -1)

            if (len(frames_asr_online) % websocket.chunk_interval == 0) or websocket.status_dict_asr_online["is_final"]:
                if websocket.mode in ("2pass", "online", "online_refine"):
                    audio_in = b"".join(frames_asr_online)
                    try:
                        await async_asr_online(websocket, audio_in)
                    except Exception:
                        print(f"error in asr streaming, {websocket.status_dict_asr_online}")
                frames_asr_online = []

            if speech_start:
                frames_asr.append(pcm)

            # vad online
            try:
                speech_start_i, speech_end_i = await async_vad(websocket, pcm)
            except Exception as e:
                print("error in vad:", e)
                speech_start_i, speech_end_i = -1, -1

            if speech_start_i != -1:
                if speech_start and pending_speech_end_i != -1:
                    gap_ms = max(0, int(speech_start_i) - int(pending_speech_end_i))
                    if gap_ms <= merge_silence_ms:
                        pending_speech_end_i = -1
                        pending_endpoint_seen_ms = -1
                        speech_end_i = -1
                    else:
                        prior_frames_asr = frames_asr[:-1] if frames_asr else []
                        if websocket.mode in ("2pass", "offline"):
                            await _run_offline_asr_segment(websocket, b"".join(prior_frames_asr), "vad_end")
                        elif websocket.mode == "online_refine":
                            await _send_online_segment_reset(websocket)
                            _schedule_live_refine_segment(websocket, b"".join(prior_frames_asr), "vad_end")
                        frames_asr = []
                        frames_asr_online = []
                        websocket.status_dict_asr_online["cache"] = {}
                        speech_start = False
                        frames = frames[-20:]
                        pending_speech_end_i = -1
                        pending_endpoint_seen_ms = -1

                if not speech_start:
                    speech_start = True
                    segment_start_i = max(0, int(speech_start_i) - pre_roll_ms)
                    websocket.current_speech_start_ms = segment_start_i
                    if duration_ms > 0:
                        beg_bias = (websocket.vad_pre_idx - segment_start_i) // duration_ms
                    else:
                        beg_bias = 0
                    frames_pre = frames[-beg_bias:] if beg_bias > 0 else []
                    frames_asr = []
                    frames_asr.extend(frames_pre)

            if speech_end_i != -1 and speech_start:
                pending_speech_end_i = int(speech_end_i)
                pending_endpoint_seen_ms = int(websocket.vad_pre_idx)
                speech_end_i = -1

            # ========== 3) 2pass：离线阶段触发点 ==========
            should_flush_offline = not websocket.is_speaking
            flush_reason = "not_speaking"
            if not should_flush_offline and pending_speech_end_i != -1:
                if pending_endpoint_seen_ms < 0:
                    pending_endpoint_seen_ms = int(websocket.vad_pre_idx)
                if int(websocket.vad_pre_idx) - pending_endpoint_seen_ms >= endpoint_delay_ms:
                    should_flush_offline = True
                    flush_reason = "vad_end"

            if should_flush_offline:
                if websocket.mode in ("2pass", "offline"):
                    audio_in = b"".join(frames_asr)
                    fallback_pcm = b"".join(frames) if not websocket.is_speaking else None
                    await _run_offline_asr_segment(websocket, audio_in, flush_reason, fallback_pcm=fallback_pcm)
                elif websocket.mode == "online_refine":
                    audio_in = b"".join(frames_asr)
                    fallback_pcm = b"".join(frames) if not websocket.is_speaking else None
                    await _send_online_segment_reset(websocket)
                    if websocket.is_speaking:
                        _schedule_live_refine_segment(websocket, audio_in, flush_reason, fallback_pcm=fallback_pcm)
                    else:
                        await _run_offline_asr_segment(websocket, audio_in, flush_reason, fallback_pcm=fallback_pcm)
                        await _drain_live_refine_tasks(websocket)

                frames_asr = []
                speech_start = False
                frames_asr_online = []
                websocket.status_dict_asr_online["cache"] = {}
                pending_speech_end_i = -1
                pending_endpoint_seen_ms = -1

                if not websocket.is_speaking:
                    websocket.vad_pre_idx = 0
                    frames = []
                    websocket.status_dict_vad["cache"] = {}
                    speech_end_i = -1
                else:
                    frames = frames[-20:]

    except websockets.ConnectionClosed:
        print("ConnectionClosed...", websocket_users, flush=True)
        _cancel_live_refine_tasks(websocket)
        await ws_reset(websocket)
        if websocket in websocket_users:
            websocket_users.remove(websocket)
    except websockets.InvalidState:
        print("InvalidState...")
    except Exception as e:
        print("Exception:", e)
        _cancel_live_refine_tasks(websocket)
        try:
            await ws_reset(websocket)
        except Exception:
            pass
        if websocket in websocket_users:
            websocket_users.remove(websocket)


def _schedule_live_refine_segment(websocket, audio_in: bytes, reason: str, fallback_pcm: bytes | None = None) -> None:
    if not audio_in and not fallback_pcm:
        return
    tasks = getattr(websocket, "live_refine_tasks", None)
    if tasks is None:
        websocket.live_refine_tasks = set()
        tasks = websocket.live_refine_tasks
    if len(tasks) >= 2:
        return
    segment_start_ms = int(getattr(websocket, "current_speech_start_ms", 0) or 0)
    task = asyncio.create_task(
        _run_offline_asr_segment(
            websocket,
            audio_in,
            f"live_refine:{reason}",
            fallback_pcm=fallback_pcm,
            refine=True,
            segment_start_ms=segment_start_ms,
        )
    )
    tasks.add(task)

    def _cleanup(done_task):
        tasks.discard(done_task)
        try:
            done_task.result()
        except asyncio.CancelledError:
            pass
        except Exception as exc:
            print("[LIVE_REFINE] task failed:", exc)

    task.add_done_callback(_cleanup)


async def _send_online_segment_reset(websocket) -> None:
    await _send_json(
        websocket,
        {
            "mode": getattr(websocket, "mode", "online_refine"),
            "text": "",
            "is_final": True,
            "reset_online": True,
            "wav_name": getattr(websocket, "wav_name", "microphone"),
            "segment_start_ms": int(getattr(websocket, "current_speech_start_ms", 0) or 0),
        },
    )


async def _drain_live_refine_tasks(websocket) -> None:
    tasks = list(getattr(websocket, "live_refine_tasks", set()))
    if not tasks:
        return
    await asyncio.gather(*tasks, return_exceptions=True)


def _cancel_live_refine_tasks(websocket) -> None:
    for task in list(getattr(websocket, "live_refine_tasks", set())):
        task.cancel()


async def _run_offline_asr_segment(
    websocket,
    audio_in: bytes,
    reason: str,
    fallback_pcm: bytes | None = None,
    refine: bool = False,
    segment_start_ms: int | None = None,
):
    if not audio_in and fallback_pcm:
        fallback_audio = _trim_active_pcm(
            fallback_pcm,
            fs=int(getattr(websocket, "audio_fs", 16000) or 16000),
        )
        if fallback_audio:
            audio_in = fallback_audio
            reason = "no_vad_fallback"
            print(
                f"[LIVE_FALLBACK] {websocket.wav_name}: VAD produced no segment, "
                f"fallback to {len(audio_in)} bytes / "
                f"{_pcm_duration_ms(audio_in, fs=websocket.audio_fs, ch=1, sampwidth=2)} ms"
            )

    if websocket.save_offline_segments and audio_in:
        try:
            await run_blocking(
                save_offline_wav_segment_sync,
                websocket,
                audio_in,
                reason,
                sem=SEM_WAV,
            )
        except Exception as e:
            print("[SAVE_OFFLINE_SEG] async failed:", e)

    try:
        await async_asr(websocket, audio_in, refine=refine, segment_start_ms=segment_start_ms)
    except Exception as e:
        print("error in asr offline:", e)


# ===================== 推理：全部改为“线程池 + 限流” =====================

async def async_vad(websocket, audio_in: bytes):
    # model_vad.generate 是阻塞的，必须 offload
    out = await run_blocking(_generate_sync, model_vad, audio_in, websocket.status_dict_vad, sem=SEM_VAD)
    segments_result = out[0].get("value", [])

    speech_start = -1
    speech_end = -1

    if len(segments_result) == 0 or len(segments_result) > 1:
        return speech_start, speech_end
    if segments_result[0][0] != -1:
        speech_start = segments_result[0][0]
    if segments_result[0][1] != -1:
        speech_end = segments_result[0][1]
    return speech_start, speech_end


def _sv_and_match_sync(audio_in: bytes, reload_sec: int):
    """
    同步执行：SV embedding + speaker_db 匹配
    返回 (spk_name, best_score)
    """
    spk_name = "unknown"
    best_score = 0.0
    embedding = None

    sv_out = model_sv.generate(input=audio_in, embedding=True)[0]
    embedding = sv_out["spk_embedding"][0].cpu().numpy()

    now_ts = time.time()
    local_speaker_db = get_speaker_db_cached(now_ts, reload_sec=reload_sec)
    if local_speaker_db:
        for name, ref_embedding in local_speaker_db.items():
            if ref_embedding is None:
                continue
            arr = np.array(ref_embedding, dtype=np.float32)
            similarity = 1.0 - cosine(embedding, arr)
            print("sv similarity with {}: {}".format(name, similarity))
            if similarity > best_score and similarity > 0.2:
                best_score = similarity
                spk_name = name

    return spk_name, float(best_score), embedding


def _cluster_embeddings_by_similarity(embeddings: list[np.ndarray], max_speakers: int, threshold: float) -> list[int]:
    if not embeddings:
        return []

    normalized = []
    for embedding in embeddings:
        arr = np.asarray(embedding, dtype=np.float32)
        norm = max(float(np.linalg.norm(arr)), 1e-6)
        normalized.append(arr / norm)

    centroids: list[np.ndarray] = []
    counts: list[int] = []
    labels: list[int] = []
    max_speakers = max(1, int(max_speakers))
    threshold = float(threshold)

    for embedding in normalized:
        if not centroids:
            centroids.append(embedding.copy())
            counts.append(1)
            labels.append(0)
            continue

        similarities = [float(np.dot(embedding, centroid)) for centroid in centroids]
        best_index = int(np.argmax(similarities))
        best_similarity = similarities[best_index]
        if best_similarity >= threshold or len(centroids) >= max_speakers:
            labels.append(best_index)
            counts[best_index] += 1
            centroids[best_index] = centroids[best_index] + (embedding - centroids[best_index]) / counts[best_index]
            centroids[best_index] = centroids[best_index] / max(float(np.linalg.norm(centroids[best_index])), 1e-6)
            continue

        centroids.append(embedding.copy())
        counts.append(1)
        labels.append(len(centroids) - 1)

    return labels


async def _send_json(websocket, message: dict):
    payload = json.dumps(message, ensure_ascii=False)
    lock = getattr(websocket, "send_lock", None)
    if lock is None:
        await websocket.send(payload)
        return
    async with lock:
        await websocket.send(payload)


async def async_asr(websocket, audio_in: bytes, refine: bool = False, segment_start_ms: int | None = None):
    mode = "2pass-offline" if "2pass" in (websocket.mode or "") else websocket.mode
    segment_start_ms = int(segment_start_ms if segment_start_ms is not None else (getattr(websocket, "current_speech_start_ms", 0) or 0))

    if len(audio_in) <= 0:
        message = {
            "mode": mode,
            "text": "",
            "wav_name": websocket.wav_name,
            "is_final": True,
            "refine": bool(refine),
            "segment_start_ms": segment_start_ms,
        }
        await _send_json(websocket, message)
        return

    if not _has_audible_pcm(audio_in):
        message = {
            "mode": mode,
            "text": "",
            "wav_name": websocket.wav_name,
            "is_final": True,
            "refine": bool(refine),
            "segment_start_ms": segment_start_ms,
        }
        await _send_json(websocket, message)
        return

    # 1) ASR（阻塞，线程池执行）
    rec_result_list = await run_blocking(
        _generate_sync,
        model_asr,
        audio_in,
        websocket.status_dict_asr,
        sem=SEM_ASR_OFFLINE,
    )
    rec_result = rec_result_list[0]

    print("offline_asr, raw:", rec_result)
    print("offline_asr, keys:", rec_result.keys())

    text = rec_result.get("text", "")
    timestamp = rec_result.get("timestamp", None)
    sentence_info = rec_result.get("sentence_info", None)

    # 2) 声纹识别（阻塞，线程池执行）
    spk_name = "unknown"
    best_score = 0.0
    spk_embedding = None
    if websocket.mode != "online_refine":
        try:
            spk_name, best_score, spk_embedding = await run_blocking(
                _sv_and_match_sync,
                audio_in,
                int(args.speaker_db_reload_sec),
                sem=SEM_SV,
            )
            if spk_name == "unknown" and spk_embedding is not None:
                websocket.spk_embeddings.append(spk_embedding)
                if len(websocket.spk_embeddings) == 1:
                    spk_name = "spk_0"
                else:
                    try:
                        labels = model_spk_cluster(np.stack(websocket.spk_embeddings, axis=0))
                        spk_name = f"spk_{int(labels[-1])}"
                    except Exception as e:
                        print("speaker cluster failed:", e)
                        spk_name = f"spk_{len(websocket.spk_embeddings) - 1}"
        except Exception as e:
            print(f"声纹识别失败: {e}")

    # 3) 标点（阻塞，线程池执行）
    punc_array = None
    if model_punc is not None and len(text) > 0:
        try:
            # punc 只对文本处理
            punc_out = await run_blocking(
                _generate_sync,
                model_punc,
                text,
                websocket.status_dict_punc,
                sem=SEM_PUNC,
            )
            punc_result = punc_out[0]
            print("offline, after punc", punc_result)

            if "text" in punc_result and punc_result["text"]:
                text = punc_result["text"]
            if "punc_array" in punc_result:
                punc_array = punc_result["punc_array"]
        except Exception as e:
            print("punc failed:", e)

    # 4) 构造最终 message
    if len(text) > 0:
        print("======offline final text:", text)
        message = {
            "mode": mode,
            "spk_name": spk_name,
            "spk_score": float(best_score),
            "text": text,
            "wav_name": websocket.wav_name,
            "is_final": True,
            "refine": bool(refine),
            "segment_start_ms": segment_start_ms,
        }
        if timestamp is not None:
            message["timestamp"] = to_python(timestamp)
        if sentence_info is not None:
            message["sentence_info"] = to_python(sentence_info)
        if punc_array is not None:
            message["punc_array"] = to_python(punc_array)

        try:
            await _send_json(websocket, message)
        except Exception as e:
            print("send json failed:", e)
            print("message types:", {k: type(v) for k, v in message.items()})
    else:
        message = {
            "mode": mode,
            "spk_name": spk_name,
            "spk_score": float(best_score),
            "text": "",
            "wav_name": websocket.wav_name,
            "is_final": True,
            "refine": bool(refine),
            "segment_start_ms": segment_start_ms,
        }
        await _send_json(websocket, message)


async def _send_file_pcm_error(websocket, messagejson: dict, error: str):
    mode = "file-offline"
    task_id = _clean_task_id(messagejson.get("task_id"))
    segment_index = _safe_int(messagejson.get("segment_index"), 0)
    request_start_ms = _safe_int(messagejson.get("segment_start_ms"), 0)
    await websocket.send(
        json.dumps(
            {
                "type": "file_pcm_result",
                "mode": mode,
                "is_final": True,
                "segment_index": segment_index,
                "segment_start_ms": request_start_ms,
                "task_id": task_id,
                "text": "",
                "error": error,
            },
            ensure_ascii=False,
        )
    )


async def async_file_pcm_asr(websocket, messagejson: dict):
    task_id = _clean_task_id(messagejson.get("task_id"))
    _clear_file_task_cancel(task_id)
    audio_b64 = messagejson.get("audio_b64") or ""
    try:
        _raise_if_file_task_cancelled(task_id)
        audio_in = base64.b64decode(audio_b64)
    except Exception as exc:
        await _send_file_pcm_error(websocket, messagejson, f"invalid audio_b64: {exc}")
        return

    await async_file_pcm_asr_bytes(websocket, messagejson, audio_in, reset_cancel=False)


async def async_file_pcm_asr_bytes(websocket, messagejson: dict, audio_in: bytes, reset_cancel: bool = True):
    mode = "file-offline"
    task_id = _clean_task_id(messagejson.get("task_id"))
    if reset_cancel:
        _clear_file_task_cancel(task_id)
    segment_index = _safe_int(messagejson.get("segment_index"), 0)
    request_start_ms = _safe_int(messagejson.get("segment_start_ms"), 0)
    audio_fs = _safe_int(messagejson.get("audio_fs"), 16000)

    if len(audio_in) <= 0:
        await websocket.send(
            json.dumps(
                {
                    "type": "file_pcm_result",
                    "mode": mode,
                    "is_final": True,
                    "segment_index": segment_index,
                    "segment_start_ms": request_start_ms,
                    "task_id": task_id,
                    "text": "",
                },
                ensure_ascii=False,
            )
        )
        return

    try:
        _raise_if_file_task_cancelled(task_id)
        vad_out = await run_blocking(_generate_sync, model_vad, audio_in, {}, sem=SEM_VAD)
        _raise_if_file_task_cancelled(task_id)
        vad_segments = []
        if vad_out and isinstance(vad_out[0], dict):
            for item in vad_out[0].get("value", []) or []:
                if not isinstance(item, (list, tuple)) or len(item) < 2:
                    continue
                start_ms = _safe_int(item[0], -1)
                end_ms = _safe_int(item[1], -1)
                if start_ms >= 0 and end_ms > start_ms:
                    vad_segments.append((start_ms, end_ms))

        if not vad_segments:
            vad_segments = [(0, _pcm_duration_ms(audio_in, fs=audio_fs, ch=1, sampwidth=2))]

        combined_text = []
        combined_sentence_info = []
        combined_timestamps = []
        combined_stamp_sents = []
        file_parts = []
        unknown_embeddings = []

        for speech_start_ms, speech_end_ms in vad_segments:
            _raise_if_file_task_cancelled(task_id)
            speech_pcm, slice_start_ms = _slice_pcm_ms(audio_in, speech_start_ms, speech_end_ms, fs=audio_fs)
            if not speech_pcm:
                continue

            rec_result_list = await run_blocking(
                _generate_sync,
                model_asr,
                speech_pcm,
                {},
                sem=SEM_ASR_OFFLINE,
            )
            _raise_if_file_task_cancelled(task_id)
            rec_result = rec_result_list[0] if rec_result_list else {}
            text = rec_result.get("text", "") if isinstance(rec_result, dict) else ""
            timestamp = rec_result.get("timestamp", None) if isinstance(rec_result, dict) else None
            sentence_info = rec_result.get("sentence_info", None) if isinstance(rec_result, dict) else None
            stamp_sents = rec_result.get("stamp_sents", None) if isinstance(rec_result, dict) else None

            spk_name = "unknown"
            best_score = 0.0
            spk_embedding = None
            try:
                spk_name, best_score, spk_embedding = await run_blocking(
                    _sv_and_match_sync,
                    speech_pcm,
                    int(args.speaker_db_reload_sec),
                    sem=SEM_SV,
                )
            except Exception as exc:
                print("file pcm sv failed:", exc)
            _raise_if_file_task_cancelled(task_id)

            unknown_embedding_index = None
            if spk_name == "unknown" and spk_embedding is not None:
                unknown_embedding_index = len(unknown_embeddings)
                unknown_embeddings.append(np.asarray(spk_embedding, dtype=np.float32))

            if model_punc is not None and len(text) > 0:
                try:
                    punc_out = await run_blocking(
                        _generate_sync,
                        model_punc,
                        text,
                        {},
                        sem=SEM_PUNC,
                    )
                    punc_result = punc_out[0] if punc_out else {}
                    if isinstance(punc_result, dict) and punc_result.get("text"):
                        text = punc_result["text"]
                except Exception as exc:
                    print("file pcm punc failed:", exc)
            _raise_if_file_task_cancelled(task_id)

            text = str(text or "").strip()
            if text:
                combined_text.append(text)

            file_parts.append(
                {
                    "text": text,
                    "timestamp": timestamp,
                    "sentence_info": sentence_info,
                    "stamp_sents": stamp_sents,
                    "speech_start_ms": speech_start_ms,
                    "speech_end_ms": speech_end_ms,
                    "part_offset_ms": request_start_ms + slice_start_ms,
                    "spk_name": spk_name,
                    "spk_score": float(best_score),
                    "unknown_embedding_index": unknown_embedding_index,
                }
            )

        unknown_speaker_names = {}
        if len(unknown_embeddings) == 1:
            unknown_speaker_names[0] = "spk_0"
        elif len(unknown_embeddings) > 1:
            try:
                max_speakers = max(1, min(int(args.max_speakers), len(unknown_embeddings)))
                raw_labels = _cluster_embeddings_by_similarity(
                    unknown_embeddings,
                    max_speakers=max_speakers,
                    threshold=float(args.speaker_cluster_threshold),
                )
                if len(set(raw_labels)) <= 1 and max_speakers > 1:
                    cluster = SpectralCluster(min_num_spks=1, max_num_spks=max_speakers)
                    raw_labels = [int(item) for item in cluster(np.stack(unknown_embeddings, axis=0))]
                label_map = {}
                for embedding_index, raw_label in enumerate(raw_labels):
                    if raw_label not in label_map:
                        label_map[raw_label] = len(label_map)
                    unknown_speaker_names[embedding_index] = f"spk_{label_map[raw_label]}"
            except Exception as exc:
                print("file pcm global speaker cluster failed:", exc)
                for embedding_index in range(len(unknown_embeddings)):
                    unknown_speaker_names[embedding_index] = f"spk_{embedding_index}"

        for part in file_parts:
            _raise_if_file_task_cancelled(task_id)
            spk_name = part["spk_name"]
            unknown_embedding_index = part["unknown_embedding_index"]
            if spk_name == "unknown" and unknown_embedding_index is not None:
                spk_name = unknown_speaker_names.get(unknown_embedding_index, f"spk_{unknown_embedding_index}")
            spk_score = float(part["spk_score"])
            part_offset_ms = int(part["part_offset_ms"])
            text = str(part["text"] or "").strip()
            sentence_info = part["sentence_info"]
            stamp_sents = part["stamp_sents"]
            timestamp = part["timestamp"]
            speech_start_ms = int(part["speech_start_ms"])
            speech_end_ms = int(part["speech_end_ms"])

            if isinstance(sentence_info, list) and sentence_info:
                for sentence in sentence_info:
                    if not isinstance(sentence, dict):
                        continue
                    adjusted = dict(sentence)
                    adjusted["start"] = _safe_int(adjusted.get("start"), 0) + part_offset_ms
                    adjusted["end"] = _safe_int(adjusted.get("end"), 0) + part_offset_ms
                    adjusted["spk_name"] = spk_name
                    adjusted["spk_score"] = spk_score
                    combined_sentence_info.append(adjusted)
            elif text:
                combined_sentence_info.append(
                    {
                        "text": text,
                        "start": request_start_ms + speech_start_ms,
                        "end": request_start_ms + speech_end_ms,
                        "spk_name": spk_name,
                        "spk_score": spk_score,
                    }
                )

            if isinstance(stamp_sents, list):
                for sentence in stamp_sents:
                    if not isinstance(sentence, dict):
                        continue
                    adjusted = dict(sentence)
                    adjusted["start"] = _safe_int(adjusted.get("start"), 0) + part_offset_ms
                    adjusted["end"] = _safe_int(adjusted.get("end"), 0) + part_offset_ms
                    adjusted["spk_name"] = spk_name
                    adjusted["spk_score"] = spk_score
                    combined_stamp_sents.append(adjusted)

            if isinstance(timestamp, list):
                for ts in timestamp:
                    if isinstance(ts, (list, tuple)) and len(ts) >= 2:
                        combined_timestamps.append([
                            _safe_int(ts[0], 0) + part_offset_ms,
                            _safe_int(ts[1], 0) + part_offset_ms,
                        ])

        message = {
            "type": "file_pcm_result",
            "mode": mode,
            "is_final": True,
            "segment_index": segment_index,
            "segment_start_ms": 0,
            "task_id": task_id,
            "text": "".join(combined_text),
            "wav_name": messagejson.get("wav_name") or "file-transcription",
        }
        if combined_timestamps:
            message["timestamp"] = to_python(combined_timestamps)
        if combined_sentence_info:
            message["sentence_info"] = to_python(combined_sentence_info)
        if combined_stamp_sents:
            message["stamp_sents"] = to_python(combined_stamp_sents)
        _raise_if_file_task_cancelled(task_id)
        await websocket.send(json.dumps(message, ensure_ascii=False))
        _clear_file_task_cancel(task_id)
    except FileTaskCancelled:
        await _send_file_cancelled_result(websocket, segment_index, request_start_ms, task_id)
    except Exception as exc:
        if _is_file_task_cancelled(task_id):
            await _send_file_cancelled_result(websocket, segment_index, request_start_ms, task_id)
            return
        await websocket.send(
            json.dumps(
                {
                    "type": "file_pcm_result",
                    "mode": mode,
                    "is_final": True,
                    "segment_index": segment_index,
                    "segment_start_ms": request_start_ms,
                    "task_id": task_id,
                    "text": "",
                    "error": str(exc),
                },
                ensure_ascii=False,
            )
        )


async def async_asr_online(websocket, audio_in: bytes):
    if len(audio_in) <= 0:
        return

    # streaming generate 也是阻塞：线程池执行
    rec_out = await run_blocking(
        _generate_sync,
        model_asr_streaming,
        audio_in,
        websocket.status_dict_asr_online,
        sem=SEM_ASR_ONLINE,
    )
    rec_result = rec_out[0]
    print("online, ", rec_result)

    # 2pass：online 只要 partial，不发 final（final 交给 offline）
    if websocket.mode == "2pass" and websocket.status_dict_asr_online.get("is_final", False):
        return

    if rec_result.get("text"):
        mode = "2pass-online" if "2pass" in (websocket.mode or "") else websocket.mode
        message = {
            "mode": mode,
            "text": rec_result["text"],
            "wav_name": websocket.wav_name,
            "is_final": bool(
                websocket.status_dict_asr_online.get("is_final", False) or (not websocket.is_speaking)
            ),
        }
        await _send_json(websocket, message)


# ===================== 启动服务 =====================

async def main():
    if len(args.certfile) > 0:
        ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ssl_context.load_cert_chain(args.certfile, keyfile=args.keyfile)
        server = await websockets.serve(
            ws_serve,
            args.host,
            args.port,
            subprotocols=["binary"],
            max_size=None,
            ping_interval=None,
            ssl=ssl_context,
        )
    else:
        server = await websockets.serve(
            ws_serve,
            args.host,
            args.port,
            subprotocols=["binary"],
            max_size=None,
            ping_interval=None,
        )

    print(f"WS server started at ws(s)://{args.host}:{args.port}")
    await server.wait_closed()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    finally:
        try:
            EXECUTOR.shutdown(wait=False, cancel_futures=True)
        except Exception:
            pass
