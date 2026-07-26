#!/usr/bin/env bash
#
# Выбор моделей для обёрток демо. Подключается через `source`, самостоятельно не запускается.
#
# Зачем: модель у клиента задаётся ТОЛЬКО per-agent (глобального флага нет), поэтому прогон на другой
# модели раньше требовал правки КАЖДОГО клоза `-agent` в каждой обёртке - а забытый клоз молча уезжал
# на дефолт, и замер сравнивал разные модели. Здесь это сведено к переменным окружения.
#
# Интерфейс (пусто = дефолт клиента, gemini-2.5-flash):
#   PROVIDER=<gemini|ollama> - куда ходят ВСЕ агенты прогона                  (дефолт: gemini)
#   OLLAMA_HOST=<url>        - адрес сервера, если он не на localhost:11434
#   MODEL=<id>           - всем агентам разом: fallback + исполнители + судья
#   FALLBACK_MODEL=<id>  - только безпрофильному fallback-агенту      (дефолт: $MODEL)
#   JUDGE_MODEL=<id>     - только судье                               (дефолт: $MODEL)
#   <РОЛЬ>_MODEL=<id>    - конкретному исполнителю; список ролей - в шапке своей обёртки
#
# Примеры:
#   MODEL=gemini-2.5-flash-lite bash demo/project-fs/run-adr.sh
#   MODEL=gemini-2.5-flash-lite JUDGE_MODEL=gemini-2.5-flash bash demo/ctt-support/run-support.sh
#   REPORTER_MODEL=gemini-2.5-flash bash demo/project-fs/run-usage-report.sh
#   PROVIDER=ollama MODEL=qwen3:8b bash demo/project-fs/run-adr.sh

# Провайдер - общий на прогон, а не per-agent: клиент-то умеет по агенту, но смешанный прогон
# нечитаем как замер (непонятно, чья половина дала результат), а списки моделей у провайдеров
# не пересекаются - каждая роль требовала бы своего id. Один переключатель на прогон.
PROVIDER="${PROVIDER:-gemini}"
OLLAMA_HOST="${OLLAMA_HOST:-http://localhost:11434}"

require_supported_provider() {
  case "$PROVIDER" in
    gemini | ollama) ;;
    *)
      echo "ОШИБКА: PROVIDER='$PROVIDER' - поддержаны: gemini, ollama." >&2
      exit 1
      ;;
  esac
}

# Кусок `provider <id> [host <url>]` для клоза `-agent`. Подставляется БЕЗ кавычек - как model_arg.
provider_arg() {
  if [ "$PROVIDER" = "ollama" ]; then
    echo "provider ollama host $OLLAMA_HOST"
  else
    echo "provider gemini"
  fi
}

provider_label() {
  if [ "$PROVIDER" = "ollama" ]; then
    echo "ollama @ $OLLAMA_HOST"
  else
    echo "gemini"
  fi
}

# Идентификаторы, на которых демо реально работают — проверено прогоном, а не взято из каталога
# клиента. Два отсева:
#   - семейство 3.x несовместимо с function-calling у этого клиента (в ответе нет `thoughtSignature`,
#     следующий запрос получает `400 Function call is missing a thought_signature`), а всё демо
#     построено на вызовах инструментов;
#   - `gemini-2.5-pro` отдаёт `404 NOT_FOUND: "no longer available to new users"` — он есть в каталоге
#     клиента (`GeminiModel.Known`), но этим ключом недоступен. Держать его в списке значило бы ровно
#     тот отказ, ради которого список и заведён: переключатель выглядит рабочим и падает в бою (замер:
#     3 прогона adr сгорели, репортёр 404-ил на каждом ходу стадии execution).
SUPPORTED_MODELS="gemini-2.5-flash gemini-2.5-flash-lite"

# Проверка id ДО старта JVM. Клиент неизвестный id молча заворачивает в `Custom` и уходит с ним на
# провод, поэтому опечатка вылезает не сразу, а посреди прогона - после setup, ходов и сожжённых
# токенов. Опечатка должна стоить секунду, а не прогон.
#
# Под ollama проверять надо другое (список моделей - это то, что спулено локально), поэтому ветка
# уходит в require_ollama_model.
require_supported_model() {
  local id="${1:-}" whose="${2:-MODEL}" known
  if [ "$PROVIDER" = "ollama" ]; then
    require_ollama_model "$id" "$whose"
    return 0
  fi
  if [ -z "$id" ]; then
    return 0
  fi
  for known in $SUPPORTED_MODELS; do
    if [ "$id" = "$known" ]; then
      return 0
    fi
  done
  if [ "${ALLOW_CUSTOM_MODEL:-0}" = "1" ]; then
    echo "[demo] $whose='$id' вне списка поддержанных - продолжаю (ALLOW_CUSTOM_MODEL=1)." >&2
    return 0
  fi
  case "$id" in
    gemini-3*)
      echo "ОШИБКА: $whose='$id' - семейство 3.x несовместимо с function-calling в этом клиенте:" >&2
      echo "        в ответе нет thoughtSignature -> '400 Function call is missing a thought_signature'." >&2
      ;;
    gemini-2.5-pro)
      echo "ОШИБКА: $whose='$id' - модель отключена на стороне Gemini:" >&2
      echo "        '404 NOT_FOUND: this model is no longer available to new users'." >&2
      echo "        Она есть в каталоге клиента (GeminiModel.Known), но этим ключом недоступна." >&2
      ;;
    *)
      echo "ОШИБКА: $whose='$id' - неизвестный id. Клиент молча превратит его в Custom и упадёт" >&2
      echo "        уже на проводе, посреди прогона." >&2
      ;;
  esac
  echo "        Поддержаны: $SUPPORTED_MODELS" >&2
  echo "        Всё равно попробовать: ALLOW_CUSTOM_MODEL=1 $whose=$id bash <обёртка>" >&2
  exit 1
}

# --- ollama: предполётная проверка ------------------------------------------------------------
#
# Списка «поддержанных» тут быть не может: доступные модели - это то, что спулено на конкретной
# машине. Поэтому проверяется не имя, а три факта, каждый из которых иначе всплывает посреди
# прогона: сервер поднят, тег скачан, тег умеет вызывать инструменты.
#
# Третий - главный. Всё демо построено на инструментах, а Ollama на модель без capability `tools`
# отвечает ошибкой на КАЖДЫЙ ход: прогон не деградирует в «ассистент отвечает без инструментов», он
# просто идёт в стену - после setup и переиндексации корпуса.
require_ollama_model() {
  local id="${1:-}" whose="${2:-MODEL}"
  if [ -z "$id" ]; then
    echo "ОШИБКА: PROVIDER=ollama требует явный тег модели: MODEL=<тег> (или <РОЛЬ>_MODEL для одной роли)." >&2
    echo "        Дефолт клиента (OllamaModel.Default) - обычный chat-тег без capability tools," >&2
    echo "        и он вряд ли спулен на этой машине." >&2
    echo "        Что есть локально: ollama list" >&2
    exit 1
  fi
  require_ollama_up
  require_ollama_pulled "$id" "$whose"
  require_ollama_tools "$id" "$whose"
}

require_ollama_up() {
  if [ "${OLLAMA_UP_OK:-0}" = "1" ]; then
    return 0
  fi
  if ! curl -sf --max-time 5 "$OLLAMA_HOST/api/tags" >/dev/null 2>&1; then
    echo "ОШИБКА: Ollama не отвечает на $OLLAMA_HOST." >&2
    echo "        Подними сервер (ollama serve) или укажи адрес: OLLAMA_HOST=<url> bash <обёртка>" >&2
    exit 1
  fi
  OLLAMA_UP_OK=1
}

# Теги кэшируются: проверка зовётся на каждую роль, а ролей в обёртке до четырёх.
ollama_tags() {
  if [ -z "${OLLAMA_TAGS_CACHE:-}" ]; then
    OLLAMA_TAGS_CACHE="$(curl -s --max-time 10 "$OLLAMA_HOST/api/tags" || true)"
  fi
  printf '%s' "$OLLAMA_TAGS_CACHE"
}

# Тег без явного `:<версия>` - это `:latest`, так его и печатает сервер в /api/tags.
require_ollama_pulled() {
  local id="$1" whose="$2" tags full="$1"
  case "$id" in
    *:*) ;;
    *) full="$id:latest" ;;
  esac
  tags="$(ollama_tags)"
  case "$tags" in
    *"\"name\":\"$full\""*) return 0 ;;
  esac
  echo "ОШИБКА: $whose='$id' - тег не спулен на $OLLAMA_HOST." >&2
  echo "        Скачать: ollama pull $id" >&2
  echo "        Что есть локально: ollama list" >&2
  exit 1
}

# Capabilities вырезаются из ответа /api/show СВОИМ фрагментом, а не грепом по всему телу: в теле
# лежит и шаблон промпта, где `tools` упоминается почти у любой модели (`{{ if .Tools }}`), так что
# греп по телу целиком считал бы tools-capable кого угодно.
require_ollama_tools() {
  local id="$1" whose="$2" caps
  caps="$(curl -s --max-time 15 "$OLLAMA_HOST/api/show" -d "{\"model\":\"$id\"}" |
    tr -d ' \n' | grep -o '"capabilities":\[[^]]*\]' || true)"
  if [ -z "$caps" ]; then
    echo "[demo] $whose='$id': сервер не сообщил capabilities - проверку tools пропускаю." >&2
    return 0
  fi
  case "$caps" in
    *'"tools"'*) return 0 ;;
  esac
  if [ "${ALLOW_CUSTOM_MODEL:-0}" = "1" ]; then
    echo "[demo] $whose='$id' без capability tools - продолжаю (ALLOW_CUSTOM_MODEL=1)." >&2
    return 0
  fi
  echo "ОШИБКА: $whose='$id' - модель не умеет вызывать инструменты (capabilities: ${caps#\"capabilities\":})." >&2
  echo "        Демо целиком построено на инструментах: Ollama будет отклонять каждый ход." >&2
  echo "        Проверить тег: ollama show $id" >&2
  echo "        Всё равно попробовать: ALLOW_CUSTOM_MODEL=1 $whose=$id bash <обёртка>" >&2
  exit 1
}

# Каскад: модель роли -> общий MODEL -> дефолт клиента (пусто).
model_for() {
  if [ -n "${1:-}" ]; then
    echo "$1"
  else
    echo "${MODEL:-}"
  fi
}

# Кусок `model <id>` для клоза `-agent`, либо пусто (клоз идёт на дефолте клиента).
#
# Строкой, а не массивом, и подставляется БЕЗ кавычек: в bash 3.2 (стоковый на macOS) под `set -u`
# раскрытие ПУСТОГО массива "${A[@]}" считается unbound variable и роняет скрипт - тем же приёмом
# подставляется JUDGE_ARG.
model_arg() {
  if [ -n "${1:-}" ]; then
    echo "model $1"
  fi
}

# Что печатать в шапке прогона: пустая модель - это дефолт клиента, и назвать его надо явно, иначе
# шапка врёт при переопределении (а по шапке потом читают, на чём был замер). Под ollama пустой
# модели не бывает - require_ollama_model падает раньше.
model_label() {
  if [ -n "${1:-}" ]; then
    echo "$1"
  elif [ "$PROVIDER" = "ollama" ]; then
    echo "(не задана)"
  else
    echo "gemini-2.5-flash (дефолт)"
  fi
}

# --- температура ------------------------------------------------------------------------------
#
# TEMP=<0..2> - температура ВОРКЕРОВ: клиент читает её из primary-клоза `-agent` и кладёт в общие
# GenerationParams, которые получают fallback и все стадийные агенты. Судья НЕ затрагивается: он
# строится со своими дефолтами (buildJudges), и это правильно - мерило не должно плыть вместе с
# тем, что меряют. Пусто = дефолт провайдера (~1.0).

require_valid_temp() {
  local t="${1:-}"
  if [ -z "$t" ]; then
    return 0
  fi
  case "$t" in
    *[!0-9.]* | '' | '.' | *.*.*)
      echo "ОШИБКА: TEMP='$t' - не число (ожидается 0..2, напр. 0 или 0.2)." >&2
      exit 1
      ;;
  esac
}

# Кусок `temperature <val>` для primary-клоза `-agent`, либо пусто (дефолт провайдера).
temp_arg() {
  if [ -n "${1:-}" ]; then
    echo "temperature $1"
  fi
}

temp_label() {
  if [ -n "${1:-}" ]; then
    echo "$1"
  else
    echo "дефолт провайдера (~1.0)"
  fi
}
