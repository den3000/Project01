# demo/weekly-review — недельный разбор продуктивности

Реальный AI-пайплайн под конкретную личную задачу: **раз в неделю сравнить, что я запланировал и на что
реально ушло время, и получить рекомендации по продуктивности**, не сводя два приложения руками.

Источники — сервисы, которыми я пользуюсь:
- **[TickTick](https://ticktick.com)** — план недели (задачи-тайм-блоки с началом и концом),
- **[aTimeLogger](https://atimelogger.pro)** — фактически потраченное время по активностям.

Собрано на наработках проекта: два MCP-сервера-обёртки над реальными HTTP-API + уже существующий агент
`cliJvmApp` (tool-loop + LLM Gemini) как оркестратор. Без судьи/FSM/RAG — только два MCP и агент.

## Как участвует AI

Агент по одной цели («разбери мою неделю») сам вызывает два инструмента и сводит их:
1. `week_plan` (ticktick-mcp) → **план**: запланированные часы по активностям (сумма длительностей тайм-блоков);
2. `time_by_activity` (atimelogger-mcp) → **факт**: реально потраченные часы по активностям;
3. LLM сопоставляет план и факт по активностям (в т.ч. **сам мапит разные названия** — напр. TickTick
   «Подготовка к собесам» ↔ aTimeLogger «Study»), отмечает пере-/недо-выполнение и даёт рекомендации.

Инструменты возвращают **только факты** — сравнение и советы делает модель. Персона аналитика и формат
разбора заданы системным профилем `weekly` (`setup-weekly.sh`).

## Архитектура

```
                       cliJvmApp (агент + LLM Gemini)
                                │  tool-loop
             ┌──────────────────┴───────────────────┐
      ticktick-mcp                             atimelogger-mcp
   (Open API, OAuth2)                          (v2 API, Basic)
   week_plan  ──► план недели (часы/актив.)    time_by_activity ──► факт (часы/актив.)
```

Обе стороны — «часы по активностям» за один диапазон, поэтому LLM сравнивает их напрямую.

## Доступы (как получить)

Все креды читаются **только из окружения** — в argv и в транскрипт не попадают. Положи их в
`~/.project01-weekly.env` (или в `.env` рядом со скриптом — оба в `.gitignore`); `review-week.sh` сам
подхватит файл, если переменные не выставлены в шелле (ручной `export`/`source` имеет приоритет).

Итоговый `~/.project01-weekly.env`:
```bash
export ATIMELOGGER_USERNAME='<логин aTimeLogger>'
export ATIMELOGGER_PASSWORD='<пароль aTimeLogger>'
export TICKTICK_ACCESS_TOKEN='<OAuth2 access token>'
export GEMINI_API_KEY='<ключ Gemini>'   # можно опустить, если он в local.properties
export WEEK_TZ='Europe/Moscow'          # опц.: зона для границ недели (дефолт — зона машины)
```
Потом `chmod 600 ~/.project01-weekly.env` — это файл с секретами.

### aTimeLogger — логин/пароль (HTTP Basic)

API aTimeLogger v2 авторизуется **теми же логином и паролем, что и аккаунт в приложении** (это твой
реальный пароль, отдельного токена нет). Впиши их в `ATIMELOGGER_USERNAME`/`ATIMELOGGER_PASSWORD` — сервер
соберёт из них заголовок `Authorization: Basic …` один раз и никуда не залогирует.

### TickTick — OAuth2 access token

Готового токена в настройках нет — он выдаётся разовым OAuth2-обменом с зарегистрированным приложением:

1. **Зарегистрировать приложение:** https://developer.ticktick.com/manage → создать app → получить
   **Client ID** и **Client Secret**. В поле **OAuth redirect URL** вписать и **сохранить**
   `http://localhost:8000/callback` — без зарегистрированного redirect шаг 2 вернёт
   `invalid_request: At least one redirect_uri must be registered`.
2. **Авторизоваться (получить `code`):** открыть в браузере (подставив свой `CLIENT_ID`):
   ```
   https://ticktick.com/oauth/authorize?client_id=CLIENT_ID&redirect_uri=http://localhost:8000/callback&response_type=code&scope=tasks:read&state=x
   ```
   Подтвердить → браузер редиректнёт на `http://localhost:8000/callback?code=XXXX&state=x` (страница не
   загрузится — это ок; нужен только `code` из адресной строки).
3. **Обменять `code` на токен** (сразу — код живёт минуты и одноразовый):
   ```bash
   curl -s -X POST https://ticktick.com/oauth/token \
     -H "Content-Type: application/x-www-form-urlencoded" \
     -d "client_id=CLIENT_ID&client_secret=CLIENT_SECRET&code=XXXX&grant_type=authorization_code&redirect_uri=http://localhost:8000/callback"
   ```
   В JSON-ответе `access_token` → в `TICKTICK_ACCESS_TOKEN`. Scope `tasks:read` достаточно (сервер только
   читает), токен живёт ~6 мес. `redirect_uri` должен **буквально совпадать** в шагах 1–3.

### Gemini — ключ модели

Агент ходит в Gemini. Если `GEMINI_API_KEY` уже в `local.properties` (→ BuildKonfig), в env его класть не
нужно; иначе добавь строку в env-файл (env перекрывает BuildKonfig).

## Запуск

```bash
# 1) один раз: сборка бинарей + профиль weekly
bash demo/weekly-review/setup-weekly.sh

# 2) разбор недели (from включительно, to ИСКЛЮЧИТЕЛЬНО — день после последнего)
bash demo/weekly-review/review-week.sh 2026-07-13 2026-07-20
```

Один прогон — один ход агента: он вызывает `week_plan` и `time_by_activity` за эти даты и печатает разбор.
Даты интерпретируются в зоне `WEEK_TZ`.

## Пример живого вывода (неделя 2026-07-13…20)

```
[tool] week_plan → Отдых 22h, Подготовка к собесам 20h, Ai Advent Challenge 16h, Гитара 8h … Total 79h
[tool] time_by_activity → Sleep 50h, Rest 29h43m, Own Projects 24h14m, Study 14h41m, Guitar 6h43m … Total 168h
LLM: Подготовка к собесам недобрал (план 20ч → факт Study 14ч41м); Own Projects перебрал (16ч → 24ч14м); … + рекомендации.
```

## Ограничения / грабли (честно)

- **План = задачи со временем начала-конца.** Задачи без времени (all-day, без `startDate/dueDate`) дают 0
  часов и в план не попадают. Именно тайм-блоки календаря и есть план.
- **Названия в TickTick и aTimeLogger разные** — сопоставление делает LLM по смыслу (в промпте/профиле).
- **Синхронизация Open API не мгновенная**: только что добавленные в приложении задачи появляются в API с
  задержкой (секунды-минуты) — если план пуст, подожди и повтори.
- Официальный TickTick Open API возвращает только незавершённые задачи и не разворачивает повторяющиеся
  (RRULE) в инстансы — для тайм-блоков это не мешает (они отдельные и незавершённые).
- Альтернатива для workflow «сделано/не сделано» (задачи с due-date, которые отмечают выполненными) —
  тулы `snapshot_week`/`review_week` в ticktick-mcp (в этом демо не используются).
