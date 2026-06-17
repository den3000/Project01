---
name: test-writer
description: How to write Kotlin unit tests in this project's house style — backtick names with "when X - then Y" format, given/when/then body sections, fakes over mocks, runTest for suspend code, file size limit of 15-20 tests. Use this skill whenever the user wants to add, write, or refactor a test in Kotlin — phrases like "напиши тест", "добавь тест", "покрой тестом", "тест на", "write a test for", "add coverage", "test this function" should trigger it, even if they don't mention style. The project has strong stylistic conventions that easily go violated without these rules. Does NOT auto-refactor existing tests — applies to NEW tests only unless the user explicitly asks to rewrite.
---

# Test Writer (cliJvmApp / Kotlin / JUnit 4)

Правила для написания **новых** Kotlin-тестов в этом проекте. Существующие тесты **не трогать** без явной просьбы пользователя — стиль вводится постепенно, рефакторинг старья — отдельная задача.

Стек, который реально используется (не выдумывать другие): `kotlin-test` + `kotlin-test-junit` + JUnit 4, `kotlinx-coroutines-test` (`runTest`). Моков (`mockk`, `mockito`) **нет** и не нужно — только handwritten fakes (см. `cliJvmApp/src/test/.../FakeLlmApi.kt`, `TestDb.kt`).

## 1. Имя теста

Backtick-имя, всё **с маленькой буквы**, формат `` `when <action> - then <result>` ``. Никакого camelCase, никаких заглавных в начале.

**Плохо:**
```kotlin
@Test fun dashSessionsReturnsListSessions() { ... }
@Test fun `Dash sessions returns ListSessions object`() { ... }
@Test fun `dash sessions returns ListSessions object`() { ... }
```

**Хорошо:**
```kotlin
@Test fun `when -sessions arg passed - then CliArgs_ListSessions returned`() { ... }
@Test fun `when prompt is empty - then MissingRequiredArgument thrown`() { ... }
```

Тире между when и then — **обычное `-` в окружении пробелов**, не `—`.

## 2. Тело теста

Три секции, каждая открывается комментарием. **Always**, даже для однострочных тестов — единообразие важнее лаконичности.

```kotlin
@Test
fun `when -sessions arg passed - then CliArgs_ListSessions returned`() {
    // given
    val args = arrayOf("-sessions")

    // when
    val actual = parseCliArgsWithDummyKeys(args)

    // then
    val expected = CliArgs.ListSessions
    assertEquals(expected, actual)
}
```

Где есть «результат» и «ожидание» — называть `actual` и `expected` явно. Это спасает diff при провале (порядок аргументов `assertEquals` — `expected, actual`, см. §6).

## 3. Region-разделители

Когда в файле логические подгруппы — оборачивать в `//region` / `//endregion`. **Не** `// --- ... ---`.

**Плохо:**
```kotlin
// --- mode conflicts and validation errors ------------------------
@Test fun ...
```

**Хорошо:**
```kotlin
//region mode conflicts and validation errors
@Test fun ...
@Test fun ...
//endregion
```

Android Studio такие region'ы сворачивает, что напрямую решает проблему «не вижу что в файле». Внутри одного region — тесты одной семантической группы.

## 4. Размер файла

**Soft limit — 15 тестов. Hard limit — 20.** Если файл подходит к 20+ — **разбить** перед тем как добавлять новый тест.

Как разбивать: один region → один новый тестовый класс. Например, `CliArgsTest` (на 80 тестов) разнести как:
- `CliArgsModeSelectionTest` (`-sessions` / `-clean` / `-oneshot` / `-inflate` выбор режима)
- `CliArgsProviderRoutingTest` (gemini / openrouter / huggingface)
- `CliArgsFeedModeTest` (`-feedFile` / `-chunkChars` / `-byLine`)
- `CliArgsStrategyTest` (`-strategy` / `-compress` / `-keepLast` / `-summarizeEvery`)
- `CliArgsValidationErrorsTest` (conflicts, invalid values)

Один тестовый файл должен быть «осилимым за один взгляд» — это и есть смысл лимита. 80 тестов в одном файле никто не читает целиком.

Если пользователь просит «добавь тест в X», а X уже на 19 тестах — **сказать ему**: «файл подошёл к лимиту, предлагаю выделить region Y в отдельный класс. Делать?» — и ждать ответа, не делить молча.

### Подпапка для разбиения

Когда разбиваешь один файл на **два и более** — **в том же сообщении** предложи пользователю переложить получившиеся файлы в **новую подпапку**, имя которой = корень исходного класса в camelCase (без суффикса `Test`).

Иллюстрация:

```
до:                              после:
cliJvm/                          cliJvm/
└── CliArgsTest.kt   (80)        └── cliArgs/
                                     ├── CliArgsModeSelectionTest.kt
                                     ├── CliArgsProviderRoutingTest.kt
                                     ├── CliArgsValidationTest.kt
                                     ├── CliArgsFeedModeTest.kt
                                     ├── CliArgsInflateTest.kt
                                     ├── CliArgsUsageTest.kt
                                     ├── CliArgsCompressionTest.kt
                                     └── CliArgsStrategyTest.kt
```

Зачем папка: 8 файлов рядом с `AgentTest.kt`, `FakeLlmApi.kt`, `TestDb.kt` и т.п. — это шум в Project view. Папка `cliArgs/` визуально группирует «всё про парсинг CLI» и читается как одна сущность.

Что меняется технически:
- **Package** в каждом перенесённом файле — добавить суффикс по имени папки: `package …cliJvm` → `package …cliJvm.cliArgs`. IntelliJ/Studio будет ругаться на несоответствие path и package, поэтому соответствие держим.
- **Видимость**: на `internal` это не влияет — `internal` это module-private, а не package-private; sub-package видит `internal` родителя в том же compile module. На `public`-классах из родительского пакета тестам всё видно как раньше.
- **Импорты в тестах не меняются** — они уже импортировали по полному имени (или вообще не импортировали, потому что были в том же пакете, что и тестируемый класс). После переноса добавятся `import …cliJvm.CliArgs` и т.п. — это IDE сделает автоматически при компиляции, либо `kotlinc` ругнётся и подскажет.

Когда **не** предлагать папку:
- Разбиение на ровно 2 файла, оба коротких — лишний уровень вложенности дороже визуального шума.
- Если у пользователя уже есть устоявшаяся flat-структура и он явно сказал «не плоди папки» — уважать.

Формулировка предложения: «Разбил на N файлов. Предлагаю положить их в подпапку `<camelCase>/` (package станет `…<camelCase>`), чтобы они не шумели рядом с остальными тестами. Делать?» — и ждать ответа.

## 5. Helpers, константы, factory-функции

**В конце файла**, после всех `@Test`-методов. Имена — **явные**, не однословные:

**Плохо:**
```kotlin
private fun parse(vararg args: String) = CliArgs.from(...)
```

**Хорошо:**
```kotlin
private fun parseCliArgsWithDummyKeys(vararg args: String): CliArgs =
    CliArgs.from(
        args = arrayOf(*args),
        geminiApiKey = DUMMY_GEMINI_KEY,
        openRouterApiKey = DUMMY_OPENROUTER_KEY,
        huggingFaceApiKey = DUMMY_HUGGINGFACE_KEY,
    )
```

**Factory-функции для тестовых данных** — там же, в конце:

```kotlin
private fun message(
    text: String = "hi",
    role: Role = Role.USER,
    tokens: Int? = null,
): Message = Message(text = text, role = role, tokens = tokens)
```

Дефолтные параметры — чтобы тест писал только то, что важно для конкретного кейса.

**Константы** — в `private companion object` в самом низу файла:

```kotlin
private companion object {
    const val DUMMY_GEMINI_KEY = "test-gemini-key"
    const val DUMMY_OPENROUTER_KEY = "test-openrouter-key"
}
```

## 6. Один логический assert на тест

Один тест проверяет **одно поведение**. Если связанные проверки (поля одного объекта) — пачкой ОК:

```kotlin
// then
assertEquals("hi", chat.prompt)
assertEquals(100, chat.maxTokens)
assertEquals(0.7, chat.temperature)
```

— это всё про «параметры одного `Chat`», норм. Но **не** надо в одном тесте проверять, что парсинг прошёл, потом что DB записала, потом что API вернула. Это три разных теста.

Порядок аргументов `assertEquals` — **`expected, actual`** (как в kotlin.test). И **не** `assertTrue(x == y)` — пиши `assertEquals(y, x)`: при провале увидишь `expected: <…> but was: <…>`, а не голый `false`.

## 7. Видимость для тестов — через `internal`, не reflection

Если функция нужна для теста, но не должна быть public API — пометить `internal`. Reflection не использовать. Это сигнал, что либо тест слишком глубоко лезет, либо функция должна быть protected/internal по дизайну.

```kotlin
// в main: ru/den/.../HistoryCompressor.kt
internal fun foldOldestPair(...) { ... }

// в test: HistoryCompressorTest.kt — internal видна, потому что
// test sourceSet делит package и module с main
```

## 8. Тестирование suspend и корутин

**`kotlinx.coroutines.test.runTest`**, не `runBlocking`. `runTest` пропускает delay, виртуальное время, нормальный exception propagation.

```kotlin
import kotlinx.coroutines.test.runTest

@Test
fun `when maybeCompact below threshold - then null returned and api not called`() = runTest {
    // given
    val fakeApi = FakeLlmApi(reply = "should not be called")
    val compressor = HistoryCompressor(api = fakeApi, threshold = 10)

    // when
    val actual = compressor.maybeCompact(messages = listOf(message(), message()))

    // then
    assertNull(actual)
    assertEquals(0, fakeApi.callCount)
}
```

Если на JVM нужен явный `TestDispatcher` (управление временем) — `runTest` его сам создаёт и шарит как `coroutineContext[TestCoroutineScheduler]`. Прокидывать в подсистемы как параметр конструктора, не через `Dispatchers.setMain` — это Android-патерн, тут не нужен.

## 9. Тестирование Flow

Собирать через `.toList()`. Если flow бесконечный или горячий — `take(N).toList()`. Никаких внешних библиотек типа `turbine` — их в проекте нет, не тащить.

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

- Имя: `Fake<Interface>` — `FakeLlmApi`, `FakeSummaryStore`. Имя должно явно говорить, что фейкается.
- Файл: рядом с тестами, в `cliJvmApp/src/test/.../FakeXxx.kt` (не в `main/`).
- Реализует тот же интерфейс, что и реальный класс. Никаких частичных «proxy» — fake полный и самостоятельный.
- Хранит вход в публичных полях (`lastCalledWith`, `callCount`) — тест проверяет их в `// then`.
- Возвращает заготовленный ответ из конструктора (`val reply: String`).

Скелет:

```kotlin
internal class FakeLlmApi(
    private val reply: String = "",
    private val failWith: Throwable? = null,
) : LlmApi {
    var callCount: Int = 0
        private set
    var lastPrompt: String? = null
        private set

    override suspend fun complete(prompt: String, params: GenerationParams): LlmResult {
        callCount++
        lastPrompt = prompt
        failWith?.let { throw it }
        return LlmResult.Success(text = reply, usage = Usage.zero())
    }
}
```

См. `FakeLlmApi.kt`, `TestDb.kt` в проекте для реальных примеров.

## 11. Дискуссионные правила (можно нарушать, если контекст оправдывает)

**E. Параметризация через `forEach`.** Общее правило — **писать N отдельных `@Test`-методов**, не один с `listOf(case1, case2, …).forEach`. Это сохраняет читабельность имён `when X - then Y` и точное сообщение об ошибке.

Но **есть случаи, где `forEach` оправдан**:
- Проверка «USAGE упоминает каждый флаг» — один логический assert, расширяемый список (см. `USAGE mentions every public flag` в `CliArgsTest`).
- Smoke-обход справочного списка (все значения enum, все элементы registry).
- Когда раздувание в 15 одинаковых @Test'ов засорит файл без пользы.

В таких случаях — внутри `forEach` использовать message-параметр `assertX(actual, expected, "message about case")`, чтобы при провале было видно какой именно кейс упал.

**F. `@Before` / `@After` для общего setup.** Общее правило — **не использовать**, фикстуру создавать в `// given` каждого теста (или factory-функцией внизу). `@Before` прячет state, читатель не понимает откуда взялся `db`/`api`.

Но **исключения уместны**:
- Дорогой setup (поднять in-memory DB, см. `TestDb`) — раз создать в `@Before`, потом переиспользовать.
- Очень короткий тест-класс (5-7 тестов) на одну и ту же фикстуру — повторять её в каждом тесте раздражает читателя сильнее, чем `@Before`.

Если используешь `@Before` — назвать поля так, чтобы по имени было ясно что это shared fixture (`private lateinit var sharedDb: TestDb`), и **`@After` обязательно**, если в `@Before` есть resource что закрывается.

## 12. Чего не делать

- **Не тестировать live network.** Никаких реальных вызовов Gemini / OpenRouter / HuggingFace в `:cliJvmApp:test`. Это offline suite. Если очень хочется live-проверки — отдельный module / отдельный helper, не в JVM-test.
- **Не подключать mockk / mockito** для решения «как замокать suspend» — есть handwritten fake (см. §10).
- **Не использовать `Thread.sleep` / `runBlocking { delay(…) }`** для синхронизации. `runTest` даёт виртуальное время; на нём `delay()` пропускается.
- **Не писать assert на «оно не упало»** — если тест просто запускает функцию и не проверяет результат, это не тест. Проверять конкретное наблюдаемое поведение (возвращаемое значение, побочный эффект в fake, exception).
- **Не комментировать `@Ignore` сломанные тесты** — починить или удалить. Если не починить сейчас — открыть отдельный TODO, и не коммитить ignored.

## Проверка после написания

После того как написал/добавил тест:

1. Запустить `./gradlew :cliJvmApp:test` (или `/cli-smoke`, если он есть в проекте).
2. Убедиться что **новые** тесты прошли. Если один упал — починить, не маскировать.
3. Если файл вырос **за** 20 тестов — это **блок** на merge: разбить файл перед коммитом.

## Когда правила противоречат друг другу

Если возникает «у меня есть legacy-файл `Foo.kt` на 60 тестов, мне нужно добавить ещё один — правило 4 говорит разбить, но пользователь не просил рефакторинг» — **не разбивать молча**. Спросить: «Файл уже за лимитом, добавляю тест в текущий стиль (как было) или сначала разнесём по region'ам?» Решение — пользователя.
