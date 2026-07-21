#!/usr/bin/env bash
#
# Сценарий «проверка инвариантов»: правила из AGENTS.md сверяются с фактическим кодом.
# Результат - НОВЫЙ файл docs/invariants-report.md в целевом репозитории.
#
# Тяжелее usage-report: список проверок ассистент составляет сам, и у части пунктов
# честный ответ - «не проверено». Именно этот сценарий показывает, ради чего судья:
# соблазн написать «подтверждён» без единого вызова здесь максимальный.
#
# Использование (из корня репозитория):
#   bash demo/project-fs/run-invariants.sh
#   JUDGE=0 bash demo/project-fs/run-invariants.sh    # без судьи, аварийный выход
#   TURNS=12 bash demo/project-fs/run-invariants.sh   # длиннее headless-прогон
#
set -euo pipefail

# shellcheck source=demo/project-fs/common.sh
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

require_ctt_repo
require_built
require_clean_tree
switch_to_work_branch
reset_workspace docs/invariants-report.md
reset_task invariants

print_run_header "invariants" "проверка инвариантов по коду -> docs/invariants-report.md"

run_assistant \
  -prompt "Приступай: найди в проекте список критических инвариантов и разберись, как каждый из них проверяется по коду." \
  -task invariants \
  -rag "$RAG_NAME" \
  -agent provider gemini mode system \
  -agent fs-explorer provider gemini profile fs-explorer stages clarification..planning \
  -agent fs-reporter provider gemini profile fs-reporter stages execution..done \
  $JUDGE_ARG \
  -mcpServer "$PFS $CTT_REPO --write-ext=md"

print_result_hint
