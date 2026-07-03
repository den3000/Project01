# :agenticHubClient:platform:fileSystem — порт локальной ФС

KMP-модуль (jvm/android/ios): узкий путь-строковый порт к файловой системе, чтобы доменные модули
не зависели от `java.io`. Абстрагирует чтение/запись/листинг файлов.

## Публичный API
- `LocalFileSystem` — интерфейс: `exists`/`readText`/`writeText`/`delete`/`mkdirs`/`listFileNames`
  (все по строковому пути) (`LocalFileSystem.kt`). `JvmLocalFileSystem` (`java.io`) — `internal`.
- `di/`: `fileSystemModule` — Koin-модуль, биндит `LocalFileSystem` (`internal expect` + `public val`;
  jvm actual `single { JvmLocalFileSystem() }`, android/ios `TODO`). Рядом `fileSystemTestModule` —
  common-модуль на `internal InMemoryLocalFileSystem` (in-memory; работает на всех таргетах, для
  композиции integration-графов вместо `fileSystemModule`). Общая дока — [DI.md](../../DI.md).

## Зависимости
- `implementation(koin.core)` (для di). Модульных — нет (лист). Потребитель — `features:memory`
  (`FileMemoryStore` получает `LocalFileSystem` из графа).

## Грабли
- android/ios `fileSystemModule` actual — сейчас `TODO()` (нужны Context.filesDir / NSFileManager);
  eager `public val` упадёт при инициализации, если тот таргет его дёрнет (см. [DI.md](../../DI.md)).
