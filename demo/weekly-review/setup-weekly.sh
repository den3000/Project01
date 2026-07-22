#!/usr/bin/env bash
#
# Готовит окружение недельного разбора продуктивности (один раз перед демо):
#   1. собирает cliJvmApp + atimelogger-mcp + ticktick-mcp (installDist);
#   2. заливает системный профиль weekly (персона аналитика + формат разбора).
#
# Креды НЕ здесь — их серверы читают из окружения при запуске (см. README.md):
#   ATIMELOGGER_USERNAME / ATIMELOGGER_PASSWORD, TICKTICK_ACCESS_TOKEN, GEMINI_API_KEY.
#
# Использование (из корня репозитория):
#   bash demo/weekly-review/setup-weekly.sh
#
set -euo pipefail

# UTF-8: иначе кириллица в -profile-аргументах уедет в JVM в неверной кодировке.
export LC_ALL="${LC_ALL:-en_US.UTF-8}"
export LANG="${LANG:-en_US.UTF-8}"

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CLI="$REPO_ROOT/agenticHubClient/apps/cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp"

echo "[setup] gradle installDist (cliJvmApp + atimelogger-mcp + ticktick-mcp)..."
( cd "$REPO_ROOT" && ./gradlew \
    :agenticHubClient:apps:cliJvmApp:installDist \
    :playground:atimelogger-mcp:installDist \
    :playground:ticktick-mcp:installDist --console=plain )

echo "[setup] профиль weekly (сброс + секции)..."
"$CLI" -profile clear weekly || true
"$CLI" -profile weekly style "Пиши по-русски, кратко и по делу."
"$CLI" -profile weekly format "Структура ответа: (1) План против факта; (2) Куда ушло время; (3) 3–5 конкретных рекомендаций по продуктивности."
"$CLI" -profile weekly constraints "Не выдумывай задачи, статусы и числа: любые факты бери только из результатов вызовов review_week и time_by_activity."
"$CLI" -profile weekly constraints "Не называй задачу выполненной, если review_week не пометил её done или gone."
"$CLI" -profile weekly context "Ты — ассистент недельного разбора продуктивности. Доступны инструменты: review_week (план недели против факта из TickTick) и time_by_activity (время по типам активностей из aTimeLogger)."
"$CLI" -profile weekly context "Разбирая неделю: вызови review_week по метке недели и time_by_activity по тем же датам, затем сопоставь план, факт и распределение времени и дай практичные рекомендации."
"$CLI" -profile weekly context "Учитывай ограничение: задачи, созданные и закрытые внутри недели, в TickTick-части не видны, а gone — «скорее всего сделано». Не выдавай это за точную статистику."

cat <<EOF

[setup] готово. Дальше (даты: from включительно, to исключительно — день ПОСЛЕ последнего):

  export ATIMELOGGER_USERNAME=... ATIMELOGGER_PASSWORD=...   # aTimeLogger (HTTP Basic)
  export TICKTICK_ACCESS_TOKEN=...                           # TickTick OAuth2 (см. README)
  export GEMINI_API_KEY=...                                  # если не в local.properties

  bash demo/weekly-review/snapshot-week.sh 2026-07-13 2026-07-20 2026-W29   # в НАЧАЛЕ недели
  bash demo/weekly-review/review-week.sh  2026-07-13 2026-07-20 2026-W29    # в КОНЦЕ недели
EOF
