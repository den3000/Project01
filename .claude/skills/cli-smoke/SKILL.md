---
name: cli-smoke
description: Offline smoke check for cliJvmApp — runs the JVM unit tests, rebuilds installDist, then verifies the freshly-built binary boots (USAGE on no args) and that `-sessions` works without network. Use when the user says "smoke", "прогони smoke", "проверь cliJvmApp", "smoke check the CLI", "after CLI flag changes", or asks to verify the binary still works without burning LLM tokens. Does NOT make any network calls.
---

# CLI Smoke (cliJvmApp)

Быстрая offline-проверка, что `cliJvmApp` собирается и поднимается. Без сети, без LLM-вызовов, без расхода токенов. Гонять после правки `Agent`/`CliArgs`/`*Api`/стратегий и **всегда** после правки CLI-флагов (иначе старый бинарь не знает новых — типовая грабля из CLAUDE.md).

## Шаги

Выполнять последовательно. На первой ошибке — стоп, показать пользователю последние ~30 строк вывода, дальше не идти.

1. **Offline-тесты:**
   ```
   ./gradlew :cliJvmApp:test
   ```
   Ожидается: `BUILD SUCCESSFUL`. Если есть `FAILED` тест — стоп.

2. **Пересборка дистрибутива** (обязательно — иначе бинарь не знает новых флагов):
   ```
   ./gradlew :cliJvmApp:installDist
   ```
   Ожидается: `BUILD SUCCESSFUL`. Бинарь должен оказаться по пути `./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp`.

3. **Smoke-1: USAGE без аргументов.** Бинарь должен напечатать USAGE и завершиться с ненулевым кодом, **без stack trace**:
   ```
   ./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp
   ```
   Ожидается: текст, начинающийся с `Usage: -prompt …`. Любой `Exception in thread …` / `at ru.den.writes…` → стоп, это регрессия.

4. **Smoke-2: список сессий** (читает БД, в сеть не лезет):
   ```
   ./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp -sessions
   ```
   Ожидается: либо список session id, либо пустой вывод / "no sessions" — без exception. Stack trace → стоп.

## Итог

Один-два абзаца: что прошло, что упало, и одна-две строки незаэкспоженного релевантного вывода. Если всё зелёное — короткое «smoke passed: tests + installDist + USAGE + -sessions». Не вываливать всю простыню логов.

## Чего не делать

- **Никаких сетевых вызовов** — не запускать `-prompt … -oneshot`, не делать `curl` к Gemini / OpenRouter / HuggingFace. Это offline smoke.
- **Не разрушать БД** — никаких `-clean`, никаких `-inflate`. БД в `~/.project01-cli/history.db` пользовательская, цепляться к ней нельзя.
- **Не печатать секреты.** Если в выводе мелькнёт что-то похожее на ключ (`BuildKonfig.GEMINI_API_KEY`, `OPENROUTER_API_KEY`, `HUGGINGFACE_API_KEY` или значения из `local.properties`) — замаскировать.
- **Live-smoke (с сетью)** запускать только если пользователь явно попросил, отдельным шагом: предупредить про расход токенов, дождаться подтверждения, потом гонять `-prompt "ping" -oneshot -maxTokens 20` на самой дешёвой модели. По умолчанию — нет.

## Когда smoke не помог

Если все 4 шага прошли, но баг всё равно где-то есть — это значит, что покрытие smoke здесь не дотянулось. Предложить пользователю явно описать сценарий и добавить либо отдельный test в `cliJvmApp/src/test/`, либо отдельный шаг в этот файл. Не разрастаться сюда без явной просьбы.
