# Интеграция TUI в `cliJvmApp` — журнал, состояние, план

Спутник к [`README.md`](README.md). README отвечает на вопрос **«какую TUI-либу
взять и почему»** (историческое сравнение Kotter / Mordant / combo; экспонаты
сведены к одному MVI-стеку — см. git history). Этот файл — про **реальный перенос
в боевой `cliJvmApp` по архитектуре MVI** (Kotter+Mordant как слой рендера +
plain-фолбэк): к чему пришли в песочнице, как это ложится на боевой код, что дальше.

> Сама песочница `cliTui` остаётся изолированной (без зависимостей на
> `:shared`/`:cliJvmApp`) — это полигон, чтобы руками щупать либы.
>
> ✅ **Реализовано** в ветке `feature/tui-integration` по этому контракту:
> `SessionViewModel` + `TurnEngine` + `CommandRunner` (ядро), `PlainView`
> (вывод байт-в-байт), `tui/TuiView` (Kotter+Mordant), флаг `-tui` gated на
> TTY. Ниже — исходный контракт и план (оставлен как запись решений); карта
> боевого кода и грабли — в `CLAUDE.md`.

## Развилка: листенер-шов → MVI

Прежний (откатанный) спайк планировал **листенер-шов**: интерфейс `AgentView` с
каналами `reply`/`footer`/`error`/`notice`/…, в который `Agent` **пушит** вывод;
`PlainView`/`TuiView` — пассивные приёмники. Это observer/push.

**Новый путь — MVI** (отработан в песочнице): выделяется **ViewModel, которая
держит основной цикл** и отдаёт наружу единый **`StateFlow<UiState>`**, принимает
**интенты** и шлёт **one-shot эффекты**. Виды **наблюдают** состояние и **шлют**
интенты — их не «дёргают» колбэками. Почему так лучше:
- **единственный писатель состояния** — VM; конкурентные input-коллекторы TUI не
  дерутся за общую `var` (см. грабли в «Рендеринг»);
- **один VM под plain И TUI** без дублирования логики — разница только в рендере
  одного и того же `UiState` и в наборе интентов, которые вид умеет послать;
- **ядро тестируется offline** без терминала (интент → состояние), как песочный
  `ChatViewModel`.

(Развилка против README сохраняется: боевой `LlmApi` в `cliTui` НЕ тащим — типы
остаются `internal`, MVI-форму переносим в `cliJvmApp`.)

## Точка отсчёта: что держит цикл сейчас

`Agent.run()`/`drive()` сам держит диалоговый цикл **и** пишет в stdout/stderr
напрямую: `println(reply)` + footer → **stdout**; `[session]`/`[task]`/`[warning]`/
`[error]`/`[branch]`/`[memory]` → **stderr**. `handleBranchCommand` исполняет
`/`-команды. `send(prompt)` — один ход (strategy.onTurn → wire-list → `llmApi.send`
→ persist + печать + `maybeAdvanceTaskStage` + `delay(16s)`); исход прокидывается
в источник хуками `observeReply`/`notifyTurnFailed`. Ввод — `PromptSource`
(stdin/feed/oneshot). То есть **цикл, движок хода и вывод слиты в `Agent`** — это и
надо расслоить.

## Целевая архитектура (MVI)

### Шаг 1 — выделить ViewModel, держащую цикл
Главный первый шаг: вынуть из `Agent` **цикл** в `SessionViewModel` (рабочее имя; по
форме песочного `ChatViewModel`), который:
- держит диалоговый цикл (нынешние `run`/`drive`): гидрация/resume истории,
  оркестрация ходов, обработка команд, финальная session-summary;
- отдаёт `state: StateFlow<UiState>` (наружу read-only; внутри `MutableStateFlow`
  через **explicit backing field**);
- принимает `onIntent(intent)` — вся логика переходов в **методе**, без отдельного
  reducer'а;
- шлёт `effects: ReceiveChannel<…>` для one-shot (session-summary готова, exit).

### Движок хода (model)
`Agent.send()` становится **чистым движком хода**: `send(prompt): TurnResult`
(`Ok(reply, usage-снимок, footer-числа, task-advance)` / `Failed(reason)`), **без
прямого I/O**. VM зовёт его и складывает результат в `UiState`. `delay(16s)` убрать
из `send` — это троттл feed-источника, а не движка (см. «Опорные детали»).

### Состояние и интенты
- **`UiState` — снимки-примитивы**, не живые объекты: лента ходов, footer/stats-
  снимок (turns/tokens/cost/context-fill), busy, активная задача, (опц.) пикер.
  ⚠️ **НЕ держать ссылку на живой `SessionStats`** — async-рендер TUI поймает гонку
  с накоплением статистики следующего хода; класть снимок на момент хода.
- **Интенты:** `Submit(text)`; `/`-команды (классификация сейчас в `PromptSource` →
  `PromptResult.Command`; переиспользовать один классификатор для stdin-REPL и TUI —
  в спайке его поднимали в top-level `internal` в `PromptSource.kt`); `Exit`. TUI
  добавляет `CursorUp`/`CursorDown`/`Cancel` для пикера.
- **Эффекты (one-shot):** `SessionSummary`, `Exit`; при желании transient
  `Notice`/`Error`.
- **`PromptSource` → источник интентов.** Текущая абстракция ложится как продюсер
  интентов: строки stdin → `Submit`/команда, feed-чанки → `Submit` (+троттл),
  oneshot → один `Submit`, затем `Exit`. Хуки `observeReply`/`notifyTurnFailed`
  уходят: VM сам знает исход (`TurnResult`) и решает, продолжать ли feed.

### Виды (вместо листенеров `PlainView`/`TuiView`)
Оба **наблюдают `state` и шлют интенты** (не получают пуш):
- **PlainView** — plain-рендер `UiState`, воспроизводит прежний вывод **БАЙТ-В-БАЙТ**:
  reply + footer → **stdout**, `[session]`/`[task]`/`[warning]`/… → **stderr**
  (разделение для редиректа stdout). Дефолт для feed/oneshot/non-TTY. ⚠️ Тонкое
  место: точная последовательность строк и stdout/stderr-раскладка — рендерить
  дельты состояния по ходам, не «весь экран».
- **TuiView** — Kotter+Mordant рендер `UiState` (форма `TuiChat.runTuiChat`
  песочницы): лента через `aside`, живая `section` = busy + Mordant-панель `stats`
  + поле ввода.

## Рендеринг TUI (Kotter + Mordant) — грабли

- **Конфликт двух ANSI-движков снят** приёмом из combo: Mordant рендерит виджет в
  строку с `AnsiLevel.NONE` (чистый box-drawing, без своих escape-кодов), цвет
  накладывает Kotter снаружи. Kotter — единственный, кто владеет экраном.
- **Авторитет ширины — Kotter, не Mordant.** В raw-режиме `Terminal().size`
  Mordant'а врёт. Берём `MainRenderScope.width`, захватываем в `section` на каждой
  перерисовке (ловит ресайз).
- **Анти-мерцание через `aside`.** Лента печатается раз через Kotter `aside`
  (уходит в скроллбэк, не перерисовывается). Живая `section` держит только нижний
  блок: busy + Mordant-`stats` + ввод. Иначе вся лента мигает на каждый кадр.
  (В песочнице ленту пока перерисовывает прямо `section` — для боевого нужен `aside`.)
- **Мост состояния — `StateFlow` → `liveVar`.** Вид собирает `vm.state` в один
  Kotter `liveVar` (как `work.launch { vm.state.collect { ui = it } }` в песочнице);
  `section` рендерит из `ui`, хендлеры шлют интенты. Никаких callback-no-op (это был
  листенер-дизайн).
- **Word-wrap вручную.** Длинные ответы переносим по словам по ширине терминала с
  выравниванием продолжений под `│` (12-символьный префикс `you/assistant │ `).
  Иначе терминал заворачивает в колонку 0 «лесенкой». ⚠️ **НЕ проверено на узком
  окне** — см. «План Б».
- **`onKeyPressed` и `onInputEntered` — ДВА независимых коллектора** одного key-flow,
  оба на `Dispatchers.IO`, срабатывают **конкурентно** на одну клавишу (включая Enter).
  Не мутировать общее UI-состояние из `onKeyPressed` для клавиш, которые он не «ведёт»
  (особенно не обнулять на Enter и не делать разрушающий `else`); Enter — только через
  `onInputEntered`. С MVI-единственным-писателем это безопасно структурно.
- **Одиночный Esc — отложенный и хрупкий.** Kotter эмитит `Keys.Escape` только через
  ~50 мс дизамбигуации (отличить от CSI-последовательностей стрелок `ESC[…`). Не
  делать Esc единственным способом отмены — давать текстовый fallback (любой не-выбор
  закрывает пикер).

## Гейтинг и границы
- **`-tui` — opt-in, gated на TTY.** Флаг на `CliArgs.Chat` (дефолт false), reject с
  `-oneshot`. В `main.kt`: TUI-вид только при `chat.tui && System.console() != null`,
  иначе plain-вид над тем же VM. feed / oneshot / non-TTY (пайп, IDE, CI) — **всегда
  plain**.
- **Границы первого захода:** без стрима (`LlmApi.send` отдаёт ответ целиком, без
  SSE), команды — текстом (навигируемый пикер уже показан в песочнице —
  `TuiChat.runTuiChat`, но в боевом TUI первого захода может не быть).

## Опорные детали (чтобы не передобывать)
- **`UiState` — снимки**, не живой `SessionStats` (иначе async-рендер ловит гонку с
  накоплением следующего хода). Для PlainView вывод разделён: reply + footer →
  **stdout**, `[session]`/`[task]`/`[warning]`/… → **stderr** (сохранить для редиректа).
- **Троттл feed = 16 s** живёт на feed-источнике интентов, не в движке хода;
  интерактив/TUI → 0. **Context-warn ≥ 90 %** окна. **Префикс ленты — 12 символов**
  (`you/assistant │ `), продолжения wrap'а выровнены под `│`.
- Ширина: feed-текст wrap'ится под живую `MainRenderScope.width`; Mordant-панель
  `stats` рендерится фиксированной шириной с `AnsiLevel.NONE`.
- Kotter API: `session`/`section`/`liveVarOf`/`runUntilSignal`/`aside`/`input`/
  `onInputEntered`/`onKeyPressed`/`signal`/`clearInput`. `aside` зовётся из `RunScope`
  (внутри `runUntilSignal`), не из `section`. Бóльшая часть — в песочнице
  `TuiChat.kt`/`KotterView.kt` (кроме `aside`-анти-мерцания).

## Поверхность интеграции (что создаёт/трогает реализация в `cliJvmApp`)

Это **карта будущей работы**. При интеграции понадобится:
- `SessionViewModel.kt` (новый) — держит цикл + `state`/`onIntent`/`effects` (форма
  песочного `ChatViewModel`): гидрация/resume, оркестрация ходов, команды, summary.
- `Agent.kt` — `send`→`TurnResult` **без прямого I/O**; логика `run`/`drive` уезжает
  в VM; классификатор `/`-команд — в top-level `internal`.
- `PlainView.kt` (новый) — рендер `UiState` **байт-в-байт** (заменяет прямую печать
  `Agent`).
- `TuiView.kt` + `TuiChat.kt` (новые) — Kotter+Mordant рендер `UiState` (форма
  песочного `runTuiChat`): мост `StateFlow`→`liveVar`, ввод→интенты.
- `CliArgs.kt` — флаг `-tui` (+reject с `-oneshot`); `main.kt` — собрать VM и выбрать
  вид (TUI при `tui && TTY`, иначе plain).
- `cliJvmApp/build.gradle.kts` — `implementation(libs.kotter)` + `libs.mordant`
  (записи каталога уже на месте).
- Тесты — VM offline (интент→`state`, как тестируем песочный `ChatViewModel`), fake
  `LlmApi`/движок хода, **golden на байт-в-байт PlainView**, `-tui` в
  `CliArgsModeSelectionTest`.

**Приёмочные проверки:** `:cliJvmApp:test` зелёный; PlainView повторяет прежний
вывод **байт-в-байт** (без `-tui` wire-list и stdout/stderr не меняются);
`installDist` + cli-smoke (USAGE содержит `-tui`, exit=1 без stack trace;
`-sessions` читает БД); деградация feed/oneshot/non-TTY → plain.

## Шаги на будущее
- **План Б для word-wrap** (если на узком окне останется «лесенка»): рендерить ответ
  Mordant'ом с **явной шириной** прямо в строку, вместо ручного `wrapWords` по
  Kotter-ширине. Надёжнее на стыке wide-glyph/рамок.
- **Живая проверка wrap** на узком окне (нужен токен-прогон — спросить).
- **Пикер профилей в боевом TUI** — UX отработан в `TuiChat.runTuiChat`
  (Mordant-таблица на месте `stats`, `↑↓`/цифра/Enter/Esc), логика выбора — в
  `ChatViewModel`/`PickerState`; нужен read-доступ к списку профилей
  (`MemoryProvider.store.listProfileNames()` уже есть). ⚠️ Esc хрупок (см.
  «Рендеринг») — оставить и текстовую отмену.
- **Стрим ответа** — SSE в `LlmApi` (отдельный крупный проект; `send` сейчас не
  стримит). В MVI стрим ложится естественно: вид перерисовывается на любое изменение
  `state`, поэтому стрим = последовательные апдейты одного поля `UiState`.
- **Прокрутка** длинной истории; **wrap** для `• notice` / `⚠ error`; перечёт ширины
  на ресайз каждый ход.
- **При интеграции в `main`** — обновить `CLAUDE.md`: карту (`SessionViewModel` /
  `PlainView` / `TuiView` / `TuiChat`) и грабли (`-tui` gating на TTY, ANSI-склейка
  Kotter+Mordant, авторитет ширины — Kotter, гонка input-коллекторов).

## Как запускать

Сейчас запускается **только песочница** (флага `-tui` в `cliJvmApp` НЕТ):

```bash
./gradlew :cliTui:installDist
./cliTui/build/install/cliTui/bin/cliTui            # TUI-вид (Kotter+Mordant)
./cliTui/build/install/cliTui/bin/cliTui plain      # plain-вид (тот же VM)
```

Целевой запуск ПОСЛЕ интеграции (в этой ветке НЕ работает; живые LLM-вызовы,
расход токенов) — ориентир:

```bash
# ./gradlew :cliJvmApp:installDist
# ./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp -prompt "…" -tui -session NAME
```
