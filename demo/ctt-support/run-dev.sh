#!/usr/bin/env bash
#
# Запуск консоли разработчика CTT (роль: разработчик). Отличается от run-support.sh
# профилем developer и режимом сервера --dev (включает set_ticket_status). Доступ к
# смене статусов даёт сам факт этого запуска — «кто разработчик» решает конфиг, а не
# токен в чате (в реальном деплое — аутентификация фронтенда).
#
# `-prompt` — первая реплика РАЗРАБОТЧИКА (Role.USER), не инструкция ассистенту:
# что консоль умеет, сказано в секции `context` профиля developer (см. setup.sh).
#
# Перед запуском сбрасывает кейс dev-case в стадию clarification.
# Требует предварительного `bash demo/ctt-support/setup.sh`.
#
set -euo pipefail

# UTF-8, иначе кириллица в -prompt уедет в JVM в неверной кодировке (см. setup.sh).
export LC_ALL="${LC_ALL:-en_US.UTF-8}"
export LANG="${LANG:-en_US.UTF-8}"

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEMO_DIR="$REPO_ROOT/demo/ctt-support"
CLI="$REPO_ROOT/agenticHubClient/apps/cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp"
SUPP="$REPO_ROOT/playground/support-mcp/build/install/support-mcp/bin/support-mcp"
TASKS="$HOME/.project01-cli/memory/tasks"

mkdir -p "$TASKS"
cp "$DEMO_DIR/dev-case-template.md" "$TASKS/dev-case.md"   # сброс кейса в clarification

# Выбор моделей - переменными окружения (интерфейс и валидация - в demo/models.sh).
# Роли: fallback (безпрофильный), developer (весь разговор), судья.
#   MODEL=<id>                     - всем разом
#   DEVELOPER_MODEL                - стадийному агенту developer
#   FALLBACK_MODEL / JUDGE_MODEL   - fallback-агенту / судье
# Пусто = дефолт клиента (gemini-2.5-flash).
# shellcheck source=demo/models.sh
source "$REPO_ROOT/demo/models.sh"

FALLBACK_MODEL="$(model_for "${FALLBACK_MODEL:-}")"
DEVELOPER_MODEL="$(model_for "${DEVELOPER_MODEL:-}")"
JUDGE_MODEL="$(model_for "${JUDGE_MODEL:-}")"

require_supported_model "$FALLBACK_MODEL" FALLBACK_MODEL
require_supported_model "$DEVELOPER_MODEL" DEVELOPER_MODEL
require_supported_model "$JUDGE_MODEL" JUDGE_MODEL

# Печатается до первого хода: по этой строке потом читают, на чём был прогон.
echo "[demo] модели: fallback=$(model_label "$FALLBACK_MODEL") developer=$(model_label "$DEVELOPER_MODEL")" >&2
echo "[demo]         судья=$(model_label "$JUDGE_MODEL")" >&2

exec "$CLI" \
  -tui \
  -prompt "Привет, нужно закрыть тикет." \
  -task dev-case \
  -rag ctt-support \
  -agent provider gemini $(model_arg "$FALLBACK_MODEL") mode system \
  -agent developer   provider gemini $(model_arg "$DEVELOPER_MODEL") profile developer stages clarification..done \
  -agent rules-judge provider gemini $(model_arg "$JUDGE_MODEL") stages clarification..done judge \
  -mcpServer "$SUPP $DEMO_DIR --dev"
