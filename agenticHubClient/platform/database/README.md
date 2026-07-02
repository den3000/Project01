# :agenticHubClient:platform:database — Room-KMP хранилище истории

KMP-модуль (jvm/android/ios) с чистым Room-слоем: схема БД, DAO, entity, миграции + фабрика.
Знает только про Room; доменных зависимостей нет — persistence-обёртки (`RoomHistoryStore`) живут
в `features:memory` поверх этого модуля.

## Публичный API
- `AppDatabase` (version=4, `@ConstructedBy(AppDatabaseConstructor)`), `MessageDao`, entity
  `MessageEntity`/`SummaryEntity`/`FactsEntity`, `DEFAULT_BRANCH`, миграции `MIGRATION_1_2…3_4`
  (`AppDatabase.kt`/`MessageDao.kt`/`*Entity.kt`).
- `buildDatabase()` (commonMain) + `expect fun databaseBuilder()` — actual JVM реальный
  (`~/.project01-cli/history.db`), android/ios = `TODO()` (`Database.kt` + `Database.{jvm,android,ios}.kt`).

## Зависимости
- `api(room-runtime)`; per-target KSP. Потребитель — `features:memory` (`api`).

## Тесты
`./gradlew :agenticHubClient:platform:database:jvmTest` — DAO/entities/миграции (`FactsStoreTest`/
`SummaryStoreTest`/`MigrationTest`; `:testing` для TestDb). RoomHistoryStore/persistence — в `features:memory:jvmTest`.

## Грабли
- android/ios `databaseBuilder()` — сейчас `TODO()` (нужны Context / NSDocumentDirectory).
