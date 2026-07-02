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
    single<LocalFileSystem> { JvmLocalFileSystem() }
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

## Тесты

`koin-test` не подключён. Тестам, которым нужен `LocalFileSystem`, `:testing` даёт
`testLocalFileSystem()` — резолвит из `fileSystemModule` через изолированный
`koinApplication { modules(fileSystemModule) }` (не глобальный `startKoin`).

## Грабли

- **Eager `public val xModule = xModule()`** на android/ios с `actual = TODO()` упадёт **при
  инициализации val**, если тот таргет реально его дёрнет. На этом заходе единственный потребитель —
  JVM (`cliJvmApp`), поэтому безопасно; android/ios di заводится вместе с их реальными
  `LocalFileSystem`/`databaseBuilder`.
- **`onClose`** — из `org.koin.dsl.onClose` (инфиксный на `KoinDefinition`), не из `core.module.dsl`.
