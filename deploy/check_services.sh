#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

ENV_FILE="${ENV_FILE:-.env}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.test.yml}"

env_value() {
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "${ENV_FILE}" |
    tail -n 1 |
    tr -d '\r' |
    sed 's/^"//; s/"$//; s/^'\''//; s/'\''$//'
}

env_api_port="$(env_value HUIXIAO_TEST_API_PORT)"
API_PORT="${HUIXIAO_TEST_API_PORT:-${env_api_port:-28080}}"
env_asr_warmup="$(env_value HUIXIAO_ASR_WARMUP_ON_DEPLOY)"
ASR_WARMUP_ON_DEPLOY="${HUIXIAO_ASR_WARMUP_ON_DEPLOY:-${env_asr_warmup:-false}}"

for _ in {1..60}; do
  if curl -fsS "http://127.0.0.1:${API_PORT}/api/v1/health" >/dev/null; then
    break
  fi
  sleep 2
done

curl -fsS "http://127.0.0.1:${API_PORT}/api/v1/health" >/dev/null

docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" exec -T asr_service python - <<'PY'
from importlib import metadata

for distribution_name in ("torch", "torchaudio", "modelscope"):
    metadata.version(distribution_name)

print("ASR Python dependency package checks passed.")
PY

ASR_WARMUP_ON_DEPLOY="${ASR_WARMUP_ON_DEPLOY}" \
docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" exec -T asr_service python - <<'PY'
import json
import os
import time
import urllib.request

deadline = time.time() + 180
last_error = None
while time.time() < deadline:
    try:
        with urllib.request.urlopen("http://127.0.0.1:8081/health", timeout=10) as response:
            data = json.loads(response.read().decode("utf-8"))
        assert data["status"] == "ok", data
        assert data["offline_model_owner"] == "aliyun_dashscope", data
        assert "Aliyun" in data["file_engine"], data
        break
    except Exception as exc:
        last_error = exc
        time.sleep(3)
else:
    raise RuntimeError(f"ASR service health check did not pass: {last_error}")

if os.environ.get("ASR_WARMUP_ON_DEPLOY", "false").lower() in {"1", "true", "yes"}:
    request = urllib.request.Request("http://127.0.0.1:8081/admin/warmup", data=b"{}", method="POST")
    with urllib.request.urlopen(request, timeout=180) as response:
        data = json.loads(response.read().decode("utf-8"))
    assert data["status"] == "ok", data
    assert data["offline_model_loaded"] is True, data
PY

docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" exec -T ai_service python - <<'PY'
import json
import time
import urllib.request

deadline = time.time() + 180
last_error = None
while time.time() < deadline:
    try:
        with urllib.request.urlopen("http://127.0.0.1:8082/health", timeout=10) as response:
            data = json.loads(response.read().decode("utf-8"))
        assert data["status"] == "ok", data
        break
    except Exception as exc:
        last_error = exc
        time.sleep(3)
else:
    raise RuntimeError(f"AI service health check did not pass: {last_error}")
PY

echo "Service checks passed."
