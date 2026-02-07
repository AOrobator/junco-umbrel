#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="${ROOT_DIR}/scripts/docker-maintenance.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

run_case() {
  local name="$1"
  shift
  echo "CASE: ${name}"
  "$@" || fail "${name}"
}

write_stub() {
  local path="$1"
  shift
  cat >"${path}" <<EOF
$*
EOF
  chmod +x "${path}"
}

case_skip_when_recent_and_healthy() {
  local tmp
  tmp="$(mktemp -d)"
  local bin="${tmp}/bin"
  local state="${tmp}/state"
  mkdir -p "${bin}" "${state}"
  date +%s > "${state}/docker-prune-epoch"

  write_stub "${bin}/docker" '#!/usr/bin/env bash
if [ "$1" = "version" ]; then
  exit 0
fi
echo "$*" >> "${TMPDIR}/docker.calls"
exit 0'

  TMPDIR="${tmp}" \
    PATH="${bin}:/usr/bin:/bin" \
    JUNCO_DOCKER_MAINTENANCE_DIR="${state}" \
    JUNCO_DOCKER_PRUNE_MAX_AGE_DAYS=7 \
    JUNCO_DOCKER_FREE_GB_OVERRIDE=50 \
    "${SCRIPT}"

  [ ! -f "${tmp}/docker.calls" ] || fail "no prune should run when cache is fresh and disk is healthy"
}

case_prune_when_stale() {
  local tmp
  tmp="$(mktemp -d)"
  local bin="${tmp}/bin"
  local state="${tmp}/state"
  mkdir -p "${bin}" "${state}"

  write_stub "${bin}/docker" '#!/usr/bin/env bash
if [ "$1" = "version" ]; then
  exit 0
fi
echo "$*" >> "${TMPDIR}/docker.calls"
exit 0'

  TMPDIR="${tmp}" \
    PATH="${bin}:/usr/bin:/bin" \
    JUNCO_DOCKER_MAINTENANCE_DIR="${state}" \
    JUNCO_DOCKER_PRUNE_MAX_AGE_DAYS=0 \
    JUNCO_DOCKER_FREE_GB_OVERRIDE=50 \
    "${SCRIPT}"

  grep -q "system prune -af" "${tmp}/docker.calls" || fail "stale state should trigger docker system prune"
  grep -q "builder prune -af" "${tmp}/docker.calls" || fail "stale state should trigger docker builder prune"
  if grep -q -- "--volumes" "${tmp}/docker.calls"; then
    fail "volumes prune should not run on healthy disk"
  fi
}

case_low_disk_prunes_volumes() {
  local tmp
  tmp="$(mktemp -d)"
  local bin="${tmp}/bin"
  local state="${tmp}/state"
  mkdir -p "${bin}" "${state}"
  date +%s > "${state}/docker-prune-epoch"

  write_stub "${bin}/docker" '#!/usr/bin/env bash
if [ "$1" = "version" ]; then
  exit 0
fi
echo "$*" >> "${TMPDIR}/docker.calls"
exit 0'

  TMPDIR="${tmp}" \
    PATH="${bin}:/usr/bin:/bin" \
    JUNCO_DOCKER_MAINTENANCE_DIR="${state}" \
    JUNCO_DOCKER_PRUNE_MAX_AGE_DAYS=7 \
    JUNCO_DOCKER_MIN_FREE_GB=8 \
    JUNCO_DOCKER_FREE_GB_OVERRIDE=5 \
    "${SCRIPT}"

  grep -q "system prune -af --volumes" "${tmp}/docker.calls" || fail "low disk should trigger volumes prune"
}

run_case "skip when recent and healthy" case_skip_when_recent_and_healthy
run_case "prune when stale" case_prune_when_stale
run_case "low disk prunes volumes" case_low_disk_prunes_volumes

echo "PASS: docker-maintenance tests"
