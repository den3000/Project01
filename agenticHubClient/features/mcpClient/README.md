# :agenticHubClient:features:mcpClient — MCP-клиент как ToolExecutor

KMP-модуль (common; таргеты **jvm + android**, iOS — нет). Реализация `ToolExecutor` (из
`features:llm`) через MCP-сервер-подпроцесс. На старте `listTools`, дальше роутит tool-вызовы модели
в сервер по stdio.

iOS-таргета нет: `mcp-kotlin-sdk` не публикует iOS-klib. При наборе таргетов из одних JVM-платформ
(jvm+android) Kotlin пропускает common-метадату → `commonMain` фактически JVM, поэтому `McpToolClient`
(`ProcessBuilder` + mcp-sdk stdio) целиком лежит в `commonMain` без expect/actual.

## Публичный API
- `McpToolClient(command)` — `: ToolExecutor`; `connect`/`listTools`/`callTool`. Реализация на
  mcp-sdk + kotlinx-io + `Dispatchers.IO` (`McpToolClient.kt`). Спавнится клиентом
  (`apps:cliJvmApp`) как подпроцесс; несколько серверов объединяет `McpToolRouter` (в `features:llm`).
- `di/`: `mcpClientModule` — `factory { (command: List<String>) -> McpToolClient(command) }`
  (по клиенту на команду; `connect`/`close` держит вызыватель). Общая дока — [DI.md](../../DI.md).

## Зависимости
- `implementation(features:llm)` (порт `ToolExecutor` + tool-типы) + mcp-sdk + `koin.core` (для di).
Потребитель — `apps:cliJvmApp` (`SessionBuilders`).

## Тесты
`./gradlew :agenticHubClient:features:mcpClient:jvmTest` — `McpSchemaTest` (маппинг схемы инструмента,
`commonTest`).

## Грабли
- **Connect/listTools/callTool на `Dispatchers.IO`** — фоновый ридер stdio иначе на главной
  корутине, которую Kotter (TUI) блокирует циклом отрисовки → `callTool` не получает ответ, ход
  виснет на «… thinking», `/exit` не доходит. На IO ридер на своём потоке.
- stderr подпроцесса — диагностика (`redirectError(INHERIT)`), stdout — JSON-RPC.
