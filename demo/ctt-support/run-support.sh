#!/usr/bin/env bash
#
# Запуск чата поддержки CTT (роль: пользователь). Прячет сложность многоагентного
# запуска — как это сделал бы фронтенд в реальном деплое.
#
# Перед запуском сбрасывает кейс: кладёт задачу ctt-case в стадию clarification.
# Требует предварительного `bash demo/ctt-support/setup.sh` (индекс + профили).
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
cp "$DEMO_DIR/case-template.md" "$TASKS/ctt-case.md"   # сброс кейса в clarification

exec "$CLI" \
  -tui \
  -prompt "Здравствуйте! Опишите, пожалуйста, вашу проблему (и как вас зовут)." \
  -task ctt-case \
  -rag ctt-support \
  -agent provider gemini mode system \
  -agent support provider gemini profile support stages clarification..done \
  -agent judge   provider gemini stages execution..done judge \
  -mcpServer "$SUPP $DEMO_DIR"
