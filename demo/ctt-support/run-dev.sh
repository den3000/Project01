#!/usr/bin/env bash
#
# Запуск консоли разработчика CTT (роль: разработчик). Отличается от run-support.sh
# профилем developer и режимом сервера --dev (включает set_ticket_status). Доступ к
# смене статусов даёт сам факт этого запуска — «кто разработчик» решает конфиг, а не
# токен в чате (в реальном деплое — аутентификация фронтенда).
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

exec "$CLI" \
  -tui \
  -prompt "Консоль разработчика. Назовите тикет и решение." \
  -task dev-case \
  -rag ctt-support \
  -agent provider gemini mode system \
  -agent developer provider gemini profile developer stages clarification..done \
  -agent judge     provider gemini stages execution..done judge \
  -mcpServer "$SUPP $DEMO_DIR --dev"
