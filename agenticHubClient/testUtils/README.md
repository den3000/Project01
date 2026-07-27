# :agenticHubClient:testUtils — кросс-каттинг тест-утилиты

KMP-модуль (jvm/android/ios) с переиспользуемыми тест-хелперами, которым не место в конкретном
доменном/платформенном модуле. Подключается через `commonTest.dependencies { implementation(projects.
agenticHubClient.testUtils) }`. **Фейки тут НЕ живут** — они лежат рядом со своими реальными
реализациями (см. [DI.md](../DI.md)); сюда — только общие утилиты.

## Публичный API
- `runLiveTest { … }` (`LiveTest.kt`, **jvmMain** — только JVM-таргет) — обёртка `runTest` с общим
  потолком 15 минут для live-тестов. Реальный inference идёт по стенным часам (HTTP, не виртуальное
  время) и вылетает за дефолтные 60 s `runTest`. Потолок приватный: вызывающий получает таймаут, а не
  кноб на подкрутку. Пользователи — live-тесты `features:llm`/`features:rag`/`lifecycle:session`;
  конвенция live-тестов — [LIVE_TESTS.md](../../LIVE_TESTS.md).
- `@IgnoreIos` (`IgnoreIos.kt`) — expect/actual-аннотация, пропускающая тест на Apple-таргетах. JVM/
  Android — no-op (тест выполняется); iOS — `actual typealias IgnoreIos = kotlin.test.Ignore` (в
  репорте виден как **ignored**). Нужна там, где common-тест трогает eager `val` платформенного модуля,
  чей iOS-`actual` ещё `TODO()` (напр. `fileSystemModule`) — иначе Kotlin/Native падает при
  инициализации файла. Держит «iOS не готов» **видимым**, а не скрытым.

## Зависимости
- `commonMain`: `api(kotlin.test)` (для `kotlin.test.Ignore` в iOS-actual).
- `jvmMain`: `api(kotlinx.coroutinesTest)` (для `runLiveTest` поверх `runTest`) — потому `runLiveTest`
  и виден только из `jvmTest`.
- Потребители: `platform:fileSystem`/`database`/`network`, `features:llm`/`memory`/`rag`,
  `lifecycle:start`/`session` (`@IgnoreIos` — где нужен платформенный skip, `runLiveTest` — в live-тестах).
