# CTT Support Assistant — фикстура и корпус

Демо для сборки ассистента поддержки продукта Corporate Task Tracker (CTT) на базе `cliJvmApp`.

## Что здесь

- `docs/` — 8 md-файлов, из которых собирается RAG-корпус про продукт:
  `overview`, `installation`, `network-setup`, `tasks`, `api`, `auth-and-privacy`, `known-issues`,
  `troubleshooting`. Пишутся с расчётом на 5-8 реальных вопросов пользователей, а не по мотивам
  внутренних заметок разработки.
- `users.json` / `tickets.json` — фикстура для MCP-сервера `support-mcp`. Пользователей 5, тикетов
  6; тикеты покрывают ключевые сценарии (Аврора, сеть, авторизация, PUT-эндпоинт, UI-квирк, сборка
  RPM).
- `tasks/` — заготовки файлов задач (`~/.project01-cli/memory/tasks/TICKET-*.md`), у каждого `Goal =
  subject тикета`. Кладутся в память, когда `setup.sh` копирует их в `~/.project01-cli/memory/tasks/`.
- `setup.sh` — собирает CLI + support-mcp, индексирует корпус (embedder=gemini), заливает профиль
  `support` и правила, копирует заготовки задач. Идемпотентно.

## Ключи

Скрипт использует `-agent provider gemini` / `embedder gemini`. Ключ `GEMINI_API_KEY` должен быть в
`local.properties` (там же, где `sdk.dir`) или в переменной окружения — env перекрывает
`BuildKonfig`.

## Запуск

```bash
# из корня репозитория Project01
bash demo/ctt-support/setup.sh

# затем — команда, которую setup.sh печатает в конце (пример):
CLI=./agenticHubClient/apps/cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp
SUPP=./playground/support-mcp/build/install/support-mcp/bin/support-mcp
DEMO=./demo/ctt-support

"$CLI" \
  -prompt "Здравствуйте, чем могу помочь?" \
  -task TICKET-4412 \
  -rag ctt-support \
  -agent provider gemini profile support mode system \
  -mcpServer "$SUPP $DEMO"
```

`-task TICKET-4412` кладёт id активного тикета в SYSTEM (`[Current Task] (TICKET-4412)` + `Goal`);
профиль `support` обязывает ассистента вызвать `get_ticket` первым же ходом и подтянуть клиента
через `get_user`. RAG-корпус `ctt-support` инжектится в SYSTEM выше плана истории; агент отвечает,
опираясь одновременно на живые данные тикета/клиента и на подтянутые чанки документации.

Поменяйте id тикета на другой — 4415, 4418, 4420, 4423 или 4425. Уберите `-task` целиком, чтобы
получить ассистента без привязки к тикету (только FAQ по документации).

Для видео удобнее без `-tui` (весь вывод в один поток stderr+stdout); с `-tui` — визуально красивее,
но требует настоящий TTY (несовместимо со скринкастером, читающим stdout).
