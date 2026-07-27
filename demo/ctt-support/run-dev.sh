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

# Выбор провайдера и моделей - переменными окружения (интерфейс и валидация - в demo/models.sh).
# Роли: fallback (безпрофильный), developer (весь разговор), судья.
#   PROVIDER=<gemini|ollama>       - куда ходят все агенты (дефолт gemini)
#   OLLAMA_HOST=<url>              - адрес сервера, если он не на localhost:11434
#   MODEL=<id>                     - всем разом
#   DEVELOPER_MODEL                - стадийному агенту developer
#   FALLBACK_MODEL / JUDGE_MODEL   - fallback-агенту / судье
# Пусто = дефолт клиента (gemini-2.5-flash). Под ollama модель обязательна, и её проверяют на месте
# (сервер поднят, тег спулен, тег умеет tools).
# shellcheck source=demo/models.sh
source "$REPO_ROOT/demo/models.sh"

require_supported_provider
PROVIDER_ARG="$(provider_arg)"
RAG_NAME="$(rag_name ctt-support)"   # индекс принадлежит провайдеру - строит его setup.sh
require_rag_embedder

FALLBACK_MODEL="$(model_for "${FALLBACK_MODEL:-}")"
DEVELOPER_MODEL="$(model_for "${DEVELOPER_MODEL:-}")"
JUDGE_MODEL="$(model_for "${JUDGE_MODEL:-}")"

require_supported_model "$FALLBACK_MODEL" FALLBACK_MODEL
require_supported_model "$DEVELOPER_MODEL" DEVELOPER_MODEL
require_supported_model "$JUDGE_MODEL" JUDGE_MODEL

# TEMP=<0..2> - температура воркеров (судья не затрагивается).
TEMP="${TEMP:-}"
require_valid_temp "$TEMP"

# Печатается до первого хода: по этой строке потом читают, на чём был прогон.
echo "[demo] провайдер: $(provider_label)" >&2
echo "[demo] модели: fallback=$(model_label "$FALLBACK_MODEL") developer=$(model_label "$DEVELOPER_MODEL")" >&2
echo "[demo]         судья=$(model_label "$JUDGE_MODEL")" >&2
echo "[demo] температура воркеров: $(temp_label "$TEMP")" >&2

exec "$CLI" \
  -tui \
  -prompt "Привет, нужно закрыть тикет." \
  -task dev-case \
  -rag "$RAG_NAME" \
  -agent $PROVIDER_ARG $(model_arg "$FALLBACK_MODEL") $(temp_arg "$TEMP") mode system \
  -agent developer   $PROVIDER_ARG $(model_arg "$DEVELOPER_MODEL") profile developer stages clarification..done \
  -agent rules-judge $PROVIDER_ARG $(model_arg "$JUDGE_MODEL") stages clarification..done judge \
  -mcpServer "$SUPP $DEMO_DIR --dev"
