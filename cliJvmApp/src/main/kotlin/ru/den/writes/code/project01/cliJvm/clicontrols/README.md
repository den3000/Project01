# clicontrols — единая модель CLI-контролов

> **Статус: рантайм-фронт обоих путей.** `main` парсит startup-argv через
> `command/CliControlsCommandParser` (`parseArgv` → `ControlsToCommand` → `CliCommand`), а
> in-session `/`-команды — через `parseSlashCommand` → `command/ControlsToBranchCommand`
> (`parse(line, CMD)` → `BranchCommand`). Один каталог (`CliControls.all`) питает оба фронта;
> прежние `CliArgs.from` и ручной `parseSlashCommand`-`when` удалены. `ParseError → CliArgsException`.
> Тесты: `clicontrols/*Test`, `command/CliControlsCommandParser{Mode,Agent,Fields,Memory,Gap}Test`,
> `command/ControlsToBranchCommandTest`.

## Зачем

Раньше разбор стартовых флагов и разбор `/`-команд были два независимых
«месива» из констант, `if`-ов и ручной валидации. Идея flag-command, теперь
реализованная: **один декларативный каталог грамматики питает оба фронта**
(`-` снаружи и `/` внутри). Один и тот же контрол читается
обоими способами; отличается только префикс.

---

## Глоссарий (вычитанный)

- **промпт** — user-сообщение текущего хода (`userTurn`), на основе которого идёт генерация.
- **контекст** — стабильный по смыслу «фон»: правила/ограничения/цели/особенности, доставляемый
  каждый ход. Пересобирается на каждом ходу и **может меняться** (стадия задачи двигается, правила/профиль
  можно править на лету). Это блок из 1+ сообщений. ⚠️ В коде `ContextStrategy` слово «context» означает
  **историю**, а не этот «контекст» — разные вещи.
- **режим отправки контекста** (memory-mode) — КАК **контекст** попадает в модель: `none` (выкл),
  `system` (системный блок), `preamble` (блок user-сообщений в начале списка на каждом ходу).
- **параметры генерации** — зонтик над двумя разными код-абстракциями: `ModelProvider` (provider+model)
  и `GenerationParams` (maxTokens/temperature/stopSequence/endSequence). knobs у всех агентов **общие**.
- **задача** — единица работы с FSM-стадией (`clarification→…→done`), файл `tasks/<id>.md`. Работает и в
  одно-агентном режиме (стадия+заметки в `[Current Task]`); маршрутизация нескольких агентов по стадиям —
  одно из применений.
- **профиль** — набор инструкций (секции style/format/constraints/context), файл `profile.md`/`profiles/<name>.md`.
- **правило** — глобальный инвариант. **Доставляется** в контекст И **энфорсится judge'ом** (независимый
  LLM-вызов проверяет ответ против правил, при нарушении блокирует ход) — вот что делает его инвариантом.
- **слой памяти** — совокупность **задачи + профиля + правил**, которые формируют **контекст**.
- **сессия** — тред сообщений в БД. История **всегда** едет multi-turn; имя (`-session`) даёт персист и
  резюм между запусками, а не способ доставки.
- **ветка** — форк истории внутри сессии (как git-ветка).
- **стратегия** — управление размером истории: `full`/`window`/`facts`/`summary`. `facts`/`summary`
  подставляют **одну синтетическую пару** user(рамка)→assistant(ack) в голову; `full`/`window` — без синтетики.
- **агент** = (**provider+model**) + **профиль**. knobs (maxTokens/…) глобальны.
- **инструменты (MCP)** — внешние tool'ы, которые MCP-сервер отдаёт модели для вызова в ходе хода;
  `-mcpServer "<command>"` спавнит сервер подпроцессом (Chat-only; дефолт — инструментов нет).

---

## Таксономия контролов

Контрол описывается набором **поверхностей** (`Surface`), где он валиден — это и кодирует его «вид»:

| Вид | surfaces | пример |
|---|---|---|
| startup-only | `{FLAG}` | `-prompt`, `-tui`, `-feedFile` |
| command-only | `{CMD}` | `/reuse`, `/exit`, `/help`, `branch` |
| **flag-command** | `{FLAG, CMD}` | `profile`, `task`, `agent`, `strategy`, `inflate`, `mcpServer` |
| сабкоманда | `{SUB}` | `clean`, `show`, `style`, `provider`, `chunkChars` |

## Entity-протокол (CRUD + per-entity расширения)

Сущности (`session/profile/task/rule/branch/agent`) объявляются один раз (`entity()`) и
авто-разворачиваются:

- `<entity> <name>` — выбрать/создать (для `rule` — **добавить** по тексту; «активного» правила нет);
- `<entity>` (без имени) — список (в TUI это пикер);
- `<entity> show [<name>]` — показать одну/все;
- `<entity> clean [<name>]` — удалить одну/все, сбросить выбор.

Per-entity расширения:
- **profile** — секции: `profile <name> <style|format|constraints|context> [<text>]` (без текста = очистить);
- **task** — `task <id> pause|resume`, `task <id> note <text>`;
- **rule** — `rule rm <id>` (удалить по id);
- **branch** — command-only; `branch check` (бывший `/checkpoint`);
- **session** — имя только на старте (`valueSurfaces={FLAG}`): `-session demo` ок, `/session demo` отвергается,
  внутри — только `/session` (список) и `show`.

## Агент как именованная сущность

Поглощает прежние `provider/model/...` и `stageAgent/judgeAgent`:

```
-agent main provider gemini model gemini-2.5-pro profile coder mode system
-agent interviewer ...                           profile interviewer stages clarification..planning
-agent checker     ...                           judge   stages execution..done   # judge = агент без профиля
```
Дефолтный агент — безымянный. `mode` = режим отправки контекста (`none|system|preamble`).

---

## Архитектура: два слоя

1. **Дескриптор-каталог (данные)** — `CliControls.all: List<ControlSpec>`. Один список: токен, поверхности,
   `parent` (цепочка предков для сабов — `List<CliControlsArg>`, поддерживает многоуровневость), `ValueSpec`
   (тип+валидатор декларативно), `requires`/`excludes` (кросс-ограничения декларативно вместо россыпи `if`),
   `parentValueIn` (саб легален только под определённым значением родителя — `summarizeEvery` лишь под `strategy summary`).
2. **Результат (типизированно)** — `ParsedControl(spec, value, subs)`. `subs` — это **список** (уточнение к
   исходному наброску `sub`/`subValue`): покрывает и вложенную цепочку (`profile work style "x"`), и плоский
   «мешок» опций (`agent main provider X model Y …`).
3. **Парсер** — `CliControlsParser`: один рекурсивный обход по каталогу для обоих фронтов. Токен — *саб*
   текущей цепочки, если так говорит каталог; иначе это значение уровня или принадлежит предку (так
   терминируется каждая опция `agent`). `parseArgv` режет argv на контролы (токен с ведущим `-` открывает
   новую группу только если это имя известного контрола — иначе он значение, так доезжают `-3`/`-v`) и
   гоняет кросс-валидацию.

Грамматика одного контрола: `head [value] (sub)*`. Ошибки — типизированный `ParseError` (UnknownControl /
WrongSurface / MissingValue / BadValue / ValueNotAllowedHere / WrongParentValue / UnexpectedToken / Requires /
Conflicts).

Downstream-мапперы живут в `command/` (не здесь): `ControlsToCommand` (`BatchResult.controls` →
`CliCommand`, startup) и `ControlsToBranchCommand` (CMD-строка → `BranchCommand`, in-session).
Грамматика остаётся отделённой от домена.

---

## Принятые решения (из обсуждения)

1. **oneshot** подхватывает агента (генпараметры+профиль), но исключает всё «многоходовое»: top-level
   `session`/`feedFile`/`strategy`/`inflate`/`tui`/`mcpServer`/`profile`/`task`/`rule` и agent-сабы
   `mode`/`stages`/`judge` (`excludes={ONESHOT}` на самих контролах — per-role spec разводит перегруженный
   `profile`: под агентом валиден, top-level нет).
2. **/help, /?** — палитра, command-only.
3. **/checkpoint → `branch check`** (или вовсе убрать — помечено).
4. **parent = `List<CliControlsArg>?`** (многоуровневая грамматика).
5. **session** — select только на старте; внутри list/show.
6. **memory-mode = `none|system|preamble`** (3 значения), под-опция `agent`.
7. **inflate** — и флаг, и команда (`-inflate N` / `/inflate N`).
8. **agent** — именованная сущность; stage/judge выражаются под-опциями `stages`/`judge`.
9. **rule** — `<name>`-слот = add (не select); на грамматику не влияет, разница в семантике/usage.
10. **stage-range** проверяет порядок: `from ≤ to` по FSM (`execution..planning` → `BadValue`), оба конца — известные стадии.
11. **доменность**: `inflate` требует `session` (`requires={SESSION}`, presence-based в `parseArgv`); `summarizeEvery` валиден только под `strategy summary` (`parentValueIn`, value-conditional, оба фронта).
12. **argv-арность**: значение с ведущим `-` (`-3`, `-v`) доезжает до своего флага — новую группу открывает только имя известного контрола.

## Карта файлов

| Файл | Что |
|---|---|
| `CliControlsArg.kt` | `Surface` + словарь токенов `CliControlsArg` |
| `ControlSpec.kt` | `ValueKind`/`ValueSpec` (декларативная валидация) + `ControlSpec` |
| `CliControls.kt` | каталог `all` + билдеры `entity()/top()/sub()` + lookups |
| `ParsedControl.kt` | `ParsedControl` + `ParseResult`/`ParseError`/`BatchResult` |
| `CliControlsParser.kt` | парсер обоих фронтов + batch + кросс-валидация |
| `…/test/clicontrols/CliControlsParserTest.kt` | одиночный контрол (value-equality): оба фронта, сабы, surface-гейты, ошибки |
| `…/test/clicontrols/CliControlsBatchTest.kt` | argv→контролы, arity-split, целостность каталога |
| `…/test/clicontrols/CliControlsCrossValidationTest.kt` | requires/excludes: oneshot-эксклюзивность, inflate→session, feed-mutex |
| `…/test/clicontrols/CliControlsValueValidationTest.kt` | валидация значений: BadValue / WrongSurface / WrongParentValue |
| `…/cliJvm/command/{CliControlsCommandParser,ControlsToCommand,ControlsToBranchCommand}.kt` | рантайм-фронт: controls → `CliCommand` (startup) / `BranchCommand` (in-session) |

## Решено (миграция завершена)

- **Рантайм-свитч сделан** — оба фронта (`-`/`/`) идут через каталог; legacy-парсеры удалены.
- `-clean`/`-sessions`/`-memory`-режимы схлопнуты в entity-операции (`-session [clear [<name>]]`, `-profile`/`-rule`/`-task`).
- **Удаление унифицировано** на `clear` (`<entity> clear [<name>]` = один / все); `rm` выкинут. Дореализованы clear-all-rules, удаление задачи, per-session `session clear <name>`, profile clear-all.
- **Режим памяти** — `agent mode <none|system|preamble>` (startup-саб) / `/agent mode` (in-session); top-level `-memory-mode`/`/memory-mode` убраны.
- **stage/judge** — сабы `-agent … stages <from..to>` / `… judge`, вместо `-stageAgent`/`-judgeAgent`.
- **free-text profile** (`profile <text>`→SetProfile) — дропнут (структурированные секции замещают).
- **show по имени** — `<entity> show <name>` (verb-then-name строго; `<name> show` не команда).

## Открыто

- **MCP / инструменты** — пока session-wide (`-mcpServer`, startup-only, Chat-only); per-agent (`agent <name> mcp …`) не сделано.
- **`agent mode none` live** — `MemoryMode` не моделирует off-state; отключить инъекцию посреди сессии нельзя (только не задать `mode` на старте).
- Место **strategy** — top-level config-флаг-команда (не часть агента/сессии).
- Один `/`-ввод = один контрол; несколько контролов в строке не поддержано.
- **USAGE** — ручной (`command/Usage.kt`); генерация из `CliControls.all`/`ControlSpec.usage` — будущая задача.
