#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="${JUNCO_DOCKER_MAINTENANCE_DIR:-${ROOT_DIR}/.junco/maintenance}"
STATE_FILE="${STATE_DIR}/docker-prune-epoch"
MAX_AGE_DAYS="${JUNCO_DOCKER_PRUNE_MAX_AGE_DAYS:-3}"
MIN_FREE_GB="${JUNCO_DOCKER_MIN_FREE_GB:-8}"
FORCE_PRUNE="${JUNCO_DOCKER_PRUNE_FORCE:-0}"
FORCE_VOLUMES="${JUNCO_DOCKER_PRUNE_WITH_VOLUMES:-0}"

if ! docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then
  exit 0
fi

read_free_gb() {
  if [ -n "${JUNCO_DOCKER_FREE_GB_OVERRIDE:-}" ]; then
    echo "${JUNCO_DOCKER_FREE_GB_OVERRIDE}"
    return 0
  fi
  # macOS `df -g` emits integer values, 4th column is available space.
  df -g "${ROOT_DIR}" | awk 'NR==2 {print $4}'
}

is_integer() {
  case "$1" in
    ''|*[!0-9]*) return 1 ;;
    *) return 0 ;;
  esac
}

now="$(date +%s)"
last="0"
if [ -f "${STATE_FILE}" ]; then
  raw_last="$(cat "${STATE_FILE}" 2>/dev/null || true)"
  if is_integer "${raw_last}"; then
    last="${raw_last}"
  fi
fi

if ! is_integer "${MAX_AGE_DAYS}"; then
  MAX_AGE_DAYS=3
fi
if ! is_integer "${MIN_FREE_GB}"; then
  MIN_FREE_GB=8
fi

free_gb="$(read_free_gb)"
if ! is_integer "${free_gb}"; then
  free_gb=999
fi

age_seconds=$((MAX_AGE_DAYS * 86400))
elapsed=$((now - last))
stale=0
low_disk=0
if [ "${elapsed}" -ge "${age_seconds}" ]; then
  stale=1
fi
if [ "${free_gb}" -le "${MIN_FREE_GB}" ]; then
  low_disk=1
fi

if [ "${FORCE_PRUNE}" != "1" ] && [ "${stale}" != "1" ] && [ "${low_disk}" != "1" ]; then
  exit 0
fi

mkdir -p "${STATE_DIR}"
echo "Running docker prune: stale=${stale} lowDisk=${low_disk} freeGb=${free_gb}" >&2

docker system prune -af >/dev/null 2>&1 || true
if [ "${low_disk}" = "1" ] || [ "${FORCE_VOLUMES}" = "1" ]; then
  docker system prune -af --volumes >/dev/null 2>&1 || true
fi
docker builder prune -af >/dev/null 2>&1 || true

echo "${now}" > "${STATE_FILE}"
