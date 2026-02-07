#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export JUNCO_DOCKER_PROVIDER="${JUNCO_DOCKER_PROVIDER:-colima}"

"${ROOT_DIR}/scripts/ensure-docker.sh"
"${ROOT_DIR}/scripts/docker-maintenance.sh"
docker compose -f "${ROOT_DIR}/docker-compose.yml" up --build -d
npm --prefix "${ROOT_DIR}" run test:e2e:playwright
