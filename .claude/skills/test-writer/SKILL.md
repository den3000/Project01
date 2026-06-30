---
name: test-writer
description: How to write Kotlin unit tests in this project's house style — backtick names with "when X - then Y" format, given/when/then body sections, fakes over mocks, runTest for suspend code, file size limit of 15-20 tests. Use this skill whenever the user wants to add, write, or refactor a test in Kotlin — phrases like "напиши тест", "добавь тест", "покрой тестом", "тест на", "write a test for", "add coverage", "test this function" should trigger it, even if they don't mention style. The project has strong stylistic conventions that easily go violated without these rules. Does NOT auto-refactor existing tests — applies to NEW tests only unless the user explicitly asks to rewrite.
---

# Test Writer (cliJvmApp / Kotlin / JUnit 4)

Правила для написания **новых** Kotlin-тестов в этом проекте. Существующие тесты **не трогать** без явной просьбы пользователя — стиль вводится постепенно, рефакторинг старья — отдельная задача.

Стек, который реально используется (не выдумывать другие): `kotlin-test` + `kotlin-test-junit` + JUnit 4, `kotlinx-coroutines-test` (`runTest`). Моков (`mockk`, `mockito`) **нет** и не нужно — только handwritten fakes (см. `cliJvmApp/src/test/.../FakeLlmApi.kt`, `TestDb.kt`).

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
fun `when -entities arg passed - then CliArgs_ListEntities returned`() {
    // given
    val args = arrayOf("-entities")

    // when
    val actual = parseCliArgsWithDummyKeys(args)

    // then
    val expected = CliArgs.ListEntities
    assertEquals(expected, actual)
}
```

Где есть «результат» и «ожидание» — называть `actual` и `expected` явно. Это спасает diff при провале (порядок аргументов `assertEquals` — `expected, actual`, см. §6).

Примеры как тесты писать **не нужно**

1. Не нужно сваливать всё в одну строку - это не читаемо
```kotlin
@Test
fun `when -entities arg passed - then CliArgs_ListEntities returned`() {
    // when - then
    assertEquals(CliArgs.ListEntities, parseCliArgsWithDummyKeys(arrayOf("-entities")))
}
```

2. Не нужно добавлять комментарии к given | when | then секциям
```kotlin
@Test
fun `when -entities arg passed - then CliArgs_ListEntities returned`() {
    // given - one argument entities
    val args = arrayOf("-entities")

    // when - parsing it parseCliArgsWithDummyKeys 
    val actual = parseCliArgsWithDummyKeys(args)

    // then - expect and actual matches
    val expected = CliArgs.ListEntities
    assertEquals(expected, actual)
}
```

3. Не нужно разделять секцию ассертов доп. комментариями
```kotlin
@Test
fun `when -entities arg passed - then CliArgs_ListEntities returned`() {
    // given
    val args = arrayOf("-entities")

    // when 
    val actual = parseCliArgsWithDummyKeys(args)

    // then
    // expected value - ListEntities
    val expected = CliArgs.ListEntities
    // expected matches ListEntities
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
private fun parseCliArgsWithDummyKeys(vararg args: String): CliArgs =
    CliArgs.from(
        args = arrayOf(*args),
        geminiApiKey = DUMMY_GEMINI_KEY,
        openRouterApiKey = DUMMY_OPENROUTER_KEY,
        huggingFaceApiKey = DUMMY_HUGGINGFACE_KEY,
    )
```

**Плохо:**
```kotlin
private fun parse(vararg args: String) = CliArgs.from(...)
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

- **Не тестировать live network.** Никаких реальных вызовов Gemini / OpenRouter / HuggingFace в `:cliJvmApp:test`. Это offline suite. Если очень хочется live-проверки — отдельный module / отдельный helper, не в JVM-test.
- **Не подключать mockk / mockito** для решения «как замокать suspend» — есть handwritten fake (см. §10).
- **Не использовать `Thread.sleep` / `runBlocking { delay(…) }`** для синхронизации. `runTest` даёт виртуальное время; на нём `delay()` пропускается.
- **Не писать assert на «оно не упало»** — если тест просто запускает функцию и не проверяет результат, это не тест. Проверять конкретное наблюдаемое поведение (возвращаемое значение, побочный эффект в fake, exception).
- **Не комментировать `@Ignore` сломанные тесты** — починить или удалить. Если не починить сейчас — открыть отдельный TODO, и не коммитить ignored.

## 13. Grammar-тесты парсинга (clicontrols/grammar)

Узкий, но строгий паттерн для тестов разбора CLI-грамматики (`CliControlsParser` → `ParsedControl`). Эталон — `clicontrols/grammar/CliEntityGrammarTest.kt`. Хелперы — `clicontrols/CliControlsTestUtils.kt` (`assertMatchParserCmd` / `assertMatchParserFlag` / `assertMatchParserError`, `ExpectedControl` / `top` / `sub` / `toArgsList`).

- **Один `@Test` на (контрол × фронт).** Имя: `` `when <control> <front> grammar used - then it is parsed accordingly` `` (или `- then it fails` для чисто-негативного, как branch на FLAG).
- **given выделяет всё:** `cli` (CliControlsArg), `sfc` (Surface), `cmd` (строка `-flag` / `/cmd`), значения (`name` / `text` / `id` / …). НЕ инлайнить значения, фронт или имена прямо в `then` — всё в given. `// then` — голый, без пояснений (формы говорят сами за себя).
- **Паритет фронтов.** FLAG-тест повторяет формы CMD-теста один-в-один (плюс FLAG-специфичные негативы). FLAG не должен быть беднее CMD. Surface-асимметрия (value только на одном фронте, контрол command-only) — выражается явно негативом, не молчаливым пропуском.
- **Исчерпывающее покрытие, не «представители».** Все варианты sub / секции / режима / enum-значения — через `forEach` по списку (идиома §11.E здесь **норма**, не исключение). Покрыл `style` → покрой все 4 секции (+ форма «секция без текста = clear», + unnamed); покрыл `window` → покрой все 4 strategy-режима (+ combo); покрыл 2 agent-sub → покрой все 10. «Доказал механизм на одном» — НЕ достаточно: тест-документация должна показывать каждую форму.
- **Позитив и негатив рядом** в region своего контрола. Негативы — по природе фронта: CMD → `assertMatchParserCmd` / single `parse`; FLAG → `assertMatchParserFlag` / `parseArgv`. Cross-validation (`Conflicts` / `Requires`) ловится только через `parseArgv` → её место в `crossvalidation/`, не в per-control grammar-файле. Мета-инварианты каталога (`CliControls.all`) — не грамматика, отдельный `CliControlsCatalogTest`.
- **Контрол обоих фронтов → два теста** (`command` + `flag`), каждый со своим `sfc` в given. Контрол одного фронта → один тест + негатив на неверном фронте (`WrongSurface` через `cli.title`).

## Проверка после написания

После того как написал/добавил тест:

1. Запустить `./gradlew :cliJvmApp:test` (или `/cli-smoke`, если он есть в проекте).
2. Убедиться что **новые** тесты прошли. Если один упал — починить, не маскировать.
3. Если файл вырос **за** 20 тестов — это **блок** на merge: разбить файл перед коммитом.

## Когда правила противоречат друг другу

Если возникает «у меня есть legacy-файл `Foo.kt` на 60 тестов, мне нужно добавить ещё один — правило
4 говорит разбить, но пользователь не просил рефакторинг» — **не разбивать молча**. Спросить: «Файл 
уже за лимитом, добавляю тест в текущий стиль (как было) или сначала разнесём по region'ам?» Решение — пользователя.
