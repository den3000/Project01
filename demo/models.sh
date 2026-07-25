#!/usr/bin/env bash
#
# Выбор моделей для обёрток демо. Подключается через `source`, самостоятельно не запускается.
#
# Зачем: модель у клиента задаётся ТОЛЬКО per-agent (глобального флага нет), поэтому прогон на другой
# модели раньше требовал правки КАЖДОГО клоза `-agent` в каждой обёртке - а забытый клоз молча уезжал
# на дефолт, и замер сравнивал разные модели. Здесь это сведено к переменным окружения.
#
# Интерфейс (пусто = дефолт клиента, gemini-2.5-flash):
#   MODEL=<id>           - всем агентам разом: fallback + исполнители + судья
#   FALLBACK_MODEL=<id>  - только безпрофильному fallback-агенту      (дефолт: $MODEL)
#   JUDGE_MODEL=<id>     - только судье                               (дефолт: $MODEL)
#   <РОЛЬ>_MODEL=<id>    - конкретному исполнителю; список ролей - в шапке своей обёртки
#
# Примеры:
#   MODEL=gemini-2.5-flash-lite bash demo/project-fs/run-adr.sh
#   MODEL=gemini-2.5-flash-lite JUDGE_MODEL=gemini-2.5-flash bash demo/ctt-support/run-support.sh
#   REPORTER_MODEL=gemini-2.5-flash bash demo/project-fs/run-usage-report.sh

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
require_supported_model() {
  local id="${1:-}" whose="${2:-MODEL}" known
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
# шапка врёт при переопределении (а по шапке потом читают, на чём был замер).
model_label() {
  if [ -n "${1:-}" ]; then
    echo "$1"
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
