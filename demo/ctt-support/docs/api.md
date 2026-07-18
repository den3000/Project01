# HTTP API сервера

Все ответы — JSON, кодировка UTF-8. Сервер: Ktor + Netty, слушает `0.0.0.0:8080`.

## GET /

Дружелюбный «hello» для быстрой проверки, что сервер поднялся.

```
$ curl http://localhost:8080/
Ktor: Hello, Server!
```

## GET /api/ping

Liveness-проверка. Клиент использует её для индикатора «Online/Offline».

```
$ curl http://localhost:8080/api/ping
pong
```

## GET /api/tasks

Все задачи в виде JSON-массива.

```
$ curl http://localhost:8080/api/tasks
[
  {"id":1,"title":"…","description":"","isCompleted":false,"priority":"MEDIUM"}
]
```

## POST /api/tasks

Одна ручка на «создать» и «обновить»:

- `id = 0` → сервер назначает следующий свободный id и добавляет запись.
- `id != 0` → сервер удаляет существующую запись с этим id (если была) и добавляет присланную.

```bash
# создать
curl -X POST http://localhost:8080/api/tasks \
  -H "Content-Type: application/json" \
  -d '{"id":0,"title":"Купить хлеб","description":"","isCompleted":false,"priority":"LOW"}'

# обновить (например, отметить выполненной)
curl -X POST http://localhost:8080/api/tasks \
  -H "Content-Type: application/json" \
  -d '{"id":1,"title":"Купить хлеб","description":"","isCompleted":true,"priority":"LOW"}'
```

Ответ: `201 Created` + сохранённая версия задачи.

**Важно:** тело POST'а **полностью** заменяет запись. Если не прислать поле — при обновлении оно
пропадёт (для String это станет `""`, для Boolean — `false`).

## DELETE /api/tasks/{id}

Удаление одной задачи по id. Если такой нет — `404 Not Found`.

```bash
curl -X DELETE http://localhost:8080/api/tasks/1
```

## PUT /api/tasks/{id}

**Не реализован.** Используйте POST с известным id — см. выше и
[known-issues.md](known-issues.md#put-эндпоинта-нет--обновление-задачи-идёт-через-post-с-известным-id).
