#!/usr/bin/env bash
#
# Запуск чата поддержки CTT (роль: пользователь). Прячет сложность многоагентного
# запуска — как это сделал бы фронтенд в реальном деплое.
#
# Разговор ведут два стадийных агента: intake опознаёт обратившегося и ищет прошлые
# решения, solve диагностирует и эскалирует. Судья один и покрывает весь диапазон:
# критерий ему задаёт профиль того агента, который отвечал, поэтому смена фазы сама
# меняет мерило — отдельный судья на фазу не нужен.
#
# `-prompt` — ПЕРВАЯ РЕПЛИКА ПОЛЬЗОВАТЕЛЯ (Role.USER), а не инструкция ассистенту:
# она печатается в транскрипте строкой `you │ …`. Инструкции агенту живут в секции
# `context` его профиля (см. setup.sh), поэтому здесь достаточно обычного приветствия —
# ассистент сам поздоровается и спросит имя с сутью проблемы.
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
  -prompt "Здравствуйте!" \
  -task ctt-case \
  -rag ctt-support \
  -agent provider gemini mode system \
  -agent support-intake provider gemini profile support-intake stages clarification..planning \
  -agent support-solve  provider gemini profile support-solve  stages execution..done \
  -agent rules-judge    provider gemini stages clarification..done judge \
  -mcpServer "$SUPP $DEMO_DIR"
