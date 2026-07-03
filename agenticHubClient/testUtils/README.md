# :agenticHubClient:testUtils — кросс-каттинг тест-утилиты

KMP-модуль (jvm/android/ios) с переиспользуемыми тест-хелперами, которым не место в конкретном
доменном/платформенном модуле. Подключается через `commonTest.dependencies { implementation(projects.
agenticHubClient.testUtils) }`. **Фейки тут НЕ живут** — они лежат рядом со своими реальными
реализациями (см. [DI.md](../DI.md)); сюда — только общие утилиты.

## Публичный API
- `@IgnoreIos` (`IgnoreIos.kt`) — expect/actual-аннотация, пропускающая тест на Apple-таргетах. JVM/
  Android — no-op (тест выполняется); iOS — `actual typealias IgnoreIos = kotlin.test.Ignore` (в
  репорте виден как **ignored**). Нужна там, где common-тест трогает eager `val` платформенного модуля,
  чей iOS-`actual` ещё `TODO()` (напр. `fileSystemModule`) — иначе Kotlin/Native падает при
  инициализации файла. Держит «iOS не готов» **видимым**, а не скрытым.

## Зависимости
- `api(kotlin.test)` (для `kotlin.test.Ignore` в iOS-actual). Потребители (`commonTest`): `platform:fileSystem`
  (и далее — везде, где нужен платформенный skip).
