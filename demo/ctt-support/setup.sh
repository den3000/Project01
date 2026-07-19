#!/usr/bin/env bash
#
# Готовит инфраструктуру ассистента поддержки CTT (один раз перед демо):
#   1. собирает cliJvmApp + support-mcp (installDist);
#   2. собирает курируемый RAG-корпус (наши доки + отобранный код CTT) и индексирует его;
#   3. заливает профили support / developer и правила.
# Идемпотентно: профили/правила и корпус пересобираются заново каждый прогон.
#
# Задачи (стадия clarification) кладут в память НЕ здесь, а обёртки run-support.sh /
# run-dev.sh — они сбрасывают кейс перед каждым запуском чата.
#
# Использование (из корня репозитория Project01):
#   bash demo/ctt-support/setup.sh
# Путь к репозиторию CTT — переменная CTT_REPO (по умолчанию — соседний каталог).
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

echo "[setup] индексирую RAG-корпус 'ctt-support' (embedder=gemini)..."
"$CLI" -rag add ctt-support src "$CORPUS" embedder gemini

echo "[setup] профиль support (сброс + секции)..."
"$CLI" -profile clear support || true
"$CLI" -profile support style   "Отвечай кратко, по-русски, дружелюбно."
"$CLI" -profile support style   "Если ответ содержит шаги — оформляй нумерованным списком."
"$CLI" -profile support format  "Ссылаясь на документацию, называй файл (например network-setup.md)."
"$CLI" -profile support constraints "Помогай только зарегистрированным пользователям. Определяй пользователя через find_user по имени; если его нет в базе — вежливо откажи и не выдумывай данные."
"$CLI" -profile support constraints "Не выдумывай факты. Если ответа нет в документации и тикетах — честно скажи, что не знаешь."
"$CLI" -profile support constraints "Не обещай сроки, версии и релизы — их нет в контексте."
"$CLI" -profile support context "Продукт — Corporate Task Tracker (CTT): KMP+Compose трекер задач под Android/iOS/Аврору, общий Ktor-сервер. В CTT нет авторизации и аккаунтов."
"$CLI" -profile support context "Стадия clarification: поздоровайся, выясни имя (если не назвали) и суть проблемы. Проверь пользователя через find_user."
"$CLI" -profile support context "Стадия planning: через search_tickets проверь, не решалась ли похожая проблема раньше (любого клиента) — если есть resolved с решением, переиспользуй его. Через list_user_tickets проверь тикеты самого клиента."
"$CLI" -profile support context "Стадия execution: диагностируй по документации (RAG). Веди пользователя по шагам troubleshooting."
"$CLI" -profile support context "Если решить не удалось — создай тикет через create_ticket (customerId из find_user), назови пользователю его номер и что передал разработке."
"$CLI" -profile support context "Стадия validation/done: убедись, что пользователь получил решение или номер тикета, затем заверши."
"$CLI" -profile support context "Когда стадия завершена — заканчивай ответ строкой [[stage:<next>]], выбирая одну из allowed-next стадий."

echo "[setup] профиль developer (сброс + секции)..."
"$CLI" -profile clear developer || true
"$CLI" -profile developer style "Кратко, по-деловому, по-русски."
"$CLI" -profile developer constraints "Меняй статус тикета только с внятным resolution: либо текст решения, либо причина wontfix. Пустой resolution недопустим."
"$CLI" -profile developer constraints "Не выдумывай тикеты и статусы. Сначала подтверди тикет через get_ticket."
"$CLI" -profile developer context "Ты — консоль разработчика CTT. Доступен инструмент set_ticket_status (статусы new/in_progress/resolved/wontfix)."
"$CLI" -profile developer context "Стадии: clarification — уточни какой тикет и какое решение; planning — подтверди формулировку resolution; execution — примени set_ticket_status; validation/done — подтверди результат. Продвигай стадии строкой [[stage:<next>]]."

echo "[setup] правила (сброс + добавление)..."
"$CLI" -rule clear || true
"$CLI" -rule "Незарегистрированному пользователю (нет в find_user) — вежливый отказ, без выдумывания данных."
"$CLI" -rule "Не строй догадок о фичах, которых нет в документации. Авторизации в CTT нет — так и говори."
"$CLI" -rule "Статус тикета меняется только с непустым resolution."

cat <<EOF

[setup] готово. Запуск:

  bash demo/ctt-support/run-support.sh     # чат поддержки (пользователь)
  bash demo/ctt-support/run-dev.sh         # консоль разработчика (смена статусов)

Обёртки сами сбрасывают кейс (задачу в стадии clarification) перед запуском.
EOF
