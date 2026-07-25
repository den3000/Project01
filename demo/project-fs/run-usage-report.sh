#!/usr/bin/env bash
#
# Сценарий «карта использования»: где в проекте определяется и потребляется адрес сервера.
# Результат - НОВЫЙ файл docs/usage-network.md в целевом репозитории.
#
# Самый предсказуемый из четырёх: ответ заведомо лежит в коде, и найти его - вопрос
# нескольких поисков. С него стоит начинать знакомство с демо.
#
# Использование (из корня репозитория):
#   bash demo/project-fs/run-usage-report.sh
#   JUDGE=0 bash demo/project-fs/run-usage-report.sh    # без судьи, аварийный выход
#   TURNS=12 bash demo/project-fs/run-usage-report.sh   # длиннее headless-прогон
#
set -euo pipefail

# shellcheck source=demo/project-fs/common.sh
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

require_ctt_repo
require_built
require_clean_tree
switch_to_work_branch
reset_workspace docs/usage-network.md
reset_task usage-report

print_run_header "usage-report" "карта сетевого слоя -> docs/usage-network.md"

run_assistant \
  -prompt "Приступай: найди, где в проекте определяется и используется адрес сервера, и собери карту этих мест." \
  -task usage-report \
  -rag "$RAG_NAME" \
  -agent provider gemini $FALLBACK_MODEL_ARG mode system \
  -agent fs-explorer provider gemini $EXPLORER_MODEL_ARG profile fs-explorer stages clarification..planning \
  -agent fs-reporter provider gemini $REPORTER_MODEL_ARG profile fs-reporter stages execution..done \
  $JUDGE_ARG \
  -mcpServer "$PFS $CTT_REPO --write-ext=md"

print_result_hint
