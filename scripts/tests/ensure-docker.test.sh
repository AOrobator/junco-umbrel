#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="${ROOT_DIR}/scripts/ensure-docker.sh"

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

case_immediate_success() {
  local tmp
  tmp="$(mktemp -d)"
  local bin="${tmp}/bin"
  mkdir -p "${bin}" "${tmp}/home"

  write_stub "${bin}/docker" '#!/usr/bin/env bash
if [ "$1" = "version" ]; then
  exit 0
fi
exit 1'

  write_stub "${bin}/open" '#!/usr/bin/env bash
echo called >"${TMPDIR}/open.called"
exit 0'

  TMPDIR="${tmp}" PATH="${bin}:/usr/bin:/bin" HOME="${tmp}/home" "${SCRIPT}"
  [ ! -f "${tmp}/open.called" ] || fail "open should not run when docker is already available"
}

case_colima_start_with_small_profile() {
  local tmp
  tmp="$(mktemp -d)"
  local bin="${tmp}/bin"
  mkdir -p "${bin}" "${tmp}/home"

  write_stub "${bin}/docker" '#!/usr/bin/env bash
if [ "$1" = "context" ] && [ "$2" = "use" ]; then
  echo "$*" >> "${TMPDIR}/docker.calls"
  exit 0
fi
if [ "$1" = "version" ]; then
  if [ -f "${TMPDIR}/colima.running" ]; then
    exit 0
  fi
  exit 1
fi
exit 0'

  write_stub "${bin}/colima" '#!/usr/bin/env bash
if [ "$1" = "status" ]; then
  if [ -f "${TMPDIR}/colima.running" ]; then
    exit 0
  fi
  exit 1
fi
echo "$*" > "${TMPDIR}/colima.args"
touch "${TMPDIR}/colima.running"
exit 0'

  write_stub "${bin}/sleep" '#!/usr/bin/env bash
exit 0'

  TMPDIR="${tmp}" \
    PATH="${bin}:/usr/bin:/bin" \
    HOME="${tmp}/home" \
    JUNCO_DOCKER_PROVIDER=colima \
    JUNCO_COLIMA_CPU=1 \
    JUNCO_COLIMA_MEMORY_GB=2 \
    JUNCO_COLIMA_DISK_GB=8 \
    JUNCO_DOCKER_WAIT_SECONDS=4 \
    "${SCRIPT}"

  grep -q "start --cpu 1 --memory 2 --disk 8 --runtime docker" "${tmp}/colima.args" || fail "colima should start with configured profile"
  grep -q "context use colima" "${tmp}/docker.calls" || fail "docker context should switch to colima"
}

case_desktop_start_then_success() {
  local tmp
  tmp="$(mktemp -d)"
  local bin="${tmp}/bin"
  mkdir -p "${bin}" "${tmp}/home"

  write_stub "${bin}/docker" '#!/usr/bin/env bash
count_file="${TMPDIR}/docker.count"
count="$(cat "${count_file}" 2>/dev/null || echo 0)"
count=$((count + 1))
echo "${count}" >"${count_file}"
if [ "$1" = "version" ]; then
  if [ "${count}" -ge 2 ]; then
    exit 0
  fi
  exit 1
fi
exit 1'

  write_stub "${bin}/open" '#!/usr/bin/env bash
echo called >>"${TMPDIR}/open.called"
exit 0'

  write_stub "${bin}/sleep" '#!/usr/bin/env bash
exit 0'

  TMPDIR="${tmp}" PATH="${bin}:/usr/bin:/bin" HOME="${tmp}/home" JUNCO_DOCKER_PROVIDER=desktop JUNCO_DOCKER_WAIT_SECONDS=4 "${SCRIPT}"
  [ -f "${tmp}/open.called" ] || fail "open should be called when desktop provider starts Docker"
}

case_timeout_reports_hint() {
  local tmp
  tmp="$(mktemp -d)"
  local bin="${tmp}/bin"
  local home="${tmp}/home"
  mkdir -p "${bin}" "${home}"

  write_stub "${bin}/docker" '#!/usr/bin/env bash
if [ "$1" = "version" ]; then
  exit 1
fi
exit 1'

  write_stub "${bin}/open" '#!/usr/bin/env bash
echo called >>"${TMPDIR}/open.called"
exit 0'

  write_stub "${bin}/sleep" '#!/usr/bin/env bash
exit 0'

  write_stub "${bin}/curl" '#!/usr/bin/env bash
echo "Docker Desktop is unable to start"
exit 0'

  set +e
  local output
  output="$(
    TMPDIR="${tmp}" \
      PATH="${bin}:/usr/bin:/bin" \
      HOME="${home}" \
      JUNCO_DOCKER_PROVIDER=desktop \
      JUNCO_DOCKER_WAIT_SECONDS=4 \
      "${SCRIPT}" 2>&1
  )"
  local status=$?
  set -e

  [ "${status}" -ne 0 ] || fail "script should fail when docker never becomes healthy"
  echo "${output}" | grep -q "Docker daemon did not become ready in time." || fail "timeout message should be printed"
  echo "${output}" | grep -q "JUNCO_DOCKER_RESET_SETTINGS=1" || fail "self-heal hint should be printed"
  [ -f "${tmp}/open.called" ] || fail "open should be invoked before timeout"
}

run_case "immediate success" case_immediate_success
run_case "colima start with small profile" case_colima_start_with_small_profile
run_case "desktop starts then success" case_desktop_start_then_success
run_case "timeout reports self-heal hint" case_timeout_reports_hint

echo "PASS: ensure-docker tests"
