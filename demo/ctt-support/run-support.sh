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

# Выбор провайдера и моделей - переменными окружения (интерфейс и валидация - в demo/models.sh).
# Роли: fallback (безпрофильный), support-intake (опознание), support-solve (диагностика), судья.
#   PROVIDER=<gemini|ollama>       - куда ходят все агенты (дефолт gemini)
#   OLLAMA_HOST=<url>              - адрес сервера, если он не на localhost:11434
#   MODEL=<id>                     - всем разом
#   INTAKE_MODEL / SOLVE_MODEL     - конкретному стадийному агенту
#   FALLBACK_MODEL / JUDGE_MODEL   - fallback-агенту / судье
# Пусто = дефолт клиента (gemini-2.5-flash). Под ollama модель обязательна, и её проверяют на месте
# (сервер поднят, тег спулен, тег умеет tools).
# shellcheck source=demo/models.sh
source "$REPO_ROOT/demo/models.sh"

require_supported_provider
PROVIDER_ARG="$(provider_arg)"

FALLBACK_MODEL="$(model_for "${FALLBACK_MODEL:-}")"
INTAKE_MODEL="$(model_for "${INTAKE_MODEL:-}")"
SOLVE_MODEL="$(model_for "${SOLVE_MODEL:-}")"
JUDGE_MODEL="$(model_for "${JUDGE_MODEL:-}")"

require_supported_model "$FALLBACK_MODEL" FALLBACK_MODEL
require_supported_model "$INTAKE_MODEL" INTAKE_MODEL
require_supported_model "$SOLVE_MODEL" SOLVE_MODEL
require_supported_model "$JUDGE_MODEL" JUDGE_MODEL

# TEMP=<0..2> - температура воркеров (судья не затрагивается).
TEMP="${TEMP:-}"
require_valid_temp "$TEMP"

# Печатается до первого хода: по этой строке потом читают, на чём был прогон.
echo "[demo] провайдер: $(provider_label)" >&2
echo "[demo] модели: fallback=$(model_label "$FALLBACK_MODEL") intake=$(model_label "$INTAKE_MODEL")" >&2
echo "[demo]         solve=$(model_label "$SOLVE_MODEL") судья=$(model_label "$JUDGE_MODEL")" >&2
echo "[demo] температура воркеров: $(temp_label "$TEMP")" >&2

exec "$CLI" \
  -tui \
  -prompt "Здравствуйте!" \
  -task ctt-case \
  -rag ctt-support \
  -agent $PROVIDER_ARG $(model_arg "$FALLBACK_MODEL") $(temp_arg "$TEMP") mode system \
  -agent support-intake $PROVIDER_ARG $(model_arg "$INTAKE_MODEL") profile support-intake stages clarification..planning \
  -agent support-solve  $PROVIDER_ARG $(model_arg "$SOLVE_MODEL") profile support-solve  stages execution..done \
  -agent rules-judge    $PROVIDER_ARG $(model_arg "$JUDGE_MODEL") stages clarification..done judge \
  -mcpServer "$SUPP $DEMO_DIR"
