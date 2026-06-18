# cliTui — песочница сравнения TUI-библиотек

Изолированный модуль для оценки terminal UI на Kotlin/JVM поверх одной заглушки
чата ([`DemoResponder`](src/main/kotlin/ru/den/writes/code/project01/cliTui/DemoResponder.kt) —
эхо по словам, без сети и токенов). Рабочий `:cliJvmApp` не затронут — модуль
самостоятельный, чистый JVM (без Compose).

Один бинарь, три режима (идентичный сценарий: лента сообщений + ввод + footer
turn/session/context):

| Файл | Режим |
|------|-------|
| [`KotterChat.kt`](src/main/kotlin/ru/den/writes/code/project01/cliTui/KotterChat.kt)  | declarative TUI без Compose (Varabyte) |
| [`MordantChat.kt`](src/main/kotlin/ru/den/writes/code/project01/cliTui/MordantChat.kt) | богатый вывод + цикл ввода (AJ Alt) |
| [`ComboChat.kt`](src/main/kotlin/ru/den/writes/code/project01/cliTui/ComboChat.kt)    | **Kotter-каркас + Mordant-виджеты** |

### Mosaic выброшен

Прототип Mosaic (Compose-TUI) собирался и совместим с Kotlin 2.4 / compose-runtime
1.11, но на macOS-терминале его нативный raw-ввод (`mosaic-tty`) не переводил
терминал в raw-режим — клавиши шли в обычное терминальное эхо, а `onKeyEvent`
ничего не получал, UI оставался пустым. Возиться с raw-вводом 0.x-библиотеки —
не стоит; выброшен вместе со всем Compose-стеком. История — в git.

## Запуск

⚠️ В настоящем терминале (не из IDE/`./gradlew run`).

```bash
./gradlew :cliTui:installDist
./cliTui/build/install/cliTui/bin/cliTui combo    # или kotter / mordant
```

Печатай текст → Enter отправляет → ответ «стримится» по словам → footer со
статистикой. Выход — `/exit`.

## Сравнение (по факту прогона)

| | **Kotter** 1.3.0 | **Mordant** 3.0.2 | **Combo** |
|---|---|---|---|
| Модель | declarative event-loop | императивный вывод | Kotter каркас + Mordant виджеты |
| Ввод | `input()` (правка строки, история) | `readLineOrNull` (вся строка) | `input()` от Kotter |
| Виджеты | минимум | `Panel`/`Table`/`Markdown`/цвета | `Panel`/`Table` от Mordant |
| Перерисовка | `liveVarOf` → авто-rerender | нет (печатаем заново) | `liveVarOf` от Kotter |
| Полноэкранный | да | нет (инлайн) | да |
| Зрелость | 1.x стабильный | 3.x стабильный | обе стабильны |

### Как склеены Kotter и Mordant (combo)

Конфликт двух ANSI-рендереров снят так: Mordant рендерит виджет в **строку** с
`AnsiLevel.NONE` (`terminal.render(panel)` — только текст и box-drawing, без
своих escape-кодов), а цвет накладывает уже Kotter (`yellow { … }`). Kotter
остаётся единственным, кто управляет экраном и перерисовкой; Mordant работает
как библиотека верстки виджетов. Тот же приём масштабируется на `Table`,
`Markdown`, прогресс-бары.

### Вывод

**Combo — рекомендуемый путь.** Берём интерактивный, REPL-дружелюбный каркас
Kotter (готовый `input()` с редактированием строки, реактивный `liveVarOf`) и
богатые виджеты Mordant (рамки/таблицы/Markdown для footer и, при желании,
истории). Чистый JVM, обе библиотеки стабильны, Compose-зависимости нет.

Интерактивные команды `cliJvmApp` ложатся естественно: `/profiles` открывает
Mordant-таблицу выбора **на месте** `stats` (стрелки/цифры + Enter, Esc —
отмена), применённый выбор уходит в ленту отдельной строкой, а отправка обычного
сообщения просто закрывает таблицу. То есть combo тянет реальный UX, а не только
статичный вывод. Триггер `multiline` показывает многострочный ответ с
выравниванием продолжений.

Реальную проводку LLM (`LlmApi`) в combo — отдельным шагом (типы в `cliJvmApp`
сейчас `internal`, нужно поднять видимость тонкого фасада).
