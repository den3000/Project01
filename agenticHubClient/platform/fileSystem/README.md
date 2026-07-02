# :agenticHubClient:platform:fileSystem — порт локальной ФС

KMP-модуль (jvm/android/ios): узкий путь-строковый порт к файловой системе, чтобы доменные модули
не зависели от `java.io`. Абстрагирует чтение/запись/листинг файлов.

## Публичный API
- `LocalFileSystem` — интерфейс: `exists`/`readText`/`writeText`/`delete`/`mkdirs`/`listFileNames`
  (все по строковому пути) + `expect fun localFileSystem()` (`LocalFileSystem.kt`).
- actual JVM реальный (`java.io`), android/ios = `TODO()` (`LocalFileSystem.{jvm,android,ios}.kt`).

## Зависимости
Нет модульных (лист). Потребитель — `features:memory` (`FileMemoryStore` ходит в файлы через него).

## Грабли
- android/ios `localFileSystem()` — сейчас `TODO()` (нужны Context.filesDir / NSFileManager).
