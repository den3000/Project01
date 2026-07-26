#!/usr/bin/env bash
#
# Готовит инфраструктуру ассистента поддержки CTT (один раз перед демо):
#   1. собирает cliJvmApp + support-mcp (installDist);
#   2. собирает курируемый RAG-корпус (наши доки + отобранный код CTT) и индексирует его;
#   3. заливает профили support-intake / support-solve / developer и правила.
# Идемпотентно: профили/правила и корпус пересобираются заново каждый прогон.
#
# Задачи (стадия clarification) кладут в память НЕ здесь, а обёртки run-support.sh /
# run-dev.sh — они сбрасывают кейс перед каждым запуском чата.
#
# Использование (из корня репозитория Project01):
#   bash demo/ctt-support/setup.sh
#   PROVIDER=ollama bash demo/ctt-support/setup.sh   # индекс локальным эмбеддером, СВОИМ именем
# Путь к репозиторию CTT — переменная CTT_REPO (по умолчанию — соседний каталог).
#
# Индекс принадлежит провайдеру: под ollama он и строится локально, и лежит под именем с суффиксом.
# Меняешь PROVIDER — перезапусти setup, иначе индекса этого бэкенда просто нет.
#
set -euo pipefail

# Форсим UTF-8: без него (частый случай на macOS, когда Terminal не экспортирует LANG)
# bash под `set -u` спотыкается о многобайтные символы рядом с $переменными, а JVM
# читает кириллицу в -profile-аргументах в неверной кодировке.
export LC_ALL="${LC_ALL:-en_US.UTF-8}"
export LANG="${LANG:-en_US.UTF-8}"

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEMO_DIR="$REPO_ROOT/demo/ctt-support"
CLI="$REPO_ROOT/agenticHubClient/apps/cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp"

CTT_REPO="${CTT_REPO:-$HOME/Documents/AuroraProjects/CorporateTaskTracker/CorporateTaskTracker}"
CORPUS="$HOME/.project01-cli/corpus/ctt-support"

# Имя индекса и эмбеддер - по провайдеру: индекс и запрос обязаны идти одним бэкендом (см. models.sh).
# shellcheck source=demo/models.sh
source "$REPO_ROOT/demo/models.sh"

require_supported_provider
RAG_NAME="$(rag_name ctt-support)"
RAG_EMBEDDER="$(embedder_kind)"
require_rag_embedder

echo "[setup] gradle installDist (cliJvmApp + support-mcp)..."
( cd "$REPO_ROOT" && ./gradlew \
    :agenticHubClient:apps:cliJvmApp:installDist \
    :playground:support-mcp:installDist --console=plain )

echo "[setup] проверка JSON-фикстуры..."
if command -v jq >/dev/null 2>&1; then
  jq empty "$DEMO_DIR/users.json"
  jq empty "$DEMO_DIR/tickets.json"
else
  echo "  (jq не найден — пропускаю строгую проверку JSON)"
fi

echo "[setup] собираю курируемый корпус в $CORPUS..."
rm -rf "$CORPUS"
mkdir -p "$CORPUS/docs"
cp "$DEMO_DIR"/docs/*.md "$CORPUS/docs/"

copy_ctt() {  # copy_ctt <относительный путь в CTT> <назначение в CORPUS>
  local src="$CTT_REPO/$1" dst="$CORPUS/$2"
  if [ -f "$src" ]; then mkdir -p "$(dirname "$dst")"; cp "$src" "$dst"; echo "  + $2"; fi
}

if [ -d "$CTT_REPO" ]; then
  echo "[setup] подмешиваю код CTT из $CTT_REPO..."
  copy_ctt "README.md"                 "ctt/README.md"
  copy_ctt "AGENTS.md"                 "ctt/AGENTS.md"
  copy_ctt "NETWORK_CONFIG_README.md"  "ctt/NETWORK_CONFIG_README.md"
  copy_ctt "server/src/main/kotlin/ru/den/writes/code/Application.kt" "ctt/server/Application.kt"
  # Доменные модели и сетевой слой — по факту наличия (пути в CTT могут отличаться).
  while IFS= read -r f; do
    rel="${f#"$CTT_REPO"/}"
    mkdir -p "$CORPUS/ctt/$(dirname "$rel")"; cp "$f" "$CORPUS/ctt/$rel"; echo "  + ctt/$rel"
  done < <(find "$CTT_REPO/shared/src" "$CTT_REPO/shared-ui/src" -type f \
             \( -name '*.kt' \) 2>/dev/null \
             | grep -Ei 'model/|network|repository|NetworkMonitor|AppConfig' | head -20 || true)
else
  echo "[setup] ВНИМАНИЕ: CTT_REPO не найден ($CTT_REPO) — корпус только из наших доков."
fi

echo "[setup] индексирую RAG-корпус '$RAG_NAME' (embedder=$RAG_EMBEDDER)..."
"$CLI" -rag add "$RAG_NAME" src "$CORPUS" embedder "$RAG_EMBEDDER"

# Профили нарезаны по фазам разговора, а не один на всю задачу. Причина — судья:
# он аудирует ответ против `constraints` ТОГО профиля, с которым говорил ответивший
# агент, поэтому смена стадийного агента автоматически меняет и мерило. Один профиль
# на все стадии означал бы одно мерило на весь разговор — и ограничения фазы
# диагностики применялись бы к приветствию.
#
# Отсюда же форма формулировок: `constraints` — ЗАПРЕТЫ. Судья валидирует ход
# целиком (ответ + реплика пользователя + выполненные за сессию вызовы + стадия),
# но истории диалога у него нет, поэтому требование «делай X» на фазе, где X
# неуместен, он всё равно прочитает как нарушение; запрет «не утверждай X»
# безопасен всегда.
#
# Проверяемость решается тем, откуда судья узнаёт факт: имя клиента он видит в
# реплике пользователя, вызов find_user — в списке выполненных, а содержимое
# документации не видит нигде (она приходит агенту через RAG). Что написано в
# `context`, судье не достаётся вовсе — там указания агенту, а не критерий приёмки.

echo "[setup] профиль support-intake — фаза опознания (сброс + секции)..."
"$CLI" -profile clear support || true          # старый общий профиль, если остался с прошлых прогонов
"$CLI" -profile clear support-intake || true
"$CLI" -profile support-intake style   "Отвечай кратко, по-русски, дружелюбно."
"$CLI" -profile support-intake style   "Не пересказывай то, что уже сказал в предыдущих репликах — отвечай на последнее сообщение пользователя."
"$CLI" -profile support-intake constraints "Не называй email, тариф или номер тикета, которых нет в результатах выполненных вызовов инструментов."
"$CLI" -profile support-intake constraints "Не выдумывай пользователей, тикеты и их статусы: любой названный id должен приходить из результата вызова."
"$CLI" -profile support-intake context "Продукт — Corporate Task Tracker (CTT): KMP+Compose трекер задач под Android/iOS/Аврору, общий Ktor-сервер. В CTT нет авторизации и аккаунтов."
"$CLI" -profile support-intake context "Стадия clarification: спрашивай только недостающее. Имя уже названо — не переспрашивай его; проблема уже описана — не переспрашивай её; поздоровался в первом сообщении — больше не здоровайся. Никогда не повторяй свой предыдущий ответ."
"$CLI" -profile support-intake context "Как только имя названо — обязательно вызови find_user по нему. Если пользователь не найден — вежливо откажи и заверши разговор."
"$CLI" -profile support-intake context "Стадия planning: ОБЯЗАТЕЛЬНО, прежде чем предлагать любое решение, вызови search_tickets по 2-4 КОРОТКИМ ключевым словам проблемы (не длинной фразой) и list_user_tickets по customerId из find_user. Если нашёлся resolved-тикет с похожей проблемой — переиспользуй его resolution и сошлись на номер тикета."
"$CLI" -profile support-intake context "Когда стадия завершена — заканчивай ответ строкой [[stage:<next>]], выбирая одну из allowed-next стадий."

echo "[setup] профиль support-solve — фаза решения (сброс + секции)..."
"$CLI" -profile clear support-solve || true
"$CLI" -profile support-solve style   "Отвечай кратко, по-русски, дружелюбно."
"$CLI" -profile support-solve style   "Если ответ содержит шаги — оформляй нумерованным списком."
"$CLI" -profile support-solve style   "Не пересказывай то, что уже сказано в предыдущих репликах (в том числе на прошлой стадии) — продолжай с того места, где остановились."
"$CLI" -profile support-solve format  "Ссылаясь на документацию, называй файл (например network-setup.md)."
"$CLI" -profile support-solve constraints "Утверждая факт о продукте, называй источник — файл документации или номер тикета."
"$CLI" -profile support-solve constraints "Не называй конкретных дат, когда проблема пользователя может быть решена."
"$CLI" -profile support-solve constraints "Не называй конкретных номеров релизов приложения Corporate Task Tracker (CTT), в которых проблема пользователя может быть решена. Но ты можешь ссылаться на версии компонентов приложения, если они взяты из документации или тикета"
"$CLI" -profile support-solve context "Продукт — Corporate Task Tracker (CTT): KMP+Compose трекер задач под Android/iOS/Аврору, общий Ktor-сервер. В CTT нет авторизации и аккаунтов."
"$CLI" -profile support-solve context "Стадия execution: только если готового решения в тикетах нет — диагностируй по документации (RAG), ведя пользователя по шагам troubleshooting."
"$CLI" -profile support-solve context "Если решить не удалось — создай тикет через create_ticket (customerId из find_user), назови пользователю его номер и что передал разработке."
"$CLI" -profile support-solve context "Стадия validation/done: убедись, что пользователь получил решение или номер тикета, затем заверши."
"$CLI" -profile support-solve context "Когда стадия завершена — заканчивай ответ строкой [[stage:<next>]], выбирая одну из allowed-next стадий."

# Консоль разработчика остаётся одним профилем на весь диапазон: её constraints
# уже сформулированы как запреты и потому фазово-нейтральны — резать нечего.
echo "[setup] профиль developer (сброс + секции)..."
"$CLI" -profile clear developer || true
"$CLI" -profile developer style "Кратко, по-деловому, по-русски."
"$CLI" -profile developer constraints "Не сообщай о смене статуса тикета, не приведя текст решения или причину отказа."
"$CLI" -profile developer constraints "Не называй содержимое или статус тикета, которых нет в результатах выполненных вызовов инструментов."
"$CLI" -profile developer context "Ты — консоль разработчика CTT. Доступен инструмент set_ticket_status (статусы new/in_progress/resolved/wontfix)."
"$CLI" -profile developer context "Стадии: clarification — уточни какой тикет и какое решение; planning — подтверди формулировку resolution; execution — примени set_ticket_status; validation/done — подтверди результат. Продвигай стадии строкой [[stage:<next>]]."

# Глобальных правил у этого демо нет — только очистка, чтобы прогон не тащил правила,
# залитые другим демо (стор общий). Причина: правило идёт в КАЖДЫЙ ход всем агентам, а
# все инварианты здесь фазовые и уже записаны в `constraints` своих профилей. Дубль в
# двух местах ничего не усиливает: судья получает и правила, и constraints, и на одно
# утверждение может выдать два нарушения. Правила стоит заводить под инвариант, который
# верен независимо от персоны и фазы, — такого в этом демо нет.
echo "[setup] правила (сброс)..."
"$CLI" -rule clear || true

cat <<EOF

[setup] готово. Запуск:

  bash demo/ctt-support/run-support.sh     # чат поддержки (пользователь)
  bash demo/ctt-support/run-dev.sh         # консоль разработчика (смена статусов)

Обёртки сами сбрасывают кейс (задачу в стадии clarification) перед запуском.
EOF
