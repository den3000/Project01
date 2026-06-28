# cliJvmApp — консольный LLM-клиент

JVM-приложение, которое шлёт промпт в chat-style LLM (**Gemini**, **OpenRouter** или
**Hugging Face**) и печатает ответ с footer-статистикой: токены и стоимость текущего хода
плюс накопленные итоги сессии. История успешных ходов кладётся в локальный SQLite
(`~/.project01-cli/history.db`), поэтому закрытие/переоткрытие приложения продолжает разговор
с того места, где остановились. Если не передан `-oneshot`, после первого промпта клиент
уходит в REPL — каждая новая строка становится следующим промптом.

Флаги старта (`-flag`) и команды внутри сессии (`/cmd`) разбираются **одним декларативным
каталогом** (`clicontrols`) — см. [«Как устроен парсинг»](#как-устроен-парсинг-clicontrols).

## Быстрый старт

```bash
# собрать запускаемый дистрибутив
./gradlew :cliJvmApp:installDist
BIN=./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp

# один вопрос → ответ → выход
$BIN -prompt "Объясни в двух предложениях, что такое корутина." -oneshot

# чат с персистом: первый промпт + дальше REPL
$BIN -prompt "Привет!" -session demo
```

Можно и без `installDist`: `./gradlew :cliJvmApp:run --args="-prompt <текст> [...флаги]"`.

> ⚠️ **После правки CLI-флагов обязательно пересобирать `installDist`** — иначе старый бинарь
> не знает новых флагов (типовой симптом: новый флаг «прилипает» к значению предыдущего).

### Ключи API

Ключи — **не** аргументы командной строки. Они читаются на этапе сборки из `local.properties`
(gitignored) либо из одноимённых переменных окружения и доступны через `BuildKonfig`:

| Провайдер | Ключ |
|---|---|
| Gemini | `GEMINI_API_KEY` → `BuildKonfig.GEMINI_API_KEY` |
| OpenRouter | `OPENROUTER_API_KEY` → `BuildKonfig.OPENROUTER_API_KEY` |
| Hugging Face | `HUGGINGFACE_API_KEY` → `BuildKonfig.HUGGINGFACE_API_KEY` |

Нужен только ключ выбранного провайдера. Read-only команды (`-session`, `-memory`) и
admin-операции памяти работают **без** ключа вовсе.

## Footer статистики

После каждого ответа печатается блок (в stderr, чтобы не мешать редиректу самого ответа):

```
turn:    prompt=X output=Y thoughts=Z total=T  cost=$N
context: P / W (F%)
session: turns=K prompt=… output=… total=…   cost=$M
```

- `turn` — токены текущего хода; `session` — накопленные итоги (выживают между запусками,
  потому что токены и `model_id` лежат в той же SQLite-строке, что и сообщение).
- `context` — заполнение окна модели (только если окно известно). На 90% в stderr печатается
  `[warning] context window …% full`.
- Стоимость пересчитывается каждый раз из токенов + `model_id` по статической таблице тарифов;
  модели вне таблицы показывают `cost=$? (no pricing)`.
- `thoughts` (thinking-токены) показываются для thinking-моделей Gemini и reasoning-моделей
  Hugging Face (DeepSeek-R1, Qwen3-Thinking, gpt-oss). OpenRouter сворачивает reasoning в
  `output`. **Thinking-токены биллятся как output** — основная статья расхода у сильных моделей.

## Хранение и сессии

Успешные ходы персистятся в один файл `~/.project01-cli/history.db`; строки различаются парой
`session_id` + `branch_id`. По умолчанию вся история шлётся в модель каждый ход (chat-API
stateless), так что multi-turn контекст сохраняется; ограничить рост — через `-strategy`
(см. ниже). Слой памяти (профиль/правила/задача) хранится отдельно, markdown-файлами под
`~/.project01-cli/memory/`, и **в историю не пишется** (инжектится в каждый ход, не как сообщения).

## Режимы запуска

Промпт есть → запускается чат (или `-oneshot`); промпта нет → admin-операция (управление
сессиями/памятью, без обращения к LLM).

| Команда | Что делает |
|---|---|
| `-prompt "<текст>"` | стартовый промпт; без `-oneshot` дальше открывается REPL |
| `-oneshot` | один промпт → ответ → выход; **без** загрузки/записи истории и сессии |
| `-session` | (без значения) список сохранённых сессий и выход |
| `-session clear [<name>]` | удалить историю одной сессии (или **всей** БД, если без имени) и выйти |
| `-inflate <N> -session <name>` | продублировать последние `N` строк сессии обратно в неё (dev-операция для стресс-тестов переполнения контекста; без LLM, без сети) |
| `-tui` | интерактивный TUI-вид (нужен настоящий TTY; несовместим с `-oneshot`) |
| `-schedule …` | фоновые задачи по расписанию (`collect`/`agent`, `after`/`every`) — см. «Планировщик» |

## REPL: команды внутри сессии

В чат-REPL каждая строка — следующий промпт, кроме команд с `/`:

| Команда | Что делает |
|---|---|
| `/exit`, `/quit` (или Ctrl-D) | выйти из REPL |
| `/reuse` | переслать последний ответ модели как следующий промпт (для chain-of-thought) |
| `/help`, `/?` | палитра команд |
| `/branch` | список веток сессии (`*` — текущая) |
| `/branch <name>` | форкнуть новую ветку от текущей (копирует историю + сводку/факты), **без** переключения |
| `/branch switch <name>` | перейти на другую ветку и пере-гидрировать стратегию |
| `/branch show` | текущая ветка + счётчик сообщений |
| `/branch clear <name>` | удалить ветку (без имени — все, кроме текущей; текущую нельзя) |
| `/memory` | показать активный слой памяти (режим + профиль + правила + задача) |
| `/agent mode <preamble\|system>` | переключить режим доставки памяти на лету |
| `/profile …`, `/rule …`, `/task …` | управление слоем памяти (см. ниже) |
| `/schedule` | список активных фоновых задач (с их id) |
| `/schedule collect tool <name> … \| agent prompt "<text>" <after\|every> <sec>` | добавить задачу в живой планировщик |
| `/schedule clear [<id>]` | отменить одну задачу по id (или **все** активные — остановить расписание) |

Ветка — независимый разговор под общим `session_id` (как git-ветка): уйти в касательную на
ветке, потом `/branch switch main` обратно, не потеряв ни одну нить.

## Флаги (startup)

### Агент и параметры генерации — `-agent`

Провайдер, модель и knobs генерации собраны под одним повторяемым контролом `-agent`. Агент
**без** саб-опций `stages`/`judge` — это «primary» (агент по умолчанию):

```
-agent [<name>] provider <gemini|openrouter|huggingface> model <id>
       maxTokens <int> temperature <0..2> stopSequence "<words>" endSequence "<text>"
       profile <name> mode <none|system|preamble> stages <from..to> judge
```

- `provider` — какой API звать (по умолчанию `gemini`); `model` — id модели (неизвестный id
  заворачивается в `Custom` и шлётся как есть).
- `maxTokens` — потолок output-токенов; `temperature` — `0.0..2.0`.
- `stopSequence "<words>"` — слова через пробел, каждое = своя стоп-последовательность
  (Gemini — максимум 5).
- `endSequence "<text>"` — просьба завершить ответ этой строкой (опускается в системную
  инструкцию; best-effort).
- `profile`/`mode`/`stages`/`judge` — слой памяти и маршрутизация (см. ниже).

`maxTokens`/`temperature`/`stopSequence`/`endSequence`/`provider`/`profile` сохраняются между
итерациями REPL — между ходами меняется только промпт.

### Контекст-стратегии — `-strategy`

Как сохранённая история превращается в каждый запрос по мере роста разговора. По умолчанию
`full` воспроизводит «слать всё».

```
-strategy <full|window|facts|summary> [keepLast <int>] [summarizeEvery <int>]
```

- `full` — слать весь разговор дословно каждый ход (prompt-токены растут линейно).
- `window` — скользящее окно: только последние `keepLast` сообщений, остальное отбросить.
  Самый дешёвый предел роста, ценой забывания старого. Без доп. LLM-вызова.
- `facts` — окно **плюс** sticky-память: после каждого user-хода отдельный LLM-вызов сворачивает
  устойчивые детали (цели, ограничения, имена, числа, решения) в маленький JSON, который едет
  дальше даже после того, как ходы ушли из окна.
- `summary` — rolling-summary: старые ходы сворачиваются в одну бегущую сводку (отдельный
  LLM-вызов), запрос становится `[сводка] + хвост` вместо полной истории.
- `keepLast <int>` — сколько хвостовых сообщений держать дословно (по умолчанию `6`, снапается
  вниз до чётного); игнорируется под `full`.
- `summarizeEvery <int>` — только для `summary`: сворачивать, когда поверх хвоста накопилось
  столько сообщений (по умолчанию `10`, минимум 2).

`facts`/`summary` персистят своё состояние per-(session, branch); их доп. LLM-вызовы биллятся
как **overhead** и отдельно показываются в `-session`.

### Feed-режим — `-feedFile`

Заменяет stdin как источник промптов: читает файл последовательными кусками и шлёт каждый как
следующий ход. Полезно для демонстрации монотонного роста контекста — видно, как `session:`
копит токены и стоимость, пока провайдер в конце не отвергнет запрос на переполнении окна.

```
-feedFile <path> [chunkChars <int> | byLine] [feedInstruction "<text>"]
```

- `chunkChars <int>` — размер куска в **символах** (UTF-8-safe; по умолчанию `2500`).
- `byLine` — резать по строкам (одна непустая строка = ход) вместо кусков; взаимоисключающе с `chunkChars`.
- `feedInstruction "<text>"` — префикс перед каждым куском (напр. `"Кратко прокомментируй:"`).

### Слой памяти: профиль / правила / задача

Постоянный слой, который едет в каждом запросе и **не пишется в историю**. По умолчанию выключен.
Включается режимом доставки на primary-агенте; файлами управляют admin-операции (без LLM).

**Режим доставки** — `-agent <…> mode <none|system|preamble>`:
- `preamble` — инжектит как одну пару USER/ASSISTANT в начале списка (любой провайдер);
- `system` — шлёт `Role.SYSTEM`-сообщения, которые провайдер поднимает в нативный system-слот;
- без режима (или `none`) memory-провайдер не создаётся и wire байт-в-байт как без памяти.

Три вида памяти по охвату:

- **Профиль** — инструкции персоны, четыре секции: `style` / `format` / `constraints` / `context`.
  Безымянный `profile.md` — fallback; именованные профили выбираемы и закрепляются за агентом.
  `constraints` персона-локальны: действуют только пока профиль активен.
  - `-profile` — список; `-profile <name>` — создать/тронуть именованный;
    `-profile <section> "<text>"` — добавить bullet в секцию безымянного; `-profile <section>` без
    текста — очистить секцию; `-profile <name> <section> "<text>"` — то же для именованного;
    `-profile show <name>` — показать; `-profile clear [<name>]` — удалить один (или **все**, если без имени).
  - REPL: `/profile <name>` активирует (select = use), `/profile` (список), `/profile show <name>`,
    `/profile <section> "<text>"`, `/profile clear [<name>]`.
- **Правила** — глобальные инварианты (архитектура, стек, бизнес-правила): один плоский нумерованный
  список, инжектится **каждый** ход независимо от активного профиля. Дом для «никогда не нарушать».
  - `-rule "<text>"` — добавить; `-rule clear [<id>]` — удалить одно (или все); правила смотрят
    через `-memory` (общий показ слоя).
- **Задача (FSM)** — задача двигается `clarification → planning → execution → validation → done`
  (один шаг назад разрешён; `done` терминальна). Стадия инжектится каждый ход; модель продвигает её,
  завершив ответ маркером `[[stage:<next>]]`, который CLI валидирует по таблице переходов перед
  применением. Пауза держит стадию.
  - `-task <id>` — создать/выбрать задачу; `-task <id> pause|resume`; `-task clear [<id>]` —
    удалить одну (или все). Заметки к задаче — только в REPL: `/task note "<text>"`.

`-memory` (или `/memory`) показывает весь активный слой: режим + профиль + правила + задача.

### Несколько агентов по стадиям (per-stage)

Разные стадии задачи можно отвечать разными моделями **и** профилями. Повторяй `-agent` с сабом
`stages <from..to>` (требует активной задачи и режима памяти):

- ход уходит агенту, чей диапазон покрывает текущую стадию FSM; непокрытые стадии (и любой ход без
  активной задачи) обслуживает **primary**-агент (`-agent` без `stages`/`judge`), который же несёт
  `mode`;
- в мульти-агентном режиме перед каждым ответом печатается тег `[[AGENT: <profile>:<model>]]`
  (`default`, если профиль не закреплён); в одно-агентном — тега нет (паритет вывода).

### Judge инвариантов

Второй, **независимый** контур enforcement для правил — поверх их инжекта в промпт. Повторяемый
`-agent` с сабом `judge` (без профиля — судье персона не нужна; требует хотя бы один stage-агент):

```
-agent <name> provider <p> model <m> judge stages <from..to>
```

После ответа судья, чей диапазон покрывает активную стадию, отдельным LLM-вызовом (чистый контекст,
без истории) проверяет ответ против глобальных `rules` + `constraints` профиля **ответившего**
агента (и ловит конфликт constraints↔rules). При нарушении: ответ показывается, но ход **не**
сохраняется в историю, стадия держится, печатается баннер `[invariant] …`. Чистый вердикт / стадия
вне покрытия / судья выключен → паритет. Fail-open: ошибка вызова судьи → не блокирует ход.

### MCP-инструменты — `-mcpServer`

`-mcpServer "<команда>"` (Chat-only) поднимает MCP-сервер подпроцессом (напр. `mcpLab --serve`,
см. модуль [`mcpLab`](../mcpLab/README.md)) и отдаёт его инструменты модели. На старте — один
`tools/list`, схема каждого инструмента → Gemini `functionDeclarations`. Когда модель отвечает
`functionCall` вместо текста, CLI выполняет его через `tools/call`, скармливает результат обратно
`functionResponse` и переспрашивает — до нескольких раундов — пока модель не выдаст финальный текст.
Tool-обмен эфемерный (в историю едет только финальный ответ), в транскрипте — колонка `mcp │` (TUI)
/ строки `[tool] …` (plain). **Только Gemini** — другие провайдеры не моделируют function calling.

### Планировщик — `-schedule`

`-schedule` ставит фоновые задачи поверх ядра [`:scheduling`](../scheduling/README.md). Повторяемый —
несколько задач за запуск. Два вида:

- **`collect tool <name> [args "<json>"] <after|every> <sec>`** — по расписанию вызывает MCP-инструмент
  напрямую (нужен `-mcpServer`), **без обращения к модели**, и копит его текст. Раз в ~30 c reporter
  публикует агрегированную сводку feed-строкой (тоже без LLM).
- **`agent prompt "<text>" <after|every> <sec>`** — по расписанию инжектит `<text>` как обычный ход
  диалога (через тот же сериализованный MVI-цикл) — это *тратит токены* на каждый ход.

`after <sec>` = один раз, `every <sec>` = периодически (ровно один из двух). Управление на лету — в REPL:
`/schedule` (список), `/schedule clear <id>` (отменить одну), `/schedule clear` (остановить расписание).
Расписания живут на сессию (`InMemoryScheduleStore`); сводка постится только когда меняется (`agent`-only
расписание не шумит). Без `-schedule` планировщик не поднимается — wire/вывод байт-в-байт прежний.

```bash
# периодический сбор погоды через mcpLab-инструмент (collect — без токенов на сбор)
cliJvmApp -prompt "ok" -mcpServer "$(pwd)/../mcpLab/build/install/mcpLab/bin/mcpLab --serve" \
  -schedule collect tool current_weather args '{"city":"Moscow"}' every 30 -session collect-demo

# периодический агентный ход (тратит токены каждые 60 c)
cliJvmApp -prompt "Старт" -schedule agent prompt "краткая сводка" every 60 -session agent-demo
```

## Каталоги моделей

CLI везёт типизированный enum id-шек; любой неизвестный id заворачивается в `Custom` и шлётся в API
как есть. Списки актуальны на июнь 2026 — рассматривай как ориентир, тарифы/доступность дрейфуют.

**Gemini** ([каталог Google](https://ai.google.dev/gemini-api/docs/models), по умолчанию
`gemini-2.5-flash`):
- **2.5 (GA):** `gemini-2.5-pro`, `gemini-2.5-flash`, `gemini-2.5-flash-lite`
- **3.1 (смешанно):** `gemini-3.1-pro-preview`, `gemini-3-flash-preview` (внимание: id 3.1 Flash
  буквально без `.1` — так его отгружает Google), `gemini-3.1-flash-lite` (GA)
- **3.5:** только `gemini-3.5-flash` — без Pro и Flash-Lite в 3.5

**OpenRouter** ([free-tier роспись](https://openrouter.ai/models?max_price=0), по умолчанию
`openrouter/auto`). Free-роспись быстро протухает (`:free` id мрут 404) — сверять с
`https://openrouter.ai/api/v1/models`, любой текущий id передавать сырым в `model`:
- `openrouter/auto` — meta-роутер, выбирает модель в момент запроса. **Не** `:free` → может
  маршрутизировать на платную.
- `meta-llama/llama-3.3-70b-instruct:free` (131K ctx)
- `google/gemma-4-31b-it:free` (262K ctx)
- `qwen/qwen3-coder:free` (1M ctx)
- `nvidia/nemotron-3-super-120b-a12b:free` (1M ctx)

`google/gemma-3-27b-it` живой, но **платный** (~$0.08/$0.16 за 1M токенов) — не в типизированном
каталоге, но таблица тарифов его знает, так что стоимость посчитается.

**Hugging Face Router**
([Inference Providers chat-completion](https://huggingface.co/docs/inference-providers/tasks/chat-completion),
по умолчанию `meta-llama/Llama-3.3-70B-Instruct`). Router маршрутизирует между бэкенд-провайдерами
(Cerebras, Together, Fireworks, DeepInfra…), реальный тариф зависит от того, кто ответил — цифры в
таблице *приближённые*; free-кредит $0.10/мес сверху. Сверять с `https://router.huggingface.co/v1/models`:
- `meta-llama/Llama-3.3-70B-Instruct` (131K ctx) — general
- `deepseek-ai/DeepSeek-R1` (64K ctx) — reasoning
- `Qwen/Qwen3-4B-Thinking-2507` (256K ctx) — light thinking
- `Qwen/Qwen3.6-35B-A3B` (131K ctx) — MoE general
- `openai/gpt-oss-120b` (131K ctx) — large general / tool calling

## Demo-recipes

После `./gradlew :cliJvmApp:installDist` (`BIN` — путь к бинарю из «Быстрого старта»):

```bash
# Дешёвый реальный прогон на Gemini Flash-Lite. Каждый feed-ход добавляется к итогам
# сессии — смотри, как растёт строка `session:`.
$BIN \
  -agent provider gemini model gemini-2.5-flash-lite \
  -prompt "Получишь файл по кусочкам — комментируй кратко." \
  -feedFile bigfile.txt chunkChars 3000 \
  -session feed-lite

# Стресс-тест: та же модель (окно 1M), куски крупнее. С достаточно большим файлом контекст
# забивается, провайдер в конце отдаёт 4xx; печатается `[error]`, feed-цикл останавливается.
$BIN \
  -agent provider gemini model gemini-2.5-flash-lite \
  -prompt "Кратко прокомментируй каждый кусок." \
  -feedFile bigfile.txt chunkChars 30000 \
  -session feed-bust

# То же против меньшего free-окна OpenRouter — забивается быстрее.
$BIN \
  -agent provider openrouter model "meta-llama/llama-3.3-70b-instruct:free" \
  -prompt "Comment briefly on each chunk." \
  -feedFile bigfile.txt chunkChars 5000 -session feed-or

# Резюм: итоги за всё время (между запусками) восстанавливаются из БД.
$BIN -prompt "продолжай" -session feed-lite

# Ограничить рост контекста стратегией: тот же line-by-line feed, но старые ходы сворачиваются
# в rolling-summary — сравни строку `context:` (и overhead в `-session`) с прогоном `-strategy full`.
$BIN \
  -agent provider gemini model gemini-2.5-flash-lite \
  -prompt "Комментируй кратко, помни ключевые факты." \
  -feedFile bigfile.txt byLine \
  -strategy summary keepLast 6 summarizeEvery 6 \
  -session feed-summary

# Агенты по стадиям: разные модель + профиль на разные стадии задачи. Сначала создаём задачу и
# именованные профили offline (admin, без LLM), затем маршрутизируем стадии (нужны режим памяти +
# активная задача). primary-агент несёт `mode` и служит fallback для непокрытых стадий.
$BIN -task jwt
$BIN -profile interviewer constraints "Сначала уточняй; без кода."
$BIN -profile coder       constraints "Только Kotlin + Ktor; без Spring."
$BIN \
  -prompt "Помоги сделать JWT-логин." \
  -session jwt-demo -task jwt \
  -agent provider gemini model gemini-2.5-flash mode system \
  -agent interviewer provider gemini model gemini-2.5-flash      profile interviewer stages clarification..planning \
  -agent coder       provider gemini model gemini-2.5-flash-lite profile coder       stages execution..done
# → каждый ответ помечен [[AGENT: <profile>:<model>]].

# Инвентарь: число сообщений + токены + стоимость по сессиям.
$BIN -session
```

## Как устроен парсинг (clicontrols)

Раньше разбор стартовых флагов и разбор `/`-команд были два независимых «месива» из констант,
`if`-ов и ручной валидации. Сейчас **один декларативный каталог грамматики питает оба фронта**
(`-` снаружи и `/` внутри): один и тот же контрол читается обоими способами, отличается только
префикс.

### Глоссарий

- **промпт** — user-сообщение текущего хода (`userTurn`), на основе которого идёт генерация.
- **контекст** — стабильный по смыслу «фон»: правила/ограничения/цели/особенности, доставляемый
  каждый ход. Пересобирается на каждом ходу и **может меняться** (стадия задачи двигается,
  правила/профиль правятся на лету). ⚠️ В коде `ContextStrategy` слово «context» означает
  **историю**, а не этот «контекст» — разные вещи.
- **режим доставки контекста** (memory-mode) — КАК контекст попадает в модель: `none` (выкл),
  `system` (системный блок), `preamble` (блок user-сообщений в начале списка каждый ход).
- **задача** — единица работы с FSM-стадией (`clarification→…→done`), файл `tasks/<id>.md`.
- **профиль** — набор инструкций (секции style/format/constraints/context), файл
  `profile.md` / `profiles/<name>.md`.
- **правило** — глобальный инвариант. **Доставляется** в контекст И **энфорсится judge'ом** —
  вот что делает его инвариантом.
- **слой памяти** — совокупность задачи + профиля + правил, формирующих контекст.
- **сессия** — тред сообщений в БД. История **всегда** едет multi-turn; имя (`-session`) даёт
  персист и резюм между запусками, а не способ доставки.
- **ветка** — форк истории внутри сессии (как git-ветка).
- **стратегия** — управление размером истории: `full`/`window`/`facts`/`summary`.
- **агент** = (provider + model) + профиль. knobs (maxTokens/…) глобальны.
- **инструменты (MCP)** — внешние tool'ы, которые MCP-сервер отдаёт модели для вызова в ходе хода.

### Таксономия контролов

Контрол описывается набором **поверхностей** (`Surface`), где он валиден — это и кодирует его «вид»:

| Вид | surfaces | пример |
|---|---|---|
| startup-only | `{FLAG}` | `-prompt`, `-tui`, `-feedFile` |
| command-only | `{CMD}` | `/reuse`, `/exit`, `/help`, `branch` |
| **flag-command** | `{FLAG, CMD}` | `profile`, `task`, `agent`, `strategy`, `inflate`, `mcpServer` |
| сабкоманда | `{SUB}` | `clear`, `show`, `style`, `provider`, `chunkChars` |

### Entity-протокол

Сущности (`session/profile/task/rule/branch/agent`) объявляются один раз (`entity()`) и
авто-разворачиваются:

- `<entity> <name>` — выбрать/создать (для `rule` — **добавить** по тексту; «активного» правила нет);
- `<entity>` (без имени) — список (в TUI это пикер);
- `<entity> show [<name>]` — показать одну/все;
- `<entity> clear [<name>]` — удалить одну/все, сбросить выбор.

**Verb-then-name строго**: `<entity> show|clear <name>` — команда, а `<name> show` — **нет**
(уходит промптом). Удаление везде глагол `clear` (`<name>` = один, bare = все). Многословные
значения — в кавычках.

Per-entity расширения: `profile <name> <section> [<text>]` (секции style/format/constraints/context;
без текста = очистить); `task <id> pause|resume`, `task <id> note <text>` (REPL); `branch switch <name>`;
`session` — имя только на старте (`-session demo` ок, `/session demo` отвергается; внутри — `/session`
список и `show`). «Агент» поглощает прежние `provider/model/…` и stage/judge через саб-опции
`stages`/`judge` (judge = агент без профиля).

### Два слоя

1. **Дескриптор-каталог (данные)** — `CliControls.all: List<ControlSpec>`: токен, поверхности,
   `parent` (цепочка предков для сабов), `ValueSpec` (тип + валидатор декларативно),
   `requires`/`excludes` (кросс-ограничения), `parentValueIn` (саб легален только под определённым
   значением родителя — `summarizeEvery` лишь под `strategy summary`).
2. **Парсер** — `CliControlsParser`: один рекурсивный обход по каталогу для обоих фронтов.
   `parseArgv` режет argv на контролы (токен с ведущим `-` открывает новую группу только если это
   имя известного контрола — иначе он значение, так доезжают `-3`/`-v`) и гоняет кросс-валидацию.
   Результат — `ParsedControl(spec, value, subs)`; ошибки — типизированный `ParseError`.

Downstream-мапперы живут в `command/`: `ControlsToCommand` (startup → `CliCommand`) и
`ControlsToBranchCommand` (CMD-строка → `BranchCommand`). Грамматика остаётся отделённой от домена.

### Карта файлов

| Файл | Что |
|---|---|
| `clicontrols/CliControlsArg.kt` | `Surface` + словарь токенов `CliControlsArg` |
| `clicontrols/ControlSpec.kt` | `ValueKind`/`ValueSpec` (декларативная валидация) + `ControlSpec` |
| `clicontrols/CliControls.kt` | каталог `all` + билдеры `entity()/top()/sub()` + lookups |
| `clicontrols/ParsedControl.kt` | `ParsedControl` + `ParseResult`/`ParseError`/`BatchResult` |
| `clicontrols/CliControlsParser.kt` | парсер обоих фронтов + batch + кросс-валидация |
| `command/CliControlsCommandParser.kt` | рантайм-фронт старта: `parseArgv` → `ControlsToCommand` |
| `command/ControlsToCommand.kt` | controls → `CliCommand` (startup) |
| `command/ControlsToBranchCommand.kt` | CMD-строка → `BranchCommand` (in-session) |

### Текущие ограничения

- **MCP/инструменты** — пока session-wide (`-mcpServer`); per-agent (`agent <name> mcp …`) не сделано.
- **`agent mode none` live** — `MemoryMode` не моделирует off-state; отключить инъекцию посреди
  сессии нельзя (только не задать `mode` на старте).
- **USAGE** — генерируется вручную (`command/Usage.kt`), не из каталога.
- Один `/`-ввод = один контрол; несколько контролов в строке не поддержано.

## Тесты

```bash
./gradlew :cliJvmApp:test
```

Offline и быстро — все провайдеры застаблены через `FakeLlmApi`, сети нет. Парсинг покрыт
`clicontrols/*Test` + `command/CliControlsCommandParser*Test` + `ControlsToBranchCommandTest`;
MVI-стек диалога — через хелпер `runSessionForTest`, байт-в-байт вывод пинит `PlainViewGoldenTest`.
Ядро LLM/pricing/context/memory живёт в `:shared` — `./gradlew :shared:jvmTest`.
