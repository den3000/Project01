---
name: test-writer
description: How to write Kotlin unit tests in this project's house style — backtick names with "when X - then Y" format, given/when/then body sections, fakes over mocks, runTest for suspend code, file size limit of 15-20 tests. Use this skill whenever the user wants to add, write, or refactor a test in Kotlin — phrases like "напиши тест", "добавь тест", "покрой тестом", "тест на", "write a test for", "add coverage", "test this function" should trigger it, even if they don't mention style. The project has strong stylistic conventions that easily go violated without these rules. Does NOT auto-refactor existing tests — applies to NEW tests only unless the user explicitly asks to rewrite.
---

# Test Writer (Kotlin — commonTest / jvmTest, JUnit 4)

Правила для написания **новых** Kotlin-тестов в этом проекте. Существующие тесты **не трогать** без явной просьбы пользователя — стиль вводится постепенно, рефакторинг старья — отдельная задача.

Проект — KMP, тесты живут **по модулям** (не только в `:cliJvmApp:test`): `commonMain`-код тестируется в
`commonTest` (мультиплатформенный `kotlin-test` + `kotlinx-coroutines-test` `runTest`), а JVM-only тесты
(настоящий `HttpClient` из `platform:network`, `org.junit.Assume` для live) — в `jvmTest` (JUnit 4). Моков
(`mockk`, `mockito`) **нет** и не нужно — только handwritten fakes, живущие **рядом с реализациями** и
подключаемые через тест-Koin-модуль (см. §10 и §14): `LlmApiFake`/`FakeLlmScript` (`features:llm`),
`EmbedderFake` (`features:rag`), `TestDb` (`platform:database`), `LocalFileSystemFake` (`platform:fileSystem`).

## 1. Имя теста

Backtick-имя, всё **с маленькой буквы**, формат `` `when <action> - then <result>` ``. Никакого camelCase, никаких заглавных в начале.

**Хорошо:**
```kotlin
@Test fun `when -entities passed - then CliArgs_ListEntities returned`() { ... }
```

Тире между when и then — **обычное `-` в окружении пробелов**, не `—`.

**Плохо:**
```kotlin
@Test fun dashEntitiesReturnsListEntities() { ... }
@Test fun `Dash entities returns ListEntities object`() { ... }
@Test fun `dash entities returns ListEntities object`() { ... }
```

## 2. Тело теста

Три секции, каждая открывается комментарием. **Always**, даже для однострочных тестов — единообразие важнее лаконичности.

```kotlin
@Test
fun `when fromId is given an unpulled tag - then it falls back to Custom`() {
    // given
    val tag = "mistral-small:24b"

    // when
    val actual = OllamaModel.fromId(tag)

    // then
    val expected = OllamaModel.Custom(tag)
    assertEquals(expected, actual)
}
```

Где есть «результат» и «ожидание» — называть `actual` и `expected` явно. Это спасает diff при провале (порядок аргументов `assertEquals` — `expected, actual`, см. §6).

Примеры как тесты писать **не нужно**

1. Не нужно сваливать всё в одну строку - это не читаемо
```kotlin
@Test
fun `when fromId is given an unpulled tag - then it falls back to Custom`() {
    // when - then
    assertEquals(OllamaModel.Custom("mistral-small:24b"), OllamaModel.fromId("mistral-small:24b"))
}
```

2. Не нужно добавлять комментарии к given | when | then секциям
```kotlin
@Test
fun `when fromId is given an unpulled tag - then it falls back to Custom`() {
    // given - an unpulled tag
    val tag = "mistral-small:24b"

    // when - resolving it through fromId
    val actual = OllamaModel.fromId(tag)

    // then - expect and actual matches
    val expected = OllamaModel.Custom(tag)
    assertEquals(expected, actual)
}
```

3. Не нужно разделять секцию ассертов доп. комментариями
```kotlin
@Test
fun `when fromId is given an unpulled tag - then it falls back to Custom`() {
    // given
    val tag = "mistral-small:24b"

    // when
    val actual = OllamaModel.fromId(tag)

    // then
    // expected value - a Custom tag
    val expected = OllamaModel.Custom(tag)
    // expected matches Custom
    assertEquals(expected, actual)
}
```

## 3. Region-разделители

Когда в файле логические подгруппы — оборачивать в `//region` / `//endregion`. **Не** `// --- ... ---`.

**Хорошо:**
```kotlin
//region mode conflicts and validation errors
@Test fun ...
@Test fun ...
//endregion
```

Android Studio такие region'ы сворачивает, что напрямую решает проблему «не вижу что в файле». Внутри одного region — тесты одной семантической группы.

**Плохо:**
```kotlin
// --- mode conflicts and validation errors ------------------------
@Test fun ...
```

Android Studio с такими комментариями ничего не делает — мы теряем возможность читать тесты в свёрнутом виде.

## 4. Размер файла

**Soft limit — 15 тестов. Hard limit — 20.** Если файл подходит к 20+ — **разбить** перед тем как добавлять новый тест.

Как разбивать: один region → один новый тестовый класс. Например, `EntitiesTest` (на 80 тестов) разнести как:
- `EntitiesViewTest` (`-entities` / `-show` / `-details`)
- `EntitiesCRUDTest` (`-insert` / `-delete` / `-read`)
- `EntitiesFileTest` (`-file` / `-limit`)

Один тестовый файл должен быть «осилимым за один взгляд» — это и есть смысл лимита. 80 тестов в одном файле никто не читает целиком.

Если пользователь просит «добавь тест в X», а X уже на 19 тестах — **сказать ему**: «файл подошёл к лимиту, предлагаю выделить region Y в отдельный класс. Делать?» — и ждать ответа, не делить молча.

### Подпапка для разбиения

Когда разбиваешь один файл на **два и более** — **в том же сообщении** предложи пользователю переложить получившиеся файлы в **новую подпапку**, имя которой = корень исходного класса в camelCase (без суффикса `Test`).

Иллюстрация:

```
до:                              после:
app/                             app/
└── EntitiesTest.kt   (80)       └── entities/
                                     ├── EntitiesViewTest.kt
                                     ├── EntitiesCRUDTest.kt
                                     └── EntitiesFileTest.kt
```

Зачем папка: 3 файла рядом с условными `ProvidersTest.kt`, `ApisTest.kt`, `DbTest.kt` и т.п. — это шум в Project view. Папка `entities/` визуально группирует «всё про работу Entities» и читается как одна сущность.

Что меняется технически:
- **Package** в каждом перенесённом файле — добавить суффикс по имени папки: `package …app` → `package …app.entities`. IntelliJ/Studio будет ругаться на несоответствие path и package, поэтому соответствие держим.
- **Видимость**: на `internal` это не влияет — `internal` это module-private, а не package-private; sub-package видит `internal` родителя в том же compile module. На `public`-классах из родительского пакета тестам всё видно как раньше.
- **Импорты в тестах не меняются** — они уже импортировали по полному имени (или вообще не импортировали, потому что были в том же пакете, что и тестируемый класс). После переноса добавятся `import …app.entities` и т.п. — это IDE сделает автоматически при компиляции, либо `kotlinc` ругнётся и подскажет.

Когда **не** предлагать папку:
- Разбиение на ровно 2 файла, оба коротких — лишний уровень вложенности дороже визуального шума.
- Если у пользователя уже есть устоявшаяся flat-структура и он явно сказал «не плоди папки» — уважать.

Формулировка предложения: «Разбил на N файлов. Предлагаю положить их в подпапку `<camelCase>/` (package станет `…<camelCase>`), чтобы они не шумели рядом с остальными тестами. Делать?» — и ждать ответа.

## 5. Helpers, константы, factory-функции

**В конце файла**, после всех `@Test`-методов. Имена — **явные**, не однословные:

**Хорошо:**
```kotlin
private fun buildModelProviderWithBlankKeys(provider: String, modelRaw: String?): ModelProvider =
    buildModelProvider(
        providerRaw = provider,
        modelRaw = modelRaw,
        geminiApiKey = "",
        openRouterApiKey = "",
        huggingFaceApiKey = "",
    )
```

**Плохо:**
```kotlin
private fun build(p: String) = buildModelProvider(...)
```

**Factory-функции для тестовых данных** — там же, в конце:

```kotlin
private fun message(
    text: String = "hi",
    role: Role = Role.USER,
): Message = Message(role = role, text = text)
```

Дефолтные параметры — чтобы тест писал только то, что важно для конкретного кейса.

**Константы** — в `private companion object` в самом низу файла:

```kotlin
private companion object {
    const val DUMMY_GEMINI_KEY = "test-gemini-key"
    const val DUMMY_OPENROUTER_KEY = "test-openrouter-key"
}
```

Если какая то хэлпер функция или константа повторяется в 2ух и более тестах, то она обязательно должна быть
вынесена в соответствующий файл с утилитами, например `app/entities/EntitiesTestUtils.kt`, если отталкиваться
от схемы выше.

## 6. Один логический assert на тест

Один unit-тест проверяет **одно поведение**. Если связанные проверки (поля одного объекта) — пачкой ОК:

```kotlin
// then
assertEquals("hi", entity.text)
assertEquals(100, entity.value)
assertEquals(0.7, entity.point)
```

— это всё про «параметры одного `Entity`», норм. Но **не** надо в одном тесте проверять, что парсинг 
прошёл, потом что DB записала, потом что API вернула. Это три разных unit-теста. Такое тестирование,
проверка нескольких доменов, может быть выполнено в рамках интеграционного тестирование, но оно
не рассматривается в данном документе.

Порядок аргументов `assertEquals` — **`expected, actual`** (как в kotlin.test). 
И **не** `assertTrue(x == y)` — пиши `assertEquals(y, x)`: при провале увидишь `expected: <…> but was: <…>`, 
а не голый `false`.

**Проверка типа — `assertIs<T>(x)`, не `assertTrue(x is T)`.** `assertIs` даёт внятное сообщение при
провале (`expected <T> but was <…>`) **и** smart-cast'ит `x` к `T` дальше в тесте:

```kotlin
// then
assertIs<ModelProvider.LocalOllama>(actual)
assertEquals("http://localhost:11434/api/chat", actual.endpoint)  // actual уже LocalOllama
```

**Precondition сетапа — `require`/`requireNotNull`, а не `assert*`.** Если строчка не проверяет
_поведение под тестом_, а лишь готовит фикстуру (распаковать `null`, убедиться, что тестовые данные
валидны), это `requireNotNull(...)` / `require(...)`, не assert. Assert'ы держим для `// then`. Пример —
`requireNotNull(indexStore.load(path)) { "index did not reload" }` в `DocsIndexingOllamaLiveTest`.

## 7. Видимость для тестов — через `internal`, не reflection

Если функция нужна для теста, но не должна быть public API — пометить `internal`. Reflection не использовать. 
Это сигнал, что либо тест слишком глубоко лезет, либо функция должна быть protected/internal по дизайну.

```kotlin
// в main: ru/den/.../Entity.kt
internal fun foldOldestPair(...) { ... }

// в test: EntityTest.kt — internal видна, потому что
// test sourceSet делит package и module с main
```

## 8. Тестирование suspend и корутин

**`kotlinx.coroutines.test.runTest`**, не `runBlocking`. `runTest` пропускает delay, виртуальное время, 
нормальный exception propagation.

```kotlin
import kotlinx.coroutines.test.runTest

@Test
fun `when verifyThreshhold below threshold - then null returned and api not called`() = runTest {
    // given
    val fakeApi = FakeApi(reply = "should not be called")
    val entity = Entity(api = fakeApi, threshold = 10)

    // when
    val actual = entity.verifyThreshhold(messages = listOf(something(), something()))

    // then
    assertNull(actual)
    assertEquals(0, fakeApi.callCount)
}
```

Если на JVM нужен явный `TestDispatcher` (управление временем) — `runTest` его сам создаёт и шарит 
как `coroutineContext[TestCoroutineScheduler]`. Прокидывать в подсистемы как параметр конструктора, 
не через `Dispatchers.setMain` — это Android-патерн, тут не нужен.

## 9. Тестирование Flow

Собирать через `.toList()`. Если flow бесконечный или горячий — `take(N).toList()`. Никаких внешних 
библиотек типа `turbine` — их в проекте нет, не тащить.

```kotlin
@Test
fun `when bus emits three events - then all three collected in order`() = runTest {
    // given
    val bus = MutableSharedFlow<Int>(replay = 0)
    val collected = mutableListOf<Int>()
    val job = launch { bus.take(3).toList(collected) }

    // when
    bus.emit(1); bus.emit(2); bus.emit(3)
    job.join()

    // then
    assertEquals(listOf(1, 2, 3), collected)
}
```

## 10. Fakes, не mocks

В проекте `mockk` / `mockito` **не подключены и не нужны**. Писать **handwritten fake**:

- Имя: **суффикс `<Interface>Fake`** — `LlmApiFake`, `EmbedderFake`, `LocalFileSystemFake` (не префикс
  `FakeXxx`). Отдельный holder сценария/инспекции, если он есть, — `Fake<Что>Script` (`FakeLlmScript`).
- Файл: **рядом с реализацией** в своём модуле (`commonMain`/`commonTest`), не в `cliJvmApp/src/test`.
  Фейк-класс обычно `internal` (виден только под интерфейсом через тест-Koin-модуль — см. §14); публичным
  делается лишь holder, чтобы тесты из любого модуля могли его сконфигурировать с графа.
- Реализует тот же интерфейс, что и реальный класс. Никаких частичных «proxy» — fake полный и самостоятельный.
- Хранит вход (`calls`, `callCount`, `lastX`) — тест проверяет их в `// then`. Ответ — из очереди/holder'а,
  не из живого сервиса.

Скелет (реальный `LlmApiFake` + `FakeLlmScript` из `features:llm`):

```kotlin
// holder — public, конфигурируется тестом с графа
public class FakeLlmScript {
    private val responses = ArrayDeque<LlmResult>()
    public val calls: MutableList<Call> = mutableListOf()   // всё, что видел send()

    public data class Call(val messages: List<Message>, val params: GenerationParams)

    public fun queueText(text: String) { responses += LlmResult(text = text, usage = /* … */) }

    internal fun record(messages: List<Message>, params: GenerationParams) { calls += Call(messages.toList(), params) }
    internal fun next(): LlmResult =
        if (responses.isEmpty()) LlmResult(text = null, error = "FakeLlmScript: no scripted response")
        else responses.removeFirst()
}

// fake — internal, только под интерфейсом через llmTestModule
internal class LlmApiFake(private val script: FakeLlmScript) : LlmApi {
    override suspend fun send(messages: List<Message>, params: GenerationParams): LlmResult {
        script.record(messages, params)
        return script.next()
    }
}
```

См. `LlmApiFake.kt` (`features:llm`), `EmbedderFake.kt` (`features:rag`), `TestDb.kt` (`platform:database`),
`LocalFileSystemFake` (`platform:fileSystem`) — реальные примеры. CLAUDE.md фиксирует: фейки живут рядом с
реализациями, отдельного `:testing`-модуля нет.

## 11. Дискуссионные правила (можно нарушать, если контекст оправдывает)

**E. Параметризация через `forEach`.** Общее правило — **писать N отдельных `@Test`-методов**, не 
один с `listOf(case1, case2, …).forEach`. Это сохраняет читабельность имён `when X - then Y` и точное сообщение об ошибке.

Но **есть случаи, где `forEach` оправдан**:
- Проверка «USAGE упоминает каждый флаг» — один логический assert, расширяемый список (см. `USAGE mentions every public flag` в `CliArgsTest`).
- Smoke-обход справочного списка (все значения enum, все элементы registry).
- Когда раздувание в 15 одинаковых @Test'ов засорит файл без пользы.

В таких случаях — внутри `forEach` использовать message-параметр `assertX(actual, expected, "message about case")`, 
чтобы при провале было видно какой именно кейс упал.

**F. `@Before` / `@After` для общего setup.** Общее правило — **не использовать**, фикстуру 
создавать в `// given` каждого теста (или factory-функцией внизу). `@Before` прячет state, читатель 
не понимает откуда взялся `db`/`api`. Если инстанцирование очень грамоздкое, то писать для него
вспомогательные функции.

## 12. Чего не делать

- **Live network — только в отдельной ветке, не в offline-сьюте.** Дефолтный прогон (`commonTest` +
  `jvmTest` без флага) обязан быть офлайновым и детерминированным: никаких реальных вызовов Gemini /
  OpenRouter / HuggingFace / Ollama в обычных тестах. Реальный сервис — только в opt-in `*LiveTest`
  (см. §15), исключённом центральным гейтом.
- **Не подключать mockk / mockito** для решения «как замокать suspend» — есть handwritten fake (см. §10).
- **Не использовать `Thread.sleep` / `runBlocking { delay(…) }`** для синхронизации. `runTest` даёт виртуальное время; на нём `delay()` пропускается.
- **Не писать assert на «оно не упало»** — если тест просто запускает функцию и не проверяет результат, это не тест. Проверять конкретное наблюдаемое поведение (возвращаемое значение, побочный эффект в fake, exception).
- **Не комментировать `@Ignore` сломанные тесты** — починить или удалить. Если не починить сейчас — открыть отдельный TODO, и не коммитить ignored.

## 13. Grammar-тесты парсинга (cliargs/grammar)

Узкий, но строгий паттерн для тестов разбора CLI-грамматики (`CliArgsParser` → `ParsedArg`, `ParseResult.Ok/Err`). Эталон — `cliargs/grammar/CliEntityGrammarTest.kt`. Хелперы — `cliargs/CliArgsTestUtils.kt` (`assertMatchParserCmd` / `assertMatchParserFlag` / `assertMatchParserError`, `ExpectedControl` / `top` / `sub` / `toArgsList`).

- **Один `@Test` на (контрол × фронт).** Имя: `` `when <control> <front> grammar used - then it is parsed accordingly` `` (или `- then it fails` для чисто-негативного, как branch на FLAG).
- **given выделяет всё:** `cli` (CliArg), `sfc` (Surface), `cmd` (строка `-flag` / `/cmd`), значения (`name` / `text` / `id` / …). НЕ инлайнить значения, фронт или имена прямо в `then` — всё в given. `// then` — голый, без пояснений (формы говорят сами за себя).
- **Паритет фронтов.** FLAG-тест повторяет формы CMD-теста один-в-один (плюс FLAG-специфичные негативы). FLAG не должен быть беднее CMD. Surface-асимметрия (value только на одном фронте, контрол command-only) — выражается явно негативом, не молчаливым пропуском.
- **Исчерпывающее покрытие, не «представители».** Все варианты sub / секции / режима / enum-значения — через `forEach` по списку (идиома §11.E здесь **норма**, не исключение). Покрыл `style` → покрой все 4 секции (+ форма «секция без текста = clear», + unnamed); покрыл `window` → покрой все 4 strategy-режима (+ combo); покрыл 2 agent-sub → покрой все 10. «Доказал механизм на одном» — НЕ достаточно: тест-документация должна показывать каждую форму.
- **Позитив и негатив рядом** в region своего контрола. Негативы — по природе фронта: CMD → `assertMatchParserCmd` / single `parse`; FLAG → `assertMatchParserFlag` / `parseArgv`. Cross-validation (`Conflicts` / `Requires`) ловится только через `parseArgv` → её место в `crossvalidation/`, не в per-control grammar-файле. Мета-инварианты каталога (`CliArgs.all`) — не грамматика, отдельный `CliArgsCatalogTest`.
- **Контрол обоих фронтов → два теста** (`command` + `flag`), каждый со своим `sfc` в given. Контрол одного фронта → один тест + негатив на неверном фронте (`WrongSurface` через `cli.title`).

## 14. Koin в тестах

DI в проекте — Koin, и тесты **резолвят зависимости из графа**, а не собирают объекты руками. Это ловит
ошибки самого графа и держит тест ближе к бою. Общая дока — [DI.md](../../../agenticHubClient/DI.md).

- **`koin` — поле класса**, одно объявление на тест-класс (не создавать в каждом методе, не через `@Before`).
  JUnit4 делает свежий инстанс на каждый `@Test`, так что состояние между тестами не течёт:

  ```kotlin
  private val koin = koinApplication { modules(ragModule, networkModule) }.koin
  ```

- **Прод-модули для интеграции/live** (`llmModule` / `ragModule` / `networkModule`) — резолвят реальные
  реализации. **Тест-модули для offline** (`llmTestModule` / `ragTestModule` / `fileSystemTestModule` /
  `databaseTestModule`) — те же биндинги, но на фейках (§10), без сети.
- **Параметризованные factory — через `parametersOf`.** Не `new` руками:

  ```kotlin
  val api = koin.get<LlmApi> { parametersOf(ModelProvider.LocalOllama(model = liveChatModel())) }
  val pipeline = koin.get<IndexingPipeline> { parametersOf(StructuralChunking()) }
  val retriever = koin.get<Retriever> { parametersOf(index) }
  ```

  Эталон — `DocsIndexingOllamaLiveTest` / `RagModuleTest` (резолвят весь пайплайн с графа).
- **Скриптовый фейк — с графа.** `llmTestModule` отдаёт `LlmApiFake` под `LlmApi`; тест конфигурирует
  публичный `FakeLlmScript` и передаёт его через `parametersOf`, потом ассертит на `script.calls` в `// then`.

## 15. Live-тесты (реальный сервис)

Тест, который бьёт по реальному внешнему сервису (локальная Ollama и т.п.), — это **не запрещено**, а
отдельная **opt-in** ветка. Полный механизм и конвенция — [LIVE_TESTS.md](../../../LIVE_TESTS.md).

- **Имя класса оканчивается на `LiveTest`**, файл — в `src/jvmTest` (нужен реальный `HttpClient` из
  `platform:network` + JUnit `Assume`).
- **Центральный гейт** в корневом `build.gradle.kts` исключает `*LiveTest` из дефолтного прогона; включаются
  флагом **`-PliveTests`**. Без флага — не запускаются (даже явный `--tests`), offline-сьют остаётся офлайновым.
- **Сам себя страхует:** probe сервиса + `org.junit.Assume.assumeTrue(...)` → при недоступном сервисе тест
  `skipped`, а не красный. Для Ollama есть готовый хелпер `liveOllamaTest(koin) { … }` (probe `GET /api/tags`):

  ```kotlin
  @Test
  fun `when a plain question is sent - then a non-empty answer comes back`() = liveOllamaTest(koin) {
      // given
      val api = koin.get<LlmApi> { parametersOf(ModelProvider.LocalOllama(model = liveChatModel())) }
      // when / then …
  }
  ```

- Внутри — те же правила (§1/§2/§14). `println` для наблюдаемого сравнения допустим (это диагностика live-прогона),
  но **обязателен и настоящий assert** на поведение — «оно не упало» не считается (§12).

## Проверка после написания

После того как написал/добавил тест:

1. Запустить таск нужного модуля: `./gradlew :agenticHubClient:features:<module>:jvmTest` (или
   `./gradlew :agenticHubClient:apps:cliJvmApp:test` / `/cli-smoke` для CLI). Live — с `-PliveTests`
   (спросить перед запуском: нужен поднятый сервис).
2. Убедиться что **новые** тесты прошли. Если один упал — починить, не маскировать.
3. Если файл вырос **за** 20 тестов — это **блок** на merge: разбить файл перед коммитом.

## Когда правила противоречат друг другу

Если возникает «у меня есть legacy-файл `Foo.kt` на 60 тестов, мне нужно добавить ещё один — правило
4 говорит разбить, но пользователь не просил рефакторинг» — **не разбивать молча**. Спросить: «Файл 
уже за лимитом, добавляю тест в текущий стиль (как было) или сначала разнесём по region'ам?» Решение — пользователя.
