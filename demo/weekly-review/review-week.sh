#!/usr/bin/env bash
#
# Недельный разбор продуктивности — запускать в КОНЦЕ недели. Агент (один ход) сверяет план и
# факт через ticktick review_week, разбирает время через atimelogger time_by_activity и на основе
# двух датасетов даёт рекомендации. Требует снапшота из snapshot-week.sh (та же LABEL).
#
# Использование:
#   bash demo/weekly-review/review-week.sh <FROM YYYY-MM-DD> <TO YYYY-MM-DD> <LABEL>
#     FROM/TO — тот же полуоткрытый диапазон, что и у снапшота; LABEL — та же метка.
#
# Требует `bash demo/weekly-review/setup-weekly.sh` (сборка бинарей + профиль weekly).
#
set -euo pipefail
export LC_ALL="${LC_ALL:-en_US.UTF-8}"
export LANG="${LANG:-en_US.UTF-8}"

FROM="${1:?FROM date (YYYY-MM-DD) required}"
TO="${2:?TO date (YYYY-MM-DD, exclusive) required}"
LABEL="${3:?LABEL (same as snapshot, e.g. 2026-W29) required}"

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CLI="$REPO_ROOT/agenticHubClient/apps/cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp"
ATIMELOGGER="$REPO_ROOT/playground/atimelogger-mcp/build/install/atimelogger-mcp/bin/atimelogger-mcp"
TICKTICK="$REPO_ROOT/playground/ticktick-mcp/build/install/ticktick-mcp/bin/ticktick-mcp"

: "${TICKTICK_ACCESS_TOKEN:?set TICKTICK_ACCESS_TOKEN (see demo/weekly-review/README.md)}"
: "${ATIMELOGGER_USERNAME:?set ATIMELOGGER_USERNAME (see demo/weekly-review/README.md)}"
: "${ATIMELOGGER_PASSWORD:?set ATIMELOGGER_PASSWORD (see demo/weekly-review/README.md)}"

# Один ход агента с профилем weekly: он сам вызывает оба инструмента и сводит разбор.
# < /dev/null → после хода EOF → сессия завершается (headless, без TTY).
exec "$CLI" \
  -prompt "Разбери мою неделю $LABEL (с $FROM по $TO): сверь план и факт через review_week (label=$LABEL), разбери время через time_by_activity (from=$FROM, to=$TO) и дай рекомендации по продуктивности." \
  -agent provider gemini mode system profile weekly \
  -mcpServer "$ATIMELOGGER" \
  -mcpServer "$TICKTICK" \
  < /dev/null
