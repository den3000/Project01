#!/usr/bin/env bash
#
# Недельный разбор продуктивности. Агент (один ход) берёт ПЛАН из TickTick (week_plan —
# запланированные часы по активностям) и ФАКТ из aTimeLogger (time_by_activity — реально
# потраченные часы), сравнивает их по активностям и даёт рекомендации.
#
# Использование:
#   bash demo/weekly-review/review-week.sh <FROM YYYY-MM-DD> <TO YYYY-MM-DD>
#     FROM — включительно, TO — ИСКЛЮЧИТЕЛЬНО (день после последнего дня недели).
#
# Требует `bash demo/weekly-review/setup-weekly.sh` (сборка бинарей + профиль weekly).
#
# Доступы берутся из окружения; если их там нет — скрипт сам загрузит `.env` рядом с собой
# или `~/.project01-weekly.env`. Явно выставленное окружение (ручной `source`) имеет приоритет.
#
set -euo pipefail
export LC_ALL="${LC_ALL:-en_US.UTF-8}"
export LANG="${LANG:-en_US.UTF-8}"

# Авто-подхват доступов: если токен ещё не в окружении — загрузить локальный env-файл
# (.env рядом со скриптом → ~/.project01-weekly.env). Уже выставленное окружение не трогаем.
if [ -z "${TICKTICK_ACCESS_TOKEN:-}" ]; then
  for env_file in "$(dirname "$0")/.env" "$HOME/.project01-weekly.env"; do
    if [ -f "$env_file" ]; then
      set -a; . "$env_file"; set +a
      break
    fi
  done
fi

FROM="${1:?FROM date (YYYY-MM-DD) required}"
TO="${2:?TO date (YYYY-MM-DD, exclusive) required}"

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CLI="$REPO_ROOT/agenticHubClient/apps/cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp"
ATIMELOGGER="$REPO_ROOT/playground/atimelogger-mcp/build/install/atimelogger-mcp/bin/atimelogger-mcp"
TICKTICK="$REPO_ROOT/playground/ticktick-mcp/build/install/ticktick-mcp/bin/ticktick-mcp"

: "${TICKTICK_ACCESS_TOKEN:?set TICKTICK_ACCESS_TOKEN (see demo/weekly-review/README.md)}"
: "${ATIMELOGGER_USERNAME:?set ATIMELOGGER_USERNAME (see demo/weekly-review/README.md)}"
: "${ATIMELOGGER_PASSWORD:?set ATIMELOGGER_PASSWORD (see demo/weekly-review/README.md)}"

# Один ход агента с профилем weekly: он сам вызывает week_plan и time_by_activity и сводит разбор.
# < /dev/null → после хода EOF → сессия завершается (headless, без TTY).
exec "$CLI" \
  -prompt "Разбери мою неделю с $FROM по $TO: план возьми через week_plan (from=$FROM, to=$TO), факт через time_by_activity (from=$FROM, to=$TO), сравни запланированные часы по активностям с реально потраченными и дай рекомендации." \
  -agent provider gemini mode system profile weekly \
  -mcpServer "$ATIMELOGGER" \
  -mcpServer "$TICKTICK" \
  < /dev/null
