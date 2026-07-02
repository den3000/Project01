# :agenticHubClient:features:mcpClient — MCP-клиент как ToolExecutor

JVM-модуль: реализация `ToolExecutor` (из `features:llm`) через MCP-сервер-подпроцесс. На старте
`listTools`, дальше роутит tool-вызовы модели в сервер по stdio.

## Публичный API
- `McpToolClient(command)` — `: ToolExecutor`; `connect`/`listTools`/`callTool`. Реализация на
  mcp-sdk + kotlinx-io + `Dispatchers.IO` (`McpToolClient.kt`). Спавнится клиентом
  (`apps:cliJvmApp`) как подпроцесс; несколько серверов объединяет `McpToolRouter` (в `features:llm`).

## Зависимости
- `implementation(features:llm)` (порт `ToolExecutor` + tool-типы) + mcp-sdk. JVM-only.
Потребитель — `apps:cliJvmApp` (`SessionBuilders`).

## Тесты
`./gradlew :agenticHubClient:features:mcpClient:test` — `McpSchemaTest` (маппинг схемы инструмента).

## Грабли
- **Connect/listTools/callTool на `Dispatchers.IO`** — фоновый ридер stdio иначе на главной
  корутине, которую Kotter (TUI) блокирует циклом отрисовки → `callTool` не получает ответ, ход
  виснет на «… thinking», `/exit` не доходит. На IO ридер на своём потоке.
- stderr подпроцесса — диагностика (`redirectError(INHERIT)`), stdout — JSON-RPC.
