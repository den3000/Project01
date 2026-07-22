#!/usr/bin/env bash
#
# Сценарий «документация по диапазону»: что изменилось в ветке относительно базовой и как
# это отразить в доках. Единственный сценарий, который ПРАВИТ существующие файлы, а не
# создаёт новые, - результат виден как `git diff` в целевом репозитории.
#
# Единственный сценарий с двумя MCP-серверами. Разделение труда жёсткое: git-mcp отвечает
# на вопрос «что изменилось в диапазоне» (projectfs этого не может в принципе - он читает
# рабочее дерево и про историю не знает), projectfs читает и правит файлы.
#
# Именно из-за пары серверов у projectfs-инструментов инфикс _project_: без него его
# list_files столкнулся бы с одноимённым инструментом git-mcp, а роутер клиента падает на
# дубликате имени на СТАРТЕ сессии - до первого хода.
#
# Использование (из корня репозитория):
#   bash demo/project-fs/run-docs-from-diff.sh
#   JUDGE=0 bash demo/project-fs/run-docs-from-diff.sh    # без судьи, аварийный выход
#   TURNS=12 bash demo/project-fs/run-docs-from-diff.sh   # длиннее headless-прогон
#
set -euo pipefail

# shellcheck source=demo/project-fs/common.sh
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

require_ctt_repo
require_built
require_clean_tree
switch_to_work_branch

# Диапазон обязан быть: без базы git-mcp нечем задать границу задачи, а без границы
# сценарий превращается в свободный аудит документации.
BASE="$(resolve_base)"
if [ -z "$BASE" ]; then
  echo "ОШИБКА: в '$CTT_REPO' нет ни ветки main, ни master - не от чего считать диапазон." >&2
  exit 1
fi

# Новых файлов сценарий не создаёт - правит существующие, поэтому сбросу хватает checkout.
reset_workspace
reset_task docs-from-diff

print_run_header "docs-from-diff" "правка документации по диапазону $BASE...HEAD"

run_assistant \
  -prompt "Приступай: выясни, что изменилось в текущей ветке относительно базовой, и определи, какая документация это описывает." \
  -task docs-from-diff \
  -rag "$RAG_NAME" \
  -agent provider gemini mode system \
  -agent fs-surveyor provider gemini profile fs-surveyor stages clarification..planning \
  -agent fs-editor   provider gemini profile fs-editor   stages execution..done \
  $JUDGE_ARG \
  -mcpServer "$PFS $CTT_REPO --write-ext=md" \
  -mcpServer "$GIT_MCP $CTT_REPO $BASE"

print_result_hint
