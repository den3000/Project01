# :agenticHubClient:platform:network — общий HTTP-клиент

KMP-модуль (jvm/android/ios): единая точка сборки `HttpClient` для всей сессии, инжектится через DI в
доменные модули (`features:llm`/`features:rag`), чтобы те не знали про движок/плагины. Один клиент на
сессию — держит соединения тёплыми и не ловит cold-start-гонку.

## Публичный API
- `di/`: `networkModule` — Koin-модуль, биндит `HttpClient` (`internal expect` + `public val`;
  jvm actual `single<HttpClient> { buildHttpClient() } onClose { it?.close() }`, android/ios — `TODO`).
  Билдер `buildHttpClient` — `internal` (jvmMain): движок **Java** (не CIO — рвёт длинные
  thinking-ответы Gemini) + `ContentNegotiation(Json{ ignoreUnknownKeys=true; explicitNulls=false })` +
  `HttpTimeout`. **Без `HttpRequestRetry` намеренно** — transient-retry провайдер-специфичен (что считать
  transient и как ждать различается по API), поэтому живёт в каждом `*Api` (`features:llm`), не на
  транспорте. Общая дока — [DI.md](../../DI.md).

## Зависимости
- commonMain: `api(ktor.client.core)` (тип `HttpClient` — публичный словарь модуля),
  `implementation(koin.core)`; jvmMain: `ktor.client.java` + `ktor.client.contentNegotiation` +
  `ktor.serialization.kotlinxJson` + `kotlinx.serializationJson`.

## Грабли
- android/ios `networkModule` actual — сейчас `TODO()` (нужны OkHttp/Android и Darwin движки); eager
  `public val` упадёт при инициализации, если тот таргет его дёрнет (см. [DI.md](../../DI.md)) →
  common-тест под `@IgnoreIos`.
