#!/usr/bin/env bash
#
# Общая часть обёрток демо: пути, предполётные проверки, запуск, сброс.
# Подключается через `source`, самостоятельно не запускается.
#
# Форсим UTF-8: без него (частый случай на macOS, когда Terminal не экспортирует LANG)
# bash под `set -u` спотыкается о многобайтные символы рядом с $переменными, а JVM
# читает кириллицу в -prompt/-profile-аргументах в неверной кодировке. По той же причине
# в скриптах демо нет юникодных многоточий - только ASCII.
export LC_ALL="${LC_ALL:-en_US.UTF-8}"
export LANG="${LANG:-en_US.UTF-8}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEMO_DIR="$REPO_ROOT/demo/project-fs"
CLI="$REPO_ROOT/agenticHubClient/apps/cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp"
PFS="$REPO_ROOT/playground/projectfs-mcp/build/install/projectfs-mcp/bin/projectfs-mcp"
TASKS="$HOME/.project01-cli/memory/tasks"

# Стейджинг RAG-корпуса: копия только тех файлов целевого репозитория, что описывают
# ТЕКУЩЕЕ состояние проекта (см. build_rag_corpus в setup.sh).
RAG_NAME="ctt-files"
RAG_STAGE="$HOME/.project01-cli/corpus/$RAG_NAME"

# Репозиторий, НАД которым работает ассистент (не наш собственный). Путь машинно-зависим,
# поэтому берётся по цепочке: переменная окружения -> пара обычных мест рядом с нашим
# репозиторием -> внятная инструкция. Абсолютный дефолт «как у автора» ломал бы демо на
# любой другой машине молча.
resolve_ctt_repo() {
  if [ -n "${CTT_REPO:-}" ]; then
    echo "$CTT_REPO"
    return
  fi
  local candidate
  for candidate in \
    "$REPO_ROOT/../CorporateTaskTracker" \
    "$REPO_ROOT/../../CorporateTaskTracker/CorporateTaskTracker" \
    "$HOME/Documents/AuroraProjects/CorporateTaskTracker/CorporateTaskTracker"; do
    if [ -d "$candidate/.git" ]; then
      echo "$(cd "$candidate" && pwd)"
      return
    fi
  done
  echo ""
}

CTT_REPO="$(resolve_ctt_repo)"

# Ветка, на которую ложатся правки ассистента: отдельная, чтобы не смешиваться с рабочей
# веткой репозитория. На ней же построен сброс - см. reset_workspace.
WORK_BRANCH="${WORK_BRANCH:-feature/docs-assistant}"

# Выбор моделей - переменными окружения, общий для всех демо (интерфейс и валидация - в models.sh).
# Роли этого демо: fallback (безпрофильный), fs-explorer (разведка), fs-reporter (запись), судья.
#   MODEL=<id>                            - всем разом
#   EXPLORER_MODEL / REPORTER_MODEL       - конкретному исполнителю
#   FALLBACK_MODEL / JUDGE_MODEL          - fallback-агенту / судье
# Пусто = дефолт клиента (gemini-2.5-flash). Семейство 3.x отсекается с объяснением: демо построено
# на вызовах инструментов, а с ними 3.x у этого клиента не работает.
# shellcheck source=demo/models.sh
source "$REPO_ROOT/demo/models.sh"

FALLBACK_MODEL="$(model_for "${FALLBACK_MODEL:-}")"
EXPLORER_MODEL="$(model_for "${EXPLORER_MODEL:-}")"
REPORTER_MODEL="$(model_for "${REPORTER_MODEL:-}")"
JUDGE_MODEL="$(model_for "${JUDGE_MODEL:-}")"

require_supported_model "$FALLBACK_MODEL" FALLBACK_MODEL
require_supported_model "$EXPLORER_MODEL" EXPLORER_MODEL
require_supported_model "$REPORTER_MODEL" REPORTER_MODEL
require_supported_model "$JUDGE_MODEL" JUDGE_MODEL

FALLBACK_MODEL_ARG="$(model_arg "$FALLBACK_MODEL")"
EXPLORER_MODEL_ARG="$(model_arg "$EXPLORER_MODEL")"
REPORTER_MODEL_ARG="$(model_arg "$REPORTER_MODEL")"

# Строка -mcpServer бьётся клиентом по whitespace, поэтому путь с пробелом развалится
# на два аргумента и сервер получит не тот корень.
require_no_spaces() {
  case "$1" in
    *[[:space:]]*)
      echo "ОШИБКА: путь '$1' содержит пробелы - -mcpServer их не переживёт." >&2
      exit 1
      ;;
  esac
}

require_ctt_repo() {
  if [ -z "$CTT_REPO" ]; then
    cat >&2 <<EOF
ОШИБКА: не нашёл целевой репозиторий (Corporate Task Tracker).
Искал: $REPO_ROOT/../CorporateTaskTracker
       $REPO_ROOT/../../CorporateTaskTracker/CorporateTaskTracker
       \$HOME/Documents/AuroraProjects/CorporateTaskTracker/CorporateTaskTracker
Укажи путь явно:  CTT_REPO=/путь/к/репо bash $0
EOF
    exit 1
  fi
  require_no_spaces "$CTT_REPO"
  if [ ! -d "$CTT_REPO/.git" ]; then
    echo "ОШИБКА: '$CTT_REPO' - не git-репозиторий." >&2
    exit 1
  fi
}

# Проверяются и пути к бинарям: они тоже уходят в строку -mcpServer, и пробел в любом
# из них разваливает команду ровно так же, как пробел в корне проекта.
require_built() {
  local binary
  for binary in "$CLI" "$PFS"; do
    require_no_spaces "$binary"
    if [ ! -x "$binary" ]; then
      echo "ОШИБКА: не собран '$binary'. Запусти сначала: bash demo/project-fs/setup.sh" >&2
      exit 1
    fi
  done
}

# Спрашивается ДО перехода на рабочую ветку: незакоммиченные правки переезжают вместе с
# переключением, и после него отличить их от следов прошлого прогона уже нельзя, а
# reset_workspace на рабочей ветке делает `checkout -- .` без разговоров.
require_clean_tree() {
  if [ -n "$(git -C "$CTT_REPO" status --porcelain)" ]; then
    echo "ВНИМАНИЕ: дерево '$CTT_REPO' не чистое:"
    git -C "$CTT_REPO" status --short | head -10
    echo
    echo "Эти правки переедут на $WORK_BRANCH и будут снесены сбросом прогона."
    if [ ! -t 0 ]; then
      echo "ОШИБКА: спросить некого (нет TTY) - приведи дерево в порядок и повтори." >&2
      exit 1
    fi
    read -r -p "Продолжить? [y/N] " answer
    [ "$answer" = "y" ] || exit 1
  fi
}

# Ветка ассистента: создаём при первом запуске, дальше просто переключаемся.
switch_to_work_branch() {
  local current
  current="$(git -C "$CTT_REPO" branch --show-current)"
  if [ "$current" = "$WORK_BRANCH" ]; then
    return
  fi
  if git -C "$CTT_REPO" show-ref --verify --quiet "refs/heads/$WORK_BRANCH"; then
    git -C "$CTT_REPO" switch "$WORK_BRANCH"
  else
    echo "[demo] создаю ветку $WORK_BRANCH в $CTT_REPO (была: $current)"
    git -C "$CTT_REPO" switch -c "$WORK_BRANCH"
  fi
}

# Возвращает целевой репозиторий в состояние «до прогона»: откатывает правки в
# отслеживаемых файлах и удаляет ПОИМЁННО те файлы, которые создаёт сценарий.
#
# Ни `rm -rf docs`, ни `git clean -fd` тут недопустимы, и это не осторожность вообще, а
# конкретный опыт: clean однажды снёс untracked-каталог стороннего инструмента, а в CTT
# каталог docs/ уже существует сам по себе. Обе команды не отличают созданное прогоном
# от лежавшего в репозитории до него - поэтому удаляем ровно то, что создали, а каталог
# убираем через rmdir, который на непустом каталоге честно падает.
#
# Вызывается ПЕРЕД прогоном, а не после: результат предыдущего прогона нужно успеть
# рассмотреть, а стартовать надо с чистого.
reset_workspace() {
  git -C "$CTT_REPO" checkout -- .
  local rel
  for rel in "$@"; do
    rm -f "$CTT_REPO/$rel"
    rmdir "$CTT_REPO/$(dirname "$rel")" 2>/dev/null || true
  done
}

# Судья - второй агент, проверяющий каждый ход на инварианты. JUDGE=0 выключает его.
#
# Диапазон clarification..done, то есть весь разговор. Мерило судьи - это `constraints`
# профиля ОТВЕТИВШЕГО агента, а агент выбирается по стадии, поэтому фазирование судьи
# делается нарезкой профилей (fs-explorer на разведке, fs-reporter на записи), а не
# сужением его диапазона. Прежняя попытка обойтись одним профилем на весь разговор
# требовала как раз сужения - и это лечило симптом: с одним мерилом ограничения фазы
# записи применялись к первой реплике.
#
# Нижняя граница важна отдельно: вымысел рождается на разведке. Модель пишет «Ищу
# SERVER_PORT», не вызывает инструмент и излагает результат несостоявшегося поиска;
# судья, вооружённый только с execution, ловит это, когда выдумка уже в отчёте на диске.
#
# Строкой, а не массивом: в bash 3.2 (стоковый на macOS) под `set -u` раскрытие ПУСТОГО
# массива "${A[@]}" считается unbound variable и роняет скрипт. Подставляется без кавычек.
JUDGE="${JUDGE:-1}"
JUDGE_ARG=""
if [ "$JUDGE" = "1" ]; then
  JUDGE_ARG="-agent judge provider gemini $(model_arg "$JUDGE_MODEL") stages clarification..done judge"
else
  echo "[demo] судья выключен (JUDGE=0)" >&2
fi

# Ходов, которыми headless-прогон догоняет сценарий до конца. Сценарий многостадийный:
# -prompt отрабатывает первым ходом, а дальше каждая стадия FSM просит следующего хода.
# 20, а не 8: тяжёлый сценарий (проверка десятка инвариантов, реконструкция ADR по многим
# файлам) на flash тратит несколько ходов на разведку и переписывания, и короткого фида ему
# не хватало дойти до done. Дошёл раньше - лишние 'продолжай' просто подтверждают завершение.
TURNS="${TURNS:-20}"

# Запускает клиента одним процессом - это обязательное условие, а не удобство: история
# между запусками НЕ переносится (переносится только task-файл с целью и стадией), и
# разложенный на несколько запусков сценарий даёт ложную картину.
#
# В терминале - интерактивный TUI. Из пайпа TUI не поднимется (ему нужен настоящий TTY),
# поэтому там plain-режим с автоподачей ходов: `-prompt` идёт первым ходом, дальше REPL
# читает stdin, а закрывает поток `/exit`. Подача троттлится клиентом на 16 секунд, так
# что прогон в plain-режиме идёт минуты - это не зависание.
run_assistant() {
  if [ -t 1 ]; then
    "$CLI" -tui "$@"
  else
    echo "[demo] нет TTY - plain-режим, $TURNS ходов (подача троттлится, это надолго)" >&2
    {
      local i
      for ((i = 0; i < TURNS; i++)); do echo "продолжай"; done
      echo "/exit"
    } | "$CLI" "$@"
  fi
}

# Шапка прогона: что запущено и над чем. Печатается до первого хода, потому что судья
# выключается переменной окружения и молча, а без него демо показывает совсем другое.
print_run_header() {
  cat >&2 <<EOF
------------------------------------------------------------
Сценарий:    $1
             $2
Модели:      fallback=$(model_label "$FALLBACK_MODEL")
             fs-explorer=$(model_label "$EXPLORER_MODEL")  fs-reporter=$(model_label "$REPORTER_MODEL")
             судья=$(model_label "$JUDGE_MODEL")
Судья:       $([ "$JUDGE" = "1" ] && echo "включён, clarification..done" || echo "ВЫКЛЮЧЕН (JUDGE=0)")
Репозиторий: $CTT_REPO
             ветка $(git -C "$CTT_REPO" branch --show-current)
------------------------------------------------------------
EOF
}

# Кладёт задачу в память в стадии clarification. Один активный task-файл на id глобально,
# поэтому кейс сбрасывается перед каждым запуском. Флаг -task файл НЕ создаёт, он только
# выбирает уже существующий - без этой копии стадии не будет, а значит не будет и судьи.
reset_task() {
  mkdir -p "$TASKS"
  cp "$DEMO_DIR/tasks/$1.md" "$TASKS/$1.md"
}

# Показывается после чата: чем смотреть результат и как вернуть репозиторий как было.
print_result_hint() {
  cat <<EOF

------------------------------------------------------------
Что изменил ассистент:  git -C "$CTT_REPO" diff
Что создал:             git -C "$CTT_REPO" status --short
Сброс: делается сам при следующем запуске обёртки (reset_workspace).

Руками откатывать не нужно и НЕ НАДО через 'git clean -fd': он снесёт и те
untracked-файлы, что лежали в репозитории до прогона.
------------------------------------------------------------
EOF
  git -C "$CTT_REPO" status --short
}
