#!/usr/bin/env bash
#
# Сценарий «восстанови решение по коду»: слой локального хранения задач описывается как ADR.
# Результат - НОВЫЙ файл docs/adr-0001-local-persistence.md в целевом репозитории.
#
# Отличие от usage-report: там ассистент СОБИРАЕТ факты, здесь - делает из них вывод.
# Решение многофайловое и многоплатформенное (общий expect плюс три actual-сборщика),
# поэтому границы разбора названы в задаче списком мест: задача без границы уходит в
# свободный аудит всего проекта.
#
# Готового ответа в RAG нет намеренно: план интеграции лежит в PLANS/, а этот каталог
# исключён из корпуса (см. build_rag_corpus в setup.sh). Реконструировать приходится
# по коду - в чём и смысл сценария.
#
# Использование (из корня репозитория):
#   bash demo/project-fs/run-adr.sh
#   JUDGE=0 bash demo/project-fs/run-adr.sh    # без судьи, аварийный выход
#   TURNS=12 bash demo/project-fs/run-adr.sh   # длиннее headless-прогон
#
set -euo pipefail

# shellcheck source=demo/project-fs/common.sh
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

require_ctt_repo
require_built
require_clean_tree
switch_to_work_branch
reset_workspace docs/adr-0001-local-persistence.md
reset_task adr

print_run_header "adr" "решение о локальном хранении -> docs/adr-0001-local-persistence.md"

# fs-reporter держит только execution (запись файла), а НЕ execution..done. На validation
# flash упорно коверкает глубокие пути в блоке '## Сделано' (слэши -> точки), а судья честно
# снимает ход по ограничению «не называй путь, которого нет в вызовах» - и до done не доходит.
# Отчёт пишется и заземляется судьёй на execution; validation/done идут на fallback без профиля:
# constraints пусты, судья немой (hasInvariants=false, без вызова LLM), поток доходит до done.
run_assistant \
  -prompt "Приступай: разберись, как в проекте устроено локальное хранение задач, и восстанови по коду принятое архитектурное решение." \
  -task adr \
  -rag "$RAG_NAME" \
  -agent provider gemini $FALLBACK_MODEL_ARG $TEMP_ARG mode system \
  -agent fs-explorer provider gemini $EXPLORER_MODEL_ARG profile fs-explorer stages clarification..planning \
  -agent fs-reporter provider gemini $REPORTER_MODEL_ARG profile fs-reporter stages execution..execution \
  $JUDGE_ARG \
  -mcpServer "$PFS $CTT_REPO --write-ext=md"

print_result_hint
