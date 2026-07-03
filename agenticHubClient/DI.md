# DI в agenticHubClient — Koin

Граф зависимостей собирается через **Koin** (`koin-core`, KMP; без `koin-test`). Каждый gradle-модуль,
которому есть что связывать, объявляет **свой** Koin-модуль в пакете `<root>.di`; composition root
(`apps:cliJvmApp` `main.kt`) поднимает `startKoin` со всеми модулями и резолвит из графа. Ключевая
причина, почему не `expect/actual`-фабрики инстансов (`localFileSystem()`): аргументы создания
инстанса на некоторых платформах (Android `Context`) невыразимы в общей `expect/actual`-сигнатуре — в
Koin они прячутся внутри платформенного `actual`-модуля.

## Две формы di-модуля

### Платформенный модуль (`expect/actual` → `Module`)
Для модулей с платформенными реализациями (`platform:fileSystem`, `platform:database`). Тело модуля —
`actual` на таргет, аргументы платформы прячутся в нём:
```kotlin
// commonMain/…/di/FileSystemModule.kt
internal expect fun fileSystemModule(): Module
public val fileSystemModule: Module = fileSystemModule()

// jvmMain/…/di/FileSystemModule.jvm.kt
internal actual fun fileSystemModule(): Module = module { 
    factory<LocalFileSystem> { JvmLocalFileSystem() }
}
// androidMain / iosMain: internal actual fun fileSystemModule(): Module = TODO("… not implemented yet")
```
`public val` — точка входа для composition root; `internal expect fun` — деталь. `database` так же
биндит `AppDatabase` (single, `onClose { it?.close() }`) + `MessageDao`.

### Универсальный модуль (`val xxxModule = module { }`)
Для остальных feature-модулей. Runtime-аргументы — через `parametersOf`, межмодульные зависимости —
через `get()`:
```kotlin
// features/mcpclient/di/McpClientModule.kt
val mcpClientModule = module { factory { (command: List<String>) -> McpToolClient(command) } }

// features/memory/di/MemoryModule.kt — fs из fileSystemModule, dao из databaseModule
val memoryModule = module {
    factory<MemoryStore> { (root: String) -> FileMemoryStore(root, fs = get()) }
    factory<HistoryStore> { (sessionId: String, branch: String) -> RoomHistoryStore(get(), sessionId, branch) }
}
```

## Конвенции

- **Пакет** — `<root>.di`, файл `<Name>Module.kt` (платформенный: `<Name>Module.<target>.kt`).
- **single vs factory** — `single` для stateless/разделяемого на всю сессию (`LocalFileSystem`,
  `AppDatabase`, `HttpClient`, `StartExecutor`); `factory` для того, что параметризовано runtime-данными
  или нужно по экземпляру на вызов (`LlmApi` на `ModelProvider`, `McpToolClient` на команду).
- **Runtime-аргументы** — `parametersOf(...)` на call-site, деструктуризация в лямбде фабрики. Лимит
  деструктуризации — **5 аргументов**; если больше, завернуть в data-holder (пример:
  `SessionAssemblyArgs` для `buildSessionViewModel` с 9 аргументами).
- **Lifecycle** — ресурсы, требующие закрытия, — `single { … } onClose { it?.close() }`; закрываются
  при `stopKoin()` в `finally` composition root (заменяет ручные `.close()`/`.use{}`). Исключение —
  `McpToolClient`: `factory`, `connect`/`close` держит вызыватель (`runSession`).

## Composition root (`apps:cliJvmApp`)

`main.kt` → `startKoin { modules(appModule, fileSystemModule, databaseModule, llmModule, memoryModule,
agentModule, mcpClientModule, startModule, sessionModule) }`; `koin.get()` / `koin.get { parametersOf(…) }`
вместо ручных `new`; `stopKoin()` в `finally`. `appModule` (`cliJvm/di/AppModule.kt`) держит app-owned
биндинги: `HttpClient` (Java-движок, single+`onClose`), `ApiKeys` (из `BuildKonfig`),
`ModelProviderFactory`, `CliArgsParser`. `ModelProvider` НЕ биндится — он runtime-производный,
передаётся параметром в `llmModule`. Условная логика RunChat/RunOneShot остаётся в accessor'ах
`SessionInitialStateExtensions` (`resolveHistoryStore(koin)`/`resolveMemoryProvider(koin, root)`);
`contextStrategy()` — чистая доменная логика без инъекций.

## Какие модули имеют di-пакет

- **Есть**: `platform:fileSystem`, `platform:database`; `features:llm`, `features:memory`,
  `features:agent`, `features:mcpClient`, `features:lifecycle:session`, `features:lifecycle:start`;
  `apps:cliJvmApp` (`appModule`).
- **Нет** (нечего инжектить): `platform:logging` (top-level `logWarn`), `platform:config`
  (`object BuildKonfig`), `platform:greeting`, `features:lifecycle:command` (только data-типы),
  `features:composeApp`.

## Test-модули (`*TestModule`)

Тесты собирают граф через ту же DI-систему: фейки и тест-хелперы живут **рядом со своими реальными
реализациями** (не в отдельном модуле — `:testing` снесён), а модуль с фейкопригодным портом объявляет
**рядом со своим прод-модулем** (в `di`-файле) `val xxxTestModule`, биндящий тот же интерфейс на фейк.
Интеграционный тест грузит `koinApplication` с test-вариантом **вместо** прод-модуля, а остальные (real)
модули — как есть.

- **`factory`, не `single`** — каждый `get()` отдаёт свежий фейк, тесты полностью независимы (никакого
  состояния между ними, и `val`-модуль можно переиспользовать между `koinApplication` без утечки
  закэшированного инстанса). Прод-модули — свои binding'и (`single` там, где нужен один инстанс на сессию).
- **`common`, не `expect/actual`** — фейк платформо-агностичен, поэтому модуль работает на **всех**
  таргетах, в т.ч. там, где прод-модуль ещё `TODO` (`fileSystemTestModule` поднимается на iOS, где
  `fileSystemModule` падает). Это и даёт «тесты в любом окружении».
- **Размещение фейков** — сами fake-классы (`LocalFileSystemFake`, `LlmApiFake`/`FakeLlmScript`)
  лежат в **отдельных файлах, в пакете реальной реализации** (`...filesystem`, `...features.llm`) — не в
  `di`-файле с модулем. `internal` виден `di`-модулю (та же gradle-единица).
- **Конфигурация/шаринг через `parametersOf`** — если фейк надо настроить или разделить между
  компонентами, тест создаёт инстанс сам и передаёт его resolve-параметром. Так `llmTestModule`:
  `factory<LlmApi> { (script: FakeLlmScript?) -> LlmApiFake(script ?: get()) }` — без параметра
  строится свежий пустой скрипт, а `get<LlmApi> { parametersOf(myScript) }` подставляет настроенный.
- **`platform:fileSystem` → `fileSystemTestModule`**: `factory<LocalFileSystem> { LocalFileSystemFake() }`
  (path→content map, `internal`). Seed/assert — через публичный интерфейс `LocalFileSystem`
  (`writeText`/`readText`/`listFileNames`).
- **`features:llm` → `llmTestModule`**: `factory<LlmApi> { (script) -> LlmApiFake(script ?: get()) }`
  (`internal` фейк) + `factory { FakeLlmScript() }`. Интерфейс `LlmApi` (`send`) узкий → очередь
  ответов/инспекция вызовов живут в публичном `FakeLlmScript`; тест создаёт его, `queueText(...)`,
  передаёт через `parametersOf`, потом читает `script.calls`.
- **`platform:database` → `fun databaseTestModule(db: AppDatabase)`**: биндит **реальную** БД (без мока
  таблиц), которую тест уже собрал через `TestDb` (temp-file Room поверх bundled SQLite). Аргументом —
  потому что БД это closeable-ресурс, которым владеет тест (`TestDb().use { … }` чистит), и её надо
  **забиндить** (`factory<AppDatabase>`/`factory<MessageDao>`, каждый `get()` отдаёт переданный `db`),
  а не передать resolve-параметром: до вложенного `get<MessageDao>()` в `memoryModule` `parametersOf`
  не доедет. Никаких синглтонов в тестах — инстанс приходит снаружи, модуль создаётся на каждый тест
  (`fun`, свой `db`).
- **Использование LLM-фейка в тестах** — тест держит локальный хелпер
  `scriptedApi(script) = koinApplication { modules(llmTestModule) }.koin.get { parametersOf(script) }`;
  `val script = FakeLlmScript().apply { queueText(...) }; val api = scriptedApi(script)`, потом ассертит
  `script.calls`. Пустая очередь → `LlmResult(error = "FakeLlmScript: no scripted response")`.
- **`testLocalFileSystem` больше нет** — где тесту нужен реальный `LocalFileSystem` (пишет на диск),
  резолвится напрямую из прод-модуля: `koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()`
  (даёт `JvmLocalFileSystem`). `fileSystemTestModule` (in-memory) — для графов, где диск не нужен.
- **Источник** — `commonMain`/`jvmMain` (main source set), т.е. фейки попадают в прод-артефакт; цена —
  крошечные `internal`-классы (`TestDb` public), зато они видны тестам любого модуля без отдельного
  тест-модуля (KMP не умеет `java-test-fixtures`).

`koin-test` не подключён — тесты используют обычный `koinApplication { … }` (не глобальный `startKoin`).

## Грабли

- **Eager `public val xModule = xModule()`** на android/ios с `actual = TODO()` упадёт **при
  инициализации val**, если тот таргет реально его дёрнет. android/ios di заводится вместе с их реальными
  `LocalFileSystem`/`databaseBuilder`.
  - **Следствие для тестов**: Kotlin/Native инициализирует **все** top-level `val` файла при первом
    касании любого из них. Значит common-тест, который трогает `fileSystemTestModule` (лежит рядом с
    eager `fileSystemModule` в одном файле), на iOS падает `FileFailedToInitializeException`. **НЕ**
    прячем это разнесением по файлам (маскировало бы «iOS не готов») — помечаем тест `@IgnoreIos` из
    модуля **`:testUtils`** (expect/actual: JVM/Android — no-op, iOS — `kotlin.test.Ignore`), тогда в
    iOS-репорте он виден как **ignored**, а JVM гоняет. `llmTestModule` рядом с `llmModule` без проблем
    (у llm нет `TODO`-actual).
- **`onClose`** — из `org.koin.dsl.onClose` (инфиксный на `KoinDefinition`), не из `core.module.dsl`.
