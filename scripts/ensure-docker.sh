#!/usr/bin/env bash
set -euo pipefail

docker_ready() {
  docker version --format '{{.Server.Version}}' >/dev/null 2>&1
}

if docker_ready; then
  exit 0
fi

PROVIDER="${JUNCO_DOCKER_PROVIDER:-colima}"
COLIMA_CPU="${JUNCO_COLIMA_CPU:-2}"
COLIMA_MEMORY="${JUNCO_COLIMA_MEMORY_GB:-3}"
COLIMA_DISK="${JUNCO_COLIMA_DISK_GB:-8}"

start_colima() {
  if ! command -v colima >/dev/null 2>&1; then
    return 1
  fi
  if colima status >/dev/null 2>&1; then
    docker context use colima >/dev/null 2>&1 || true
    return 0
  fi
  colima start --cpu "${COLIMA_CPU}" --memory "${COLIMA_MEMORY}" --disk "${COLIMA_DISK}" --runtime docker >/dev/null 2>&1 || return 1
  docker context use colima >/dev/null 2>&1 || true
  return 0
}

start_desktop() {
  if ! command -v open >/dev/null 2>&1; then
    echo "Docker daemon is unavailable and 'open' is not installed to start Docker Desktop." >&2
    return 1
  fi

  if ! open -ga /Applications/Docker.app >/dev/null 2>&1; then
    open -ga Docker >/dev/null 2>&1 || true
  fi
  return 0
}

if [ "${PROVIDER}" = "colima" ]; then
  if ! start_colima; then
    echo "Unable to start Colima. Install Colima or set JUNCO_DOCKER_PROVIDER=desktop." >&2
  fi
elif [ "${PROVIDER}" = "desktop" ]; then
  start_desktop || true
else
  if ! start_colima; then
    start_desktop || true
  fi
fi

SOCKET="${HOME}/.docker/run/docker.sock"
LAST_MSG=""
WAIT_SECONDS="${JUNCO_DOCKER_WAIT_SECONDS:-180}"
INTERVAL_SECONDS=2
ATTEMPTS=$((WAIT_SECONDS / INTERVAL_SECONDS))
RESET_SETTINGS="${JUNCO_DOCKER_RESET_SETTINGS:-0}"
SETTINGS_FILE="${HOME}/Library/Group Containers/group.com.docker/settings-store.json"

if [ "${ATTEMPTS}" -lt 1 ]; then
  ATTEMPTS=1
fi

reset_desktop_settings() {
  if [ "${RESET_SETTINGS}" != "1" ]; then
    return 1
  fi
  if [ ! -f "${SETTINGS_FILE}" ]; then
    return 1
  fi

  TS="$(date +%Y%m%d%H%M%S)"
  BACKUP="${SETTINGS_FILE}.bak.${TS}"
  mv "${SETTINGS_FILE}" "${BACKUP}"
  echo "Backed up Docker settings to ${BACKUP}" >&2
  if ! open -ga /Applications/Docker.app >/dev/null 2>&1; then
    open -ga Docker >/dev/null 2>&1 || true
  fi
  return 0
}

for _ in $(seq 1 "${ATTEMPTS}"); do
  if docker_ready; then
    exit 0
  fi

  if [ -S "${SOCKET}" ]; then
    MSG="$(curl --silent --max-time 2 --unix-socket "${SOCKET}" http://localhost/_ping || true)"
    if [ "${MSG}" = "OK" ]; then
      if docker_ready; then
        exit 0
      fi
    fi
    case "${MSG}" in
      *"unable to start"*)
        reset_desktop_settings || true
        ;;
    esac
    if [ -n "${MSG}" ]; then
      LAST_MSG="${MSG}"
    fi
  fi

  sleep "${INTERVAL_SECONDS}"
done

if [ -n "${LAST_MSG}" ]; then
  echo "Docker daemon did not become ready: ${LAST_MSG}" >&2
else
  echo "Docker daemon did not become ready in time." >&2
fi
if [ "${RESET_SETTINGS}" != "1" ]; then
  echo "If Docker Desktop reports settings retrieval errors, retry with JUNCO_DOCKER_RESET_SETTINGS=1." >&2
fi
exit 1
