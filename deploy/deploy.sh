#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

ENV_FILE="${ENV_FILE:-.env}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.test.yml}"
VALID_SERVICES="api_server asr_service ai_service funasr_runtime"
HEAVY_SERVICES="asr_service funasr_runtime"

env_value() {
  if [[ ! -f "${ENV_FILE}" ]]; then
    return
  fi
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "${ENV_FILE}" |
    tail -n 1 |
    tr -d '\r' |
    sed 's/^"//; s/"$//; s/^'\''//; s/'\''$//'
}

is_true() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

normalize_service_list() {
  local raw="${1:-}"
  local service=""
  local normalized=""

  for service in ${raw}; do
    case " ${VALID_SERVICES} " in
      *" ${service} "*) ;;
      *)
        echo "Unknown deploy service: ${service}" >&2
        echo "Allowed services: ${VALID_SERVICES}" >&2
        exit 1
        ;;
    esac
    case " ${normalized} " in
      *" ${service} "*) ;;
      *) normalized="${normalized} ${service}" ;;
    esac
  done

  printf '%s\n' "${normalized# }"
}

require_service_list() {
  local name="$1"
  local raw="$2"
  local normalized
  normalized="$(normalize_service_list "${raw}")"
  if [[ -z "${normalized}" ]]; then
    echo "${name} must be set to an exact non-empty service list." >&2
    echo "Allowed services: ${VALID_SERVICES}" >&2
    exit 1
  fi
  printf '%s\n' "${normalized}"
}

is_full_service_set() {
  local raw="${1:-}"
  local normalized
  normalized="$(normalize_service_list "${raw}")"
  [[ " ${normalized} " == *" api_server "* ]] &&
    [[ " ${normalized} " == *" asr_service "* ]] &&
    [[ " ${normalized} " == *" ai_service "* ]] &&
    [[ " ${normalized} " == *" funasr_runtime "* ]] &&
    [[ "$(wc -w <<< "${normalized}")" -eq 4 ]]
}

contains_heavy_service() {
  local raw="${1:-}"
  local service
  for service in ${raw}; do
    case " ${HEAVY_SERVICES} " in
      *" ${service} "*) return 0 ;;
    esac
  done
  return 1
}

validate_sms_config() {
  local dev_code sms_template_id login_template change_phone_template change_password_template
  dev_code="${HUIXIAO_SMS_DEV_CODE:-$(env_value HUIXIAO_SMS_DEV_CODE)}"
  if [[ -n "${dev_code}" ]]; then
    return
  fi
  sms_template_id="${HUIXIAO_SMS_TEMPLATE_ID:-$(env_value HUIXIAO_SMS_TEMPLATE_ID)}"
  login_template="${HUIXIAO_SMS_TEMPLATE_LOGIN_ID:-$(env_value HUIXIAO_SMS_TEMPLATE_LOGIN_ID)}"
  change_phone_template="${HUIXIAO_SMS_TEMPLATE_CHANGE_PHONE_ID:-$(env_value HUIXIAO_SMS_TEMPLATE_CHANGE_PHONE_ID)}"
  change_password_template="${HUIXIAO_SMS_TEMPLATE_CHANGE_PASSWORD_ID:-$(env_value HUIXIAO_SMS_TEMPLATE_CHANGE_PASSWORD_ID)}"

  local missing=()
  for required_sms_key in \
    HUIXIAO_TENCENT_SMS_SECRET_ID \
    HUIXIAO_TENCENT_SMS_SECRET_KEY \
    HUIXIAO_TENCENT_SMS_SDK_APP_ID \
    HUIXIAO_TENCENT_SMS_SIGN_NAME; do
    if [[ -z "${!required_sms_key:-$(env_value "${required_sms_key}")}" ]]; then
      missing+=("${required_sms_key}")
    fi
  done
  if [[ -z "${sms_template_id}" && ( -z "${login_template}" || -z "${change_phone_template}" || -z "${change_password_template}" ) ]]; then
    missing+=("HUIXIAO_SMS_TEMPLATE_ID")
  fi

  if (( ${#missing[@]} > 0 )); then
    echo "Missing required SMS config keys: ${missing[*]}" >&2
    echo "Set these keys in the test environment file or set HUIXIAO_SMS_DEV_CODE for a test-only fixed code." >&2
    exit 1
  fi

  if [[ -n "${sms_template_id}" ]]; then
    for scene_template in "${login_template}" "${change_phone_template}" "${change_password_template}"; do
      if [[ -n "${scene_template}" && "${scene_template}" != "${sms_template_id}" ]]; then
        echo "Refusing mismatched SMS template ids. HUIXIAO_SMS_TEMPLATE_ID must match every legacy scene template id." >&2
        exit 1
      fi
    done
    return
  fi

  if [[ "${login_template}" != "${change_phone_template}" || "${login_template}" != "${change_password_template}" ]]; then
    echo "Refusing multiple SMS template ids. Huixiao release must use one template id for login, phone change, register and reset flows." >&2
    exit 1
  fi
}

validate_llm_config() {
  local base_url api_key llm_model embedding_model minutes_enable_thinking
  base_url="${HUIXIAO_LLM_BASE_URL:-$(env_value HUIXIAO_LLM_BASE_URL)}"
  api_key="${HUIXIAO_LLM_API_KEY:-$(env_value HUIXIAO_LLM_API_KEY)}"
  llm_model="${HUIXIAO_LLM_MODEL:-$(env_value HUIXIAO_LLM_MODEL)}"
  embedding_model="${HUIXIAO_EMBEDDING_MODEL:-$(env_value HUIXIAO_EMBEDDING_MODEL)}"
  minutes_enable_thinking="${HUIXIAO_MINUTES_ENABLE_THINKING:-$(env_value HUIXIAO_MINUTES_ENABLE_THINKING)}"
  minutes_enable_thinking="${minutes_enable_thinking:-false}"

  if [[ "${base_url}" != "https://dashscope.aliyuncs.com/compatible-mode/v1" ]]; then
    echo "Refusing non-Aliyun LLM base URL: ${base_url}. Use DashScope compatible-mode endpoint." >&2
    exit 1
  fi
  if [[ -z "${api_key}" || "${api_key}" == replace-with-* || "${api_key}" == "replace-with-aliyun-dashscope-key" ]]; then
    echo "HUIXIAO_LLM_API_KEY must be the real Aliyun DashScope key from deployment secrets, not a placeholder." >&2
    exit 1
  fi
  if [[ "${llm_model}" != qwen3.7-plus ]]; then
    echo "Refusing unexpected LLM model: ${llm_model}. Current release must use qwen3.7-plus." >&2
    exit 1
  fi
  if [[ "${embedding_model}" != text-embedding-v4 ]]; then
    echo "Refusing unexpected embedding model: ${embedding_model}. Current release must use text-embedding-v4." >&2
    exit 1
  fi
  if [[ "${minutes_enable_thinking,,}" != "false" ]]; then
    echo "Refusing HUIXIAO_MINUTES_ENABLE_THINKING=${minutes_enable_thinking}. Meeting minutes must keep thinking disabled for this release." >&2
    exit 1
  fi
}

validate_alipay_config() {
  local app_id private_key public_key notify_url gateway_url aes_key payment_mode
  app_id="${HUIXIAO_ALIPAY_APP_ID:-$(env_value HUIXIAO_ALIPAY_APP_ID)}"
  private_key="${HUIXIAO_ALIPAY_PRIVATE_KEY:-$(env_value HUIXIAO_ALIPAY_PRIVATE_KEY)}"
  public_key="${HUIXIAO_ALIPAY_PUBLIC_KEY:-$(env_value HUIXIAO_ALIPAY_PUBLIC_KEY)}"
  notify_url="${HUIXIAO_ALIPAY_NOTIFY_URL:-$(env_value HUIXIAO_ALIPAY_NOTIFY_URL)}"
  gateway_url="${HUIXIAO_ALIPAY_GATEWAY_URL:-$(env_value HUIXIAO_ALIPAY_GATEWAY_URL)}"
  aes_key="${HUIXIAO_ALIPAY_AES_KEY:-$(env_value HUIXIAO_ALIPAY_AES_KEY)}"
  payment_mode="${HUIXIAO_ALIPAY_PAYMENT_MODE:-$(env_value HUIXIAO_ALIPAY_PAYMENT_MODE)}"
  payment_mode="${payment_mode:-wap}"
  gateway_url="${gateway_url:-https://openapi.alipay.com/gateway.do}"

  local missing=()
  [[ -z "${app_id}" || "${app_id}" == replace-with-* ]] && missing+=("HUIXIAO_ALIPAY_APP_ID")
  [[ -z "${private_key}" || "${private_key}" == replace-with-* ]] && missing+=("HUIXIAO_ALIPAY_PRIVATE_KEY")
  [[ -z "${public_key}" || "${public_key}" == replace-with-* ]] && missing+=("HUIXIAO_ALIPAY_PUBLIC_KEY")
  [[ -z "${notify_url}" || "${notify_url}" == replace-with-* || "${notify_url}" == *"replace-with-"* ]] && missing+=("HUIXIAO_ALIPAY_NOTIFY_URL")
  if (( ${#missing[@]} > 0 )); then
    echo "Missing required Alipay config keys for test payment: ${missing[*]}" >&2
    echo "Set single-line HUIXIAO_ALIPAY_PRIVATE_KEY and HUIXIAO_ALIPAY_PUBLIC_KEY in the test env." >&2
    exit 1
  fi

  if [[ "${gateway_url}" != "https://openapi.alipay.com/gateway.do" ]]; then
    echo "Refusing unexpected Alipay gateway URL: ${gateway_url}" >&2
    exit 1
  fi
  if [[ "${notify_url}" != http://* && "${notify_url}" != https://* ]]; then
    echo "HUIXIAO_ALIPAY_NOTIFY_URL must be an absolute HTTP(S) URL." >&2
    exit 1
  fi
  if [[ "${payment_mode}" != "wap" && "${payment_mode}" != "app" ]]; then
    echo "HUIXIAO_ALIPAY_PAYMENT_MODE must be wap or app." >&2
    exit 1
  fi
  if [[ -n "${aes_key}" ]]; then
    if ! AES_KEY="${aes_key}" python3 - <<'PY'
import base64
import os
import sys

try:
    key = base64.b64decode(os.environ["AES_KEY"].strip(), validate=True)
except Exception:
    sys.exit(1)
sys.exit(0 if len(key) in {16, 24, 32} else 1)
PY
    then
      echo "HUIXIAO_ALIPAY_AES_KEY must be a Base64 encoded 128/192/256-bit AES key." >&2
      exit 1
    fi
  fi
}

validate_apple_iap_config() {
  local enabled product_prefix bundle_id issuer_id key_id private_key environment
  enabled="${HUIXIAO_APPLE_IAP_ENABLED:-$(env_value HUIXIAO_APPLE_IAP_ENABLED)}"
  enabled="${enabled:-false}"
  if ! is_true "${enabled}"; then
    return
  fi

  product_prefix="${HUIXIAO_APPLE_PRODUCT_PREFIX:-$(env_value HUIXIAO_APPLE_PRODUCT_PREFIX)}"
  bundle_id="${HUIXIAO_APPLE_BUNDLE_ID:-$(env_value HUIXIAO_APPLE_BUNDLE_ID)}"
  issuer_id="${HUIXIAO_APPLE_ISSUER_ID:-$(env_value HUIXIAO_APPLE_ISSUER_ID)}"
  key_id="${HUIXIAO_APPLE_KEY_ID:-$(env_value HUIXIAO_APPLE_KEY_ID)}"
  private_key="${HUIXIAO_APPLE_PRIVATE_KEY:-$(env_value HUIXIAO_APPLE_PRIVATE_KEY)}"
  environment="${HUIXIAO_APPLE_ENVIRONMENT:-$(env_value HUIXIAO_APPLE_ENVIRONMENT)}"
  product_prefix="${product_prefix:-com.kunqiong.huiyi}"
  bundle_id="${bundle_id:-com.huiyi.app.ios}"
  environment="${environment:-sandbox}"

  local missing=()
  [[ -z "${product_prefix}" || "${product_prefix}" == replace-with-* ]] && missing+=("HUIXIAO_APPLE_PRODUCT_PREFIX")
  [[ -z "${bundle_id}" || "${bundle_id}" == replace-with-* ]] && missing+=("HUIXIAO_APPLE_BUNDLE_ID")
  [[ -z "${issuer_id}" || "${issuer_id}" == replace-with-* ]] && missing+=("HUIXIAO_APPLE_ISSUER_ID")
  [[ -z "${key_id}" || "${key_id}" == replace-with-* ]] && missing+=("HUIXIAO_APPLE_KEY_ID")
  [[ -z "${private_key}" || "${private_key}" == replace-with-* ]] && missing+=("HUIXIAO_APPLE_PRIVATE_KEY")
  if (( ${#missing[@]} > 0 )); then
    echo "Missing required Apple IAP config keys: ${missing[*]}" >&2
    echo "Set HUIXIAO_APPLE_PRIVATE_KEY from the local .p8 file in deployment secrets; do not commit it." >&2
    exit 1
  fi

  if [[ "${environment}" != "sandbox" && "${environment}" != "production" ]]; then
    echo "HUIXIAO_APPLE_ENVIRONMENT must be sandbox or production." >&2
    exit 1
  fi
  if [[ "${private_key}" != *"BEGIN PRIVATE KEY"* && "${private_key}" != *"BEGIN EC PRIVATE KEY"* ]]; then
    echo "HUIXIAO_APPLE_PRIVATE_KEY must contain the Apple .p8 private key text. Use escaped \\n if stored on one line." >&2
    exit 1
  fi
}

validate_no_legacy_asr_config() {
  local legacy_key_regex
  legacy_key_regex='^[[:space:]]*(HUIXIAO_DOLPHIN_HOST_PATH|HUIXIAO_FUNASR_SHIM_HOST_PATH|HUIXIAO_DOLPHIN_MODEL_HOST_PATH|HUIXIAO_ASR_ASSET_TARGET_DIR|HUIXIAO_ASR_ASSET_ARCHIVE_URL|HUIXIAO_ASR_ASSET_ARCHIVE_PATH|HUIXIAO_ENABLE_LOCAL_DIARIZATION)[[:space:]]*='
  if grep -Eq "${legacy_key_regex}" "${ENV_FILE}"; then
    echo "Refusing legacy ASR env keys in ${ENV_FILE}. Current deploy must use only funasr_runtime container model loading." >&2
    sed -nE "s/${legacy_key_regex}.*/  - \1/p" "${ENV_FILE}" | sort -u >&2
    exit 1
  fi
}

validate_shared_database_config() {
  local database_url
  database_url="${HUIXIAO_DATABASE_URL:-$(env_value HUIXIAO_DATABASE_URL)}"
  if [[ -z "${database_url}" ]]; then
    echo "HUIXIAO_DATABASE_URL is required and must point to the shared remote database." >&2
    exit 1
  fi
  if grep -Eq '^[[:space:]]*HUIXIAO_POSTGRES_' "${ENV_FILE}"; then
    echo "Refusing legacy HUIXIAO_POSTGRES_* keys for test deploy. Use the shared remote HUIXIAO_DATABASE_URL only." >&2
    exit 1
  fi

  case "${database_url}" in
    *localhost*|*127.0.0.1*|*10.0.2.2*|sqlite*|sqlite3*)
      echo "Refusing local database URL for test deploy. HUIXIAO_DATABASE_URL must be the shared remote database." >&2
      exit 1
      ;;
  esac
  case "${database_url}" in
    mysql*|mariadb*) ;;
    *)
      echo "Refusing non-shared database profile for test deploy. HUIXIAO_DATABASE_URL must match the shared remote MySQL/MariaDB profile." >&2
      exit 1
      ;;
  esac
}

validate_compose_and_release_config() {
  validate_no_legacy_asr_config
  docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" config >/dev/null
  validate_shared_database_config
  validate_llm_config
  validate_alipay_config
  validate_apple_iap_config
  validate_sms_config
}

maybe_remove_legacy_local_db_container() {
  if ! is_true "${HUIXIAO_DEPLOY_REMOVE_LEGACY_DB:-$(env_value HUIXIAO_DEPLOY_REMOVE_LEGACY_DB)}"; then
    return
  fi

  local legacy_container="deploy-postgres-1"
  if docker ps -aq --filter "name=^/${legacy_container}$" | grep -q .; then
    echo "Removing legacy local database container by explicit request: ${legacy_container}"
    docker rm -f "${legacy_container}"
  else
    echo "Legacy local database container not found: ${legacy_container}"
  fi
}

ACTION="${HUIXIAO_DEPLOY_ACTION:-$(env_value HUIXIAO_DEPLOY_ACTION)}"
ACTION="${ACTION:-validate}"

case "${ACTION}" in
  validate|status|build-create|start|stop) ;;
  *)
    echo "Invalid HUIXIAO_DEPLOY_ACTION=${ACTION}." >&2
    echo "Allowed actions: validate, status, build-create, start, stop." >&2
    exit 1
    ;;
esac

echo "Selected deploy action: ${ACTION}"

if [[ ! -f "${ENV_FILE}" && "${ACTION}" != "stop" && "${ACTION}" != "status" ]]; then
  echo "Missing ${SCRIPT_DIR}/${ENV_FILE}. Create it from .env.example and fill test secrets." >&2
  exit 1
fi

if [[ "${ACTION}" != "stop" && "${ACTION}" != "status" ]]; then
  validate_compose_and_release_config
fi

case "${ACTION}" in
  validate)
    echo "Validation-only mode: compose and release config are valid. No Docker containers were changed."
    ;;

  status)
    echo "Read-only status mode: no Docker containers will be started, stopped, rebuilt, recreated, or removed."
    if [[ -f "${ENV_FILE}" ]]; then
      docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps -a || true
    fi
    docker ps -a \
      --filter name=deploy-funasr_runtime-1 \
      --filter name=deploy-asr_service-1 \
      --filter name=deploy-ai_service-1 \
      --filter name=deploy-api_server-1 \
      --format 'container={{.Names}} status={{.Status}} image={{.Image}}' || true
    for image in deploy-api_server deploy-asr_service deploy-funasr_runtime deploy-ai_service; do
      docker image inspect --format 'image={{.RepoTags}} id={{.Id}} created={{.Created}} size={{.Size}}' "${image}" || true
    done
    ;;

  stop)
    STOP_SERVICES="$(require_service_list HUIXIAO_DEPLOY_STOP_SERVICES "${HUIXIAO_DEPLOY_STOP_SERVICES:-$(env_value HUIXIAO_DEPLOY_STOP_SERVICES)}")"
    echo "Stopping exact services only:${STOP_SERVICES}"
    for service in ${STOP_SERVICES}; do
      docker stop --time 10 "deploy-${service}-1" || true
    done
    docker ps -a \
      --filter name=deploy-funasr_runtime-1 \
      --filter name=deploy-asr_service-1 \
      --filter name=deploy-ai_service-1 \
      --filter name=deploy-api_server-1 \
      --format 'container={{.Names}} status={{.Status}} image={{.Image}}' || true
    ;;

  build-create)
    BUILD_SERVICES="$(require_service_list HUIXIAO_DEPLOY_BUILD_SERVICES "${HUIXIAO_DEPLOY_BUILD_SERVICES:-$(env_value HUIXIAO_DEPLOY_BUILD_SERVICES)}")"
    if is_full_service_set "${BUILD_SERVICES}" && ! is_true "${HUIXIAO_DEPLOY_ALLOW_FULL_REBUILD:-$(env_value HUIXIAO_DEPLOY_ALLOW_FULL_REBUILD)}"; then
      echo "Refusing full test-server rebuild without HUIXIAO_DEPLOY_ALLOW_FULL_REBUILD=true." >&2
      exit 1
    fi

    echo "Building exact services only:${BUILD_SERVICES}"
    # shellcheck disable=SC2086
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" build ${BUILD_SERVICES}

    maybe_remove_legacy_local_db_container

    echo "Recreating exact containers without starting dependencies:${BUILD_SERVICES}"
    # shellcheck disable=SC2086
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" up --no-start --no-build --force-recreate --no-deps ${BUILD_SERVICES}
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps -a
    echo "Build/create-only mode finished. No services were started."
    ;;

  start)
    UP_SERVICES="$(require_service_list HUIXIAO_DEPLOY_UP_SERVICES "${HUIXIAO_DEPLOY_UP_SERVICES:-$(env_value HUIXIAO_DEPLOY_UP_SERVICES)}")"
    if contains_heavy_service "${UP_SERVICES}" && ! is_true "${HUIXIAO_DEPLOY_ALLOW_HEAVY_START:-$(env_value HUIXIAO_DEPLOY_ALLOW_HEAVY_START)}"; then
      echo "Refusing to start ASR/FunASR without HUIXIAO_DEPLOY_ALLOW_HEAVY_START=true." >&2
      exit 1
    fi
    if is_full_service_set "${UP_SERVICES}" && ! is_true "${HUIXIAO_DEPLOY_ALLOW_FULL_START:-$(env_value HUIXIAO_DEPLOY_ALLOW_FULL_START)}"; then
      echo "Refusing to start all Huixiao services without HUIXIAO_DEPLOY_ALLOW_FULL_START=true." >&2
      exit 1
    fi

    validate_sms_config
    echo "Starting existing exact containers only. No build, create, or recreate will run:${UP_SERVICES}"
    # shellcheck disable=SC2086
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" start ${UP_SERVICES}

    if [[ " ${UP_SERVICES} " == *" api_server "* ]]; then
      env_api_port="$(
        sed -n 's/^[[:space:]]*HUIXIAO_TEST_API_PORT[[:space:]]*=[[:space:]]*//p' "${ENV_FILE}" |
          tail -n 1 |
          tr -d '\r'
      )"
      API_PORT="${HUIXIAO_TEST_API_PORT:-${env_api_port:-28080}}"
      for _ in {1..60}; do
        if curl -fsS "http://127.0.0.1:${API_PORT}/api/v1/health" >/dev/null; then
          echo "API service is healthy on port ${API_PORT}."
          break
        fi
        sleep 2
      done
    fi

    if contains_heavy_service "${UP_SERVICES}"; then
      bash ./check_services.sh
    fi
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps
    echo "Start action finished."
    ;;
esac
