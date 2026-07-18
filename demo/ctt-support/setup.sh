#!/usr/bin/env bash
#
# Собирает CLI + support-mcp, индексирует RAG-корпус, раскладывает файлы памяти
# (профиль support, правила, заготовки задач по тикетам). Идемпотентно: чистит
# старые версии профиля/правил/индекса перед перезаливом. Задачи (tasks/*.md)
# только копирует — уже созданный тикет из этой фикстуры не затирает соседние.
#
# Использование (из корня репозитория Project01):
#   bash demo/ctt-support/setup.sh
#
# После setup — команду запуска ассистента печатает setup.sh в конце.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEMO_DIR="$REPO_ROOT/demo/ctt-support"

CLI="$REPO_ROOT/agenticHubClient/apps/cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp"
SUPP="$REPO_ROOT/playground/support-mcp/build/install/support-mcp/bin/support-mcp"

MEMORY_ROOT="$HOME/.project01-cli/memory"

echo "[setup] gradle installDist (cliJvmApp + support-mcp)…"
(
  cd "$REPO_ROOT"
  ./gradlew \
    :agenticHubClient:apps:cliJvmApp:installDist \
    :playground:support-mcp:installDist \
    --console=plain
)

echo "[setup] проверка JSON-фикстуры…"
if command -v jq >/dev/null 2>&1; then
  jq empty "$DEMO_DIR/users.json"
  jq empty "$DEMO_DIR/tickets.json"
else
  echo "  (jq не найден — пропускаю строгую проверку JSON)"
fi

echo "[setup] индексирую RAG-корпус 'ctt-support' (embedder=gemini, docs → ~/.project01-cli/rag)…"
"$CLI" -rag add ctt-support src "$DEMO_DIR/docs" embedder gemini

echo "[setup] переливаю профиль 'support' (clear + секции)…"
"$CLI" -profile support clear || true

# --- Style / Format ---
"$CLI" -profile support style   "Отвечай кратко и по-русски."
"$CLI" -profile support style   "Пиши списком, если ответ содержит несколько шагов."
"$CLI" -profile support format  "Если ссылаешься на документацию, называй файл (например, network-setup.md)."

# --- Constraints ---
"$CLI" -profile support constraints "Не выдумывай факты. Если ответа нет в документации и в тикете — так и скажи."
"$CLI" -profile support constraints "Не обещай сроки, версии, релизы и деньги — этого нет в контексте."
"$CLI" -profile support constraints "Ссылайся на реальный текст из документации, не пересказывай своими домыслами."

# --- Context ---
"$CLI" -profile support context "Продукт — Corporate Task Tracker (CTT), KMP+Compose трекер задач под Android/iOS/Аврору."
"$CLI" -profile support context "Если в SYSTEM есть блок [Current Task] с id тикета — первым же ходом вызови get_ticket с этим id."
"$CLI" -profile support context "После get_ticket подтяни клиента через get_user по customerId из тикета."
"$CLI" -profile support context "Если вопрос про продукт — сначала ищи ответ в подтянутых RAG-чанках, потом уже отвечай."

echo "[setup] переливаю правила (clear + add)…"
"$CLI" -rule clear || true
"$CLI" -rule "Не строй догадок про фичи, которых нет в документации. Аутентификации в CTT нет — так и говори, если про неё спросят."
"$CLI" -rule "Если пользователь описывает проблему, пройди диагностический чек-лист из troubleshooting.md по порядку — не прыгай сразу к выводу."

echo "[setup] копирую заготовки задач в $MEMORY_ROOT/tasks/…"
mkdir -p "$MEMORY_ROOT/tasks"
cp -v "$DEMO_DIR"/tasks/*.md "$MEMORY_ROOT/tasks/"

cat <<EOF

[setup] готово. Запуск ассистента:

  $CLI \\
    -prompt "Здравствуйте, чем могу помочь?" \\
    -task TICKET-4412 \\
    -rag ctt-support \\
    -agent provider gemini profile support mode system \\
    -mcpServer "$SUPP $DEMO_DIR"

Поменяйте TICKET-4412 на любой из: 4415, 4418, 4420, 4423, 4425.
Уберите -task целиком, чтобы протестировать чат без тикета (только FAQ по RAG).
EOF
