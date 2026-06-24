# clicontrols — прототип единой модели CLI-контролов

> **Статус: прототип, в приложение не вшит.** Существующие `CliArgs.from` и
> `parseSlashCommand` не тронуты. Цель пакета — обкатать форму абстракций для
> флагов и команд (обобщение / централизация / управляемость), прежде чем что-то
> рефакторить.

## Зачем

Сейчас разбор стартовых флагов (`CliArgs.from`) и разбор `/`-команд
(`parseSlashCommand`) — два независимых «месива» из констант, `if`-ов и
ручной валидации. Идея flag-command: **один декларативный каталог грамматики
питает оба фронта** (`-` снаружи и `/` внутри). Один и тот же контрол читается
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
   (тип+валидатор декларативно), `requires`/`excludes` (кросс-ограничения декларативно вместо россыпи `if`).
2. **Результат (типизированно)** — `ParsedControl(spec, value, subs)`. `subs` — это **список** (уточнение к
   исходному наброску `sub`/`subValue`): покрывает и вложенную цепочку (`profile work style "x"`), и плоский
   «мешок» опций (`agent main provider X model Y …`).
3. **Парсер** — `CliControlsParser`: один рекурсивный обход по каталогу для обоих фронтов. Токен — *саб*
   текущей цепочки, если так говорит каталог; иначе это значение уровня или принадлежит предку (так
   терминируется каждая опция `agent`). `parseArgv` дополнительно режет argv на контролы и гоняет
   кросс-валидацию.

Грамматика одного контрола: `head [value] (sub)*`. Ошибки — типизированный `ParseError` (UnknownControl /
WrongSurface / MissingValue / BadValue / ValueNotAllowedHere / UnexpectedToken / Requires / Conflicts).

Downstream (не в прототипе): тонкий маппер `ParsedControl → доменная команда` — грамматика остаётся
отделённой от домена (образец доменного «результата» уже есть — sealed `BranchCommand`).

---

## Принятые решения (из обсуждения)

1. **oneshot** подхватывает агента (генпараметры+профиль), но `⟂ tui` (интерактивить нечего).
2. **/help, /?** — палитра, command-only.
3. **/checkpoint → `branch check`** (или вовсе убрать — помечено).
4. **parent = `List<CliControlsArg>?`** (многоуровневая грамматика).
5. **session** — select только на старте; внутри list/show.
6. **memory-mode = `none|system|preamble`** (3 значения), под-опция `agent`.
7. **inflate** — и флаг, и команда (`-inflate N` / `/inflate N`).
8. **agent** — именованная сущность; stage/judge выражаются под-опциями `stages`/`judge`.
9. **rule** — `<name>`-слот = add (не select); на грамматику не влияет, разница в семантике/usage.

## Карта файлов

| Файл | Что |
|---|---|
| `CliControlsArg.kt` | `Surface` + словарь токенов `CliControlsArg` |
| `ControlSpec.kt` | `ValueKind`/`ValueSpec` (декларативная валидация) + `ControlSpec` |
| `CliControls.kt` | каталог `all` + билдеры `entity()/top()/sub()` + lookups |
| `ParsedControl.kt` | `ParsedControl` + `ParseResult`/`ParseError`/`BatchResult` |
| `CliControlsParser.kt` | парсер обоих фронтов + batch + кросс-валидация |
| `…/test/clicontrols/CliControlsParserTest.kt` | демо: одиночный контрол, оба фронта, сабы, surface-гейты, ошибки |
| `…/test/clicontrols/CliControlsBatchTest.kt` | демо: argv→контролы, requires/excludes, целостность каталога |

## Открытые вопросы / не смоделировано

- **MCP / инструменты** — `-mcpServer` смоделирован как flag-command (в main — startup-only, Chat-only).
  Открытый вопрос: инструменты session-wide (как сейчас) или **per-agent** (`agent <name> mcp "<command>"`)
  в agent-as-entity? Tools концептуально — способность агента, так что вторая раскладка может быть точнее.
- Куда деть **legacy free-text** `profile <text>` (перезапись `profile.md`) — пока опущено.
- Место **strategy** (config-флаг-команда рядом с агентом или часть агента/сессии).
- Один `/`-ввод = один контрол; нужно ли несколько контролов в одной строке внутри.
- Маппинг `ParsedControl → доменная команда` (следующий слой).
- `-clean`/`-sessions`/`-memory` как режимы → схлопываются в entity-операции (`session clean`/`session`/`<entity> show`).
