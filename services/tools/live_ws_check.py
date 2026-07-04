import argparse
import asyncio
import json
import wave
from pathlib import Path

import websockets


async def run_check(url: str, wav_path: Path, realtime: bool) -> int:
    finals = []
    final_events = 0
    partials = 0
    errors = []
    session_id = f"check-{wav_path.stem}"
    target = f"{url}?session_id={session_id}"

    with wave.open(str(wav_path), "rb") as wav:
        channels = wav.getnchannels()
        sample_rate = wav.getframerate()
        sample_width = wav.getsampwidth()
        frames = wav.readframes(wav.getnframes())

    if channels != 1 or sample_rate != 16000 or sample_width != 2:
        raise ValueError("test wav must be PCM 16k mono s16le")

    async with websockets.connect(target, max_size=None, ping_interval=20, ping_timeout=20) as ws:
        await ws.send(json.dumps({
            "type": "audio.start",
            "format": "pcm_s16le",
            "sample_rate": 16000,
            "channels": 1,
            "speaker_separation": True,
        }, ensure_ascii=False))

        async def receiver():
            nonlocal partials, final_events
            async for message in ws:
                if isinstance(message, bytes):
                    continue
                payload = json.loads(message)
                event_type = payload.get("type")
                if event_type == "error":
                    errors.append(payload.get("message", "unknown error"))
                    break
                if event_type == "transcript.partial":
                    partials += 1
                if event_type == "transcript.final":
                    final_events += 1
                    segments = payload.get("segments") or []
                    if payload.get("replace_all"):
                        finals[:] = segments
                    else:
                        finals.extend(segments)

        task = asyncio.create_task(receiver())
        frame_bytes = 1920
        for offset in range(0, len(frames), frame_bytes):
            await ws.send(frames[offset:offset + frame_bytes])
            if realtime:
                await asyncio.sleep(0.1)
        await ws.send(json.dumps({"type": "audio.end"}, ensure_ascii=False))
        await asyncio.sleep(12)
        await ws.close()
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass

    if errors:
        print("ERROR:", errors[0])
        return 2
    print(f"partials={partials}")
    print(f"final_events={final_events}")
    print(f"final_segments={len(finals)}")
    speakers = sorted({item.get("speaker") for item in finals if item.get("speaker")})
    print("speakers=" + ", ".join(speakers))
    for item in finals:
        print(f"[{item.get('timestamp')}] {item.get('speaker')}: {item.get('text')}")
    if not finals:
        return 3
    if not any((item.get("speaker") or "") != "未分离" for item in finals):
        return 4
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("wav", type=Path)
    parser.add_argument("--url", default="ws://127.0.0.1:8080/api/v1/live/ws")
    parser.add_argument("--realtime", action="store_true")
    args = parser.parse_args()
    return asyncio.run(run_check(args.url, args.wav, args.realtime))


if __name__ == "__main__":
    raise SystemExit(main())
