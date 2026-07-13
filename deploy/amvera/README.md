# Приватный AI-сервис: Ollama на Amvera

Разворачивает локальную LLM (Ollama + мелкая модель) как сетевой сервис с HTTP API на
[Amvera](https://amvera.ru) — PaaS без GPU. Тот же REST, что у Ollama локально, доступен по
HTTPS-домену; клиентом выступает CLI из [cliJvmApp](../../agenticHubClient/apps/cliJvmApp/README.md)
через флаг `-agent host <url>`.

## Файлы

| Файл | Роль |
|---|---|
| [`Dockerfile`](Dockerfile) | образ `ollama/ollama` + встроенный entrypoint (serve → pull модели в `/data/models` → foreground) + env-лимиты |
| [`amvera.yml`](amvera.yml) | конфиг Amvera: `containerPort 11434`, `persistenceMount /data`, путь к Dockerfile |

## Ограничения Amvera (важно)

- **Только HTTP(S) наружу** через nginx на домене `<проект>.<user>.amvera.io` → маппится на
  `containerPort`. Произвольные TCP нельзя, но REST Ollama (`/api/*`) — это HTTP, работает.
- **Нет GPU, мало RAM** → нужна мелкая модель. По умолчанию `qwen2.5:1.5b` (~1 ГБ) под тариф
  **Стандартный** (2.5 ГБ RAM, 1 CPU, 15 ГБ SSD). Меньше 2 ГБ RAM — только `qwen2.5:0.5b`.
- **docker-compose не поддерживается** → один контейнер, один процесс (поэтому auth-прокси здесь нет —
  см. «Приватность»).
- **Постоянный том `/data`** переживает пересборки; модели пишем в `/data/models`
  (`OLLAMA_MODELS`), иначе качаются заново каждый деплой.
- nginx рвёт запрос на ~60с — короткие ответы мелкой модели укладываются.

## Деплой (панель Amvera)

1. Создайте проект типа **Docker**, подключите этот git-репозиторий (или загрузите).
2. Конфиг: скопируйте [`amvera.yml`](amvera.yml) в **корень репозитория** (Amvera ищет его там) —
   или задайте эквивалент в UI: Dockerfile `deploy/amvera/Dockerfile`, порт `11434`, том `/data`.
3. Тариф — **Стандартный** (2.5 ГБ RAM). На меньшем поменяйте модель в `Dockerfile`
   (`OLLAMA_PULL_MODEL=qwen2.5:0.5b`).
4. Соберите и запустите. **Первый старт качает модель** (~1 ГБ) — это медленно; последующие быстрые
   (модель на `/data`).
5. URL сервиса: `https://<проект>.<user>.amvera.io`.

## Проверки (для видео)

Подставьте свой `URL=https://<проект>.<user>.amvera.io`.

**1. Доступ к модели по сети** (HTTP API):
```bash
curl -s "$URL/api/tags"                       # список моделей на сервисе
```

**2. Чат через HTTP API** (напрямую):
```bash
curl -s "$URL/api/chat" -d '{
  "model": "qwen2.5:1.5b",
  "messages": [{"role":"user","content":"Ответь одним словом: столица Франции?"}],
  "stream": false
}'
```

**3. Чат через ваш CLI-клиент** (тот же сервис по сети):
```bash
CLI=agenticHubClient/apps/cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp
"$CLI" -prompt "Привет! Ты локальная модель на моём VPS?" \
       -agent provider ollama model qwen2.5:1.5b host "$URL"
#   REPL: обычный чат с историей; /exit — выход
```

**4. Стабильность при нескольких запросах** (параллельно):
```bash
seq 5 | xargs -P5 -I{} curl -s -o /dev/null -w "req {} → %{http_code} %{time_total}s\n" \
  "$URL/api/chat" -d '{"model":"qwen2.5:1.5b","messages":[{"role":"user","content":"2+2?"}],"stream":false}'
```
Ollama сериализует их (`OLLAMA_NUM_PARALLEL=1`) и держит очередь (`OLLAMA_MAX_QUEUE=8`) —
сервис не падает, запросы отрабатывают по очереди.

**5. Базовые ограничения:**
- **max context** — со стороны клиента: `-agent contextWindow <N>` (`num_ctx`); со стороны сервиса
  модель имеет свой предел окна.
- **rate limit / очередь** — env в `Dockerfile`: `OLLAMA_NUM_PARALLEL`, `OLLAMA_MAX_QUEUE`,
  `OLLAMA_MAX_LOADED_MODELS`, `OLLAMA_KEEP_ALIVE`.

## Приватность (важно)

У Ollama **нет встроенной авторизации** — эндпоинт на публичном домене открыт всем, кто знает URL
(любой может тратить ваш CPU). Сейчас защита = необнародованный URL + лимиты очереди. Для настоящей
приватности нужен обратный прокси с Bearer-токеном/rate-limit перед Ollama — но Amvera не поддерживает
docker-compose, поэтому это отдельный контейнер-прокси или supervisor внутри образа + auth-заголовок в
клиенте. Вынесено за рамки текущего деплоя; не публикуйте URL.

## Стоимость

Поминутная тарификация; Стандартный ≈ 2 ₽/час (1450 ₽/мес). Останавливайте проект в панели, когда не
нужен, чтобы не платить за простой.
