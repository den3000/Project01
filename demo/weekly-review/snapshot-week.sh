#!/usr/bin/env bash
#
# Снимок плана недели — запускать в НАЧАЛЕ недели. Агент (один ход) вызывает ticktick
# snapshot_week, который сохраняет на диск множество запланированных задач; в конце недели
# review-week.sh сверит по нему «что сделано» (официальный API не отдаёт выполненные).
#
# Использование:
#   bash demo/weekly-review/snapshot-week.sh <FROM YYYY-MM-DD> <TO YYYY-MM-DD> <LABEL>
#     FROM — включительно, TO — ИСКЛЮЧИТЕЛЬНО (день после последнего), LABEL — метка (напр. 2026-W29).
#
# Требует `bash demo/weekly-review/setup-weekly.sh` (сборка бинарей + профиль).
#
set -euo pipefail
export LC_ALL="${LC_ALL:-en_US.UTF-8}"
export LANG="${LANG:-en_US.UTF-8}"

FROM="${1:?FROM date (YYYY-MM-DD) required}"
TO="${2:?TO date (YYYY-MM-DD, exclusive) required}"
LABEL="${3:?LABEL (e.g. 2026-W29) required}"

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CLI="$REPO_ROOT/agenticHubClient/apps/cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp"
TICKTICK="$REPO_ROOT/playground/ticktick-mcp/build/install/ticktick-mcp/bin/ticktick-mcp"

: "${TICKTICK_ACCESS_TOKEN:?set TICKTICK_ACCESS_TOKEN (see demo/weekly-review/README.md)}"

# Один ход: -prompt = реплика пользователя; < /dev/null → после хода EOF → сессия завершается.
exec "$CLI" \
  -prompt "Сними снапшот плана недели: вызови snapshot_week с from=$FROM, to=$TO, label=$LABEL." \
  -agent provider gemini mode system \
  -mcpServer "$TICKTICK" \
  < /dev/null
