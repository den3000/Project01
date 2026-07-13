# Приватный AI-сервис: Ollama + мелкая модель на Amvera (CPU-only PaaS, без GPU).
# Наружу Amvera отдаёт только HTTP через nginx на containerPort — REST Ollama (/api/*)
# по этому каналу работает как есть.
FROM ollama/ollama:latest

ENV OLLAMA_MODELS=/data/models \
    # Слушать все интерфейсы: Amvera проксирует публичный домен на containerPort.
    OLLAMA_HOST=0.0.0.0:11434 \
    # Базовые ограничения под маломощный тариф (стабильность при нескольких запросах):
    # один запрос за раз, короткая очередь, одна загруженная модель.
    OLLAMA_NUM_PARALLEL=1 \
    OLLAMA_MAX_QUEUE=8 \
    OLLAMA_MAX_LOADED_MODELS=1 \
    # Держать модель в памяти между запросами (первый запрос не ждёт загрузку).
    OLLAMA_KEEP_ALIVE=10m \
    # Модель, которую сервис поднимает на старте. Мелкая — влезает в 2.5 ГБ RAM.
    OLLAMA_PULL_MODEL=qwen2.5:1.5b

# Встроенный entrypoint (без отдельного файла — не зависит от build-контекста):
# поднять сервер, дождаться готовности, один раз подтянуть модель в /data/models
# (переживает пересборки), затем отдать процесс серверу в foreground.
RUN printf '%s\n' \
    '#!/bin/sh' \
    'set -e' \
    'ollama serve &' \
    'until ollama list >/dev/null 2>&1; do sleep 1; done' \
    'ollama pull "$OLLAMA_PULL_MODEL"' \
    'wait' \
    > /entrypoint.sh

# base-образ имеет ENTRYPOINT ["/bin/ollama"] — переопределяем на наш скрипт.
ENTRYPOINT ["/bin/sh", "/entrypoint.sh"]
