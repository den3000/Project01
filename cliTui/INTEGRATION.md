# Интеграция TUI в `cliJvmApp` — журнал, состояние, план

Спутник к [`README.md`](README.md). README отвечает на вопрос **«какую TUI-либу
взять и почему»** (историческое сравнение Kotter / Mordant / combo на заглушке;
сами экспонаты сведены к одному MVI-стеку — см. git history). Этот файл — про
**реальный перенос выбранного стека (MVI поверх Kotter-каркаса + Mordant-виджетов,
с plain-фолбэком) в боевой `cliJvmApp`**: через что прошли, к чему пришли, что
дальше.

> Сама песочница `cliTui` остаётся изолированной (без зависимостей на
> `:shared`/`:cliJvmApp`) — это полигон, чтобы руками щупать либы.
>
> ⚠️ **Экспериментальная реализация TUI в `cliJvmApp` в этой ветке отменена** —
> она нигде не закоммичена и пишется заново при интеграции. Поэтому ниже —
> **контракт, нюансы и план** (знание, добытое спайком), а НЕ описание
> существующего в дереве кода. Опорная рабочая механика — в песочнице:
> `ChatViewModel.kt` (UI-agnostic MVI-ядро), `MviChat.kt` (Kotter+Mordant вид),
> `KotterView.kt` (словарь рендера), `PlainChat.kt` (plain-фолбэк).

## Как пошли (важная развилка против README)

README предлагал «поднять видимость тонкого фасада `LlmApi` и завести combo с
реальной моделью». На интеграции выбрали **другой путь**: не тащить боевой
`LlmApi` в `cliTui`, а **перенести combo-паттерн в `cliJvmApp`** за новый
презентационный шов. Плюсы: боевые типы остаются `internal`, фасад наружу
поднимать не нужно, TUI и plain делят один диалоговый цикл без дублирования.

## Нюансы и грабли интеграции (через что прошли)

### Архитектура (шов вывода)
- **`AgentView` — презентационный шов.** Весь вывод `Agent` вынесен за интерфейс
  (`reply`/`footer`/`error`/`warning`/`notice`/`sessionSummary`). `Agent` больше
  **не трогает stdout/stderr напрямую**. Две реализации: `PlainView` (старый вывод
  **байт-в-байт**: reply+footer→stdout, остальное→stderr) и `TuiView` (мост в
  Kotter `liveVar`). DTO-снимки (`TurnOutcome`/`SessionSnapshot`/`SessionSummary`/
  `TurnResult`) — примитивы, а НЕ ссылка на живой `SessionStats` (иначе async
  TUI-рендер ловит гонку с накоплением статистики следующего хода).
- **`send()` развязан от `PromptSource`.** Теперь возвращает
  `TurnResult.Ok(reply)` / `Failed(reason)`; хуки `observeReply`/`notifyTurnFailed`
  дёргает `drive()` по результату. Так TUI зовёт `send()` напрямую, не имея
  `PromptSource`. Поле `currentSource` удалено.
- **`run()` разломан** на `start()` (гидрация истории/resume/`rebind` стратегии) и
  `finishSummary(label)`. TUI владеет своим циклом: `start()` → фоновый
  `send(opening)` → `onInputEntered` → `finishSummary()`, не реюзая `run()`.
- **`parseBranchCommand`** поднят в **top-level `internal`** в `PromptSource.kt` —
  одна и та же классификация `/`-команд для stdin-REPL и TUI-ввода.
- **throttle вынесен из `send`.** `delay(16s)` между feed-чанками был внутри
  `send`; стал параметром `drive(throttle=…)`: feed → 16s, интерактив/TUI → 0.

### Презентация: MVI-ядро (форма, отработанная в песочнице)
- **UI-agnostic VM.** `ChatViewModel` отдаёт наружу `state: StateFlow<ChatUiState>`,
  принимает `onIntent(ChatIntent)`, шлёт one-shot через `effects: ReceiveChannel<ChatEffect>`.
  Вся логика переходов — в методе `onIntent`, **без отдельного reducer'а**. Explicit
  backing fields: наружу `StateFlow`/`ReceiveChannel`, внутри класса под тем же именем
  — мутабельные `MutableStateFlow`/`Channel`.
- **Состояние — чистые данные.** `ChatUiState`/`ChatLine`/`PickerState` не знают про
  Kotter/Mordant — единый source of truth. За счёт этого **два вида над одним VM**:
  `MviChat` (Kotter+Mordant, мапит состояние в `KotterView`) и `PlainChat` (plain-фолбэк,
  мапит в строки). Это ровно раскол `AgentView` → `TuiView`/`PlainView`, поднятый на
  уровень presentation-состояния.
- **Единственный писатель — VM.** Конкурентные коллекторы клавиш Kotter не дерутся за
  общую `var` (см. грабли в «Рендеринг»). В бою: цикл agent'а обновляет presentation-
  state, вид(ы) рендерят его и шлют интенты назад.

### Рендеринг (Kotter + Mordant вместе)
- **Конфликт двух ANSI-движков снят** тем же приёмом, что в combo-песочнице:
  Mordant рендерит виджет в строку с `AnsiLevel.NONE` (чистый box-drawing, без
  своих escape-кодов), цвет накладывает Kotter снаружи. Kotter — единственный, кто
  владеет экраном и перерисовкой.
- **Авторитет ширины — Kotter, не Mordant.** В raw-режиме `Terminal().size`
  Mordant'а врёт. Берём `MainRenderScope.width`, захватываем в `section` на каждой
  перерисовке (ловит ресайз).
- **Анти-мерцание через `aside`.** История печатается раз через Kotter `aside`
  (уходит в скроллбэк, больше не перерисовывается). Живая `section` держит только
  маленький нижний блок: busy-индикатор + Mordant-панель `stats` + поле ввода.
  Иначе вся лента мигала бы на каждый кадр.
- **`TuiView` — callback-мост.** `var`-колбэки с no-op дефолтами: agent создаётся
  (с этим view) ДО Kotter-сессии, а сессия проставляет реальные хендлеры в
  `runUntilSignal` перед первым ходом.
- **Word-wrap вручную.** Длинные ответы переносим по словам по ширине терминала с
  выравниванием продолжений под `│` (12-символьный префикс `you/assistant │ `).
  Иначе терминал жёстко заворачивает строку в колонку 0 «лесенкой».
  ⚠️ **Это место НЕ проверено вживую на узком окне** — см. «План Б» ниже.

- **`onKeyPressed` и `onInputEntered` — ДВА независимых коллектора** одного key-flow,
  оба на `Dispatchers.IO`, срабатывают **конкурентно** на одну клавишу (включая Enter).
  Грабли (ловили в спайке): не мутировать общее UI-состояние из `onKeyPressed` для
  клавиш, которые он не «ведёт» (особенно не обнулять состояние на Enter и не делать
  разрушающий `else`); Enter маршрутизировать только через `onInputEntered`. С
  MVI-единственным-писателем это безопасно структурно.
- **Одиночный Esc — отложенный и хрупкий.** Kotter эмитит `Keys.Escape` только через
  ~50 мс дизамбигуации (отличить от CSI-последовательностей стрелок `ESC[…`). Не делать
  Esc единственным способом отмены — давать текстовый fallback (любой не-выбор
  закрывает пикер).

### Гейтинг и границы
- **`-tui` — opt-in, gated на TTY.** Флаг на `CliArgs.Chat` (дефолт false), reject
  с `-oneshot`. В `main.kt`: TUI только при `chat.tui && System.console() != null`.
  feed / oneshot / non-TTY (пайп, IDE, CI) — **всегда plain**.
- **Границы первого захода:** без стрима (`LlmApi.send` отдаёт ответ целиком, без
  SSE), команды — текстом (навигируемого пикера в боевом TUI пока нет, хотя в
  песочнице он уже показан — `MviChat.runMviChat`).

## Опорные детали (чтобы не передобывать)

- `AgentView` — 6 каналов: `reply` / `footer` / `error` / `warning` / `notice` /
  `sessionSummary`. В `PlainView`: `reply`+`footer` → **stdout**, остальные четыре
  → **stderr** (сохранить разделение для редиректа stdout).
- DTO хода — снимки-примитивы (`TurnOutcome`/`SessionSnapshot`/`SessionSummary`),
  снимать с живого `SessionStats` в момент хода (НЕ передавать ссылку — иначе
  async TUI-рендер ловит гонку с накоплением следующего хода).
- Константы: feed-throttle **16 s** (только feed; интерактив/TUI — 0);
  context-warn при **≥ 90 %** окна; префикс ленты **12 символов**
  (`you/assistant │ `), продолжения wrap'а выровнены под `│`.
- Ширина: feed-текст wrap'ится под живую `MainRenderScope.width`; Mordant-панель
  `stats` рендерится фиксированной шириной (в спайке 64) с `AnsiLevel.NONE`.
- Kotter API спайка: `session`/`section`/`liveVarOf`/`runUntilSignal`/`aside`/
  `input`/`onInputEntered`/`onKeyPressed`/`signal`/`clearInput`. `aside` зовётся из
  `RunScope` (внутри `runUntilSignal`), не из `section`. Бóльшая часть — уже в
  `MviChat.kt`/`KotterView.kt` (кроме `aside`-анти-мерцания: песочница перерисовывает
  ленту прямо в `section`).

## Поверхность интеграции (что создаёт/трогает реализация в `cliJvmApp`)

Это **карта будущей работы**, а не текущего дерева (спайк был именно таким, но
отменён). При интеграции в `cliJvmApp` понадобится:
- `AgentView.kt` (новый) — шов вывода + `PlainView` (байт-в-байт прежний вывод) + DTO.
- `Agent.kt` — весь вывод через `view`; `send`→`TurnResult`; `run`→`start`/
  `finishSummary`; `send`/`handleBranchCommand` поднять до `internal`.
- `TuiView.kt` + `TuiChat.kt` — TUI-фронт (`runTuiChat`), по MVI-форме песочницы:
  presentation-state + интенты + one-shot эффекты, единственный писатель — цикл
  agent'а; plain-путь рендерит то же состояние.
- `CliArgs.kt` — флаг `-tui` (+reject с `-oneshot`); `main.kt` — выбор TUI/plain.
- `cliJvmApp/build.gradle.kts` — `implementation(libs.kotter)` + `libs.mordant`
  (записи каталога уже на месте — добавлены коммитом песочницы).
- Тесты — `FakeAgentView`, `AgentViewTest`, `-tui` в `CliArgsModeSelectionTest`,
  `tui=false` протянуть через тест-хелперы.

**Приёмочные проверки, которые должна пройти реализация:** `:cliJvmApp:test`
зелёный; `PlainView` повторяет прежний вывод **байт-в-байт** (без `-tui` wire-list
и stdout/stderr не меняются); `installDist` + cli-smoke (USAGE содержит `-tui`,
exit=1 без stack trace; `-sessions` читает БД); деградация feed/oneshot/non-TTY →
plain.

## Шаги на будущее

- **План Б для word-wrap** (если на узком окне останется «лесенка»): рендерить
  ответ Mordant'ом с **явной шириной** прямо в строку, вместо ручного
  `wrapWords`/`feedLine` по Kotter-ширине. Надёжнее на стыке wide-glyph/рамок.
- **Живая проверка wrap** на узком окне (нужен токен-прогон — спросить).
- **Пикер профилей в боевом TUI** — UX отработан в `MviChat` (Mordant-таблица на
  месте `stats`, `↑↓`/цифра/Enter/Esc), логика выбора — в `ChatViewModel`/`PickerState`;
  нужен read-доступ к списку профилей (`MemoryProvider.store.listProfileNames()` уже
  есть). ⚠️ Esc хрупок (см. «Рендеринг») — оставить и текстовую отмену.
- **Стрим ответа** — SSE в `LlmApi` (отдельный крупный проект; `send` сейчас не
  стримит). В песочнице стрим не демонстрируется (был в удалённом combo-экспонате —
  git history); MVI-вид перерисовывается на любое изменение `state`, так что стрим
  ляжет как обновления одного поля состояния.
- **Прокрутка** длинной истории; **wrap** для `• notice` / `⚠ error`; перечит
  ширины на ресайз каждый ход.
- **При интеграции в `main`** — обновить `CLAUDE.md`: карту (`AgentView`/`TuiView`/
  `TuiChat`) и грабли (`-tui` gating на TTY, ANSI-склейка Kotter+Mordant,
  авторитет ширины — Kotter).

## Как запускать

В этой ветке запускается **только песочница** — флага `-tui` в `cliJvmApp` НЕТ
(реализация отменена):

```bash
./gradlew :cliTui:installDist
./cliTui/build/install/cliTui/bin/cliTui            # MVI поверх Kotter+Mordant
./cliTui/build/install/cliTui/bin/cliTui plain      # тот же VM, plain-фолбэк
```

Целевой запуск ПОСЛЕ повторной интеграции (в этой ветке НЕ работает; живые
LLM-вызовы, расход токенов) — оставлен закомментированным как ориентир:

```bash
# ./gradlew :cliJvmApp:installDist
# ./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp -prompt "…" -tui -session NAME
```
