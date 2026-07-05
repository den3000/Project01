# :agenticHubClient:features:rag — RAG-ядро (индексация и поиск)

KMP-модуль: локальный пайплайн Retrieval-Augmented Generation — нарезка документов на чанки,
эмбеддинги, векторный индекс с косинусным поиском и персист индекса. Доменное ядро с конструкторным
DI и чистыми функциями; сеть/эмбеддер инжектится снаружи, так что ядро остаётся портируемым и
тестируется на фейках без сети.

## Публичный API

Раскладка по пакетам: `chunking/` (модель + стратегии + сравнение), `embedding/` (`Embedder`/косинус/
фейк), `indexing/` (индекс + пайплайн + персист); `Retriever` и `di/` — в корне модуля.

- **Модель** (`chunking/`): `SourceDocument(source,title,text)` → вход пайплайна; `Chunk(text,metadata)` +
  `ChunkMetadata(source,title,section,chunkId)` (`@Serializable`; метаданные едут на каждый чанк для
  цитирования источника).
- **Chunking** (`ChunkingStrategy` — fun interface, чистая нарезка):
  - `FixedSizeChunking(chunkSize, overlap)` — скользящее окно фикс-размера (по символам) с перекрытием
    (structure-blind, `section = null`).
  - `TokenChunking(tokensPerChunk, overlap)` — скользящее окно по токенам (`\S+`) с перекрытием: рез
    всегда по границе токена (слово не рвётся), внутренние пробелы/переносы сохраняются
    (structure-blind).
  - `StructuralChunking` — по markdown-заголовкам (ATX `#`..`######`): один чанк на раздел, имя
    раздела → `section`, заголовок остаётся в тексте чанка; преамбула до первого заголовка →
    `section = null`.
  - `ChunkingComparison.compare(doc, strategies)` → `ChunkingComparisonReport` (+ `render()`) —
    сравнение стратегий по размерным метрикам.
- **Embeddings/поиск**: `Embedder` (fun interface, `suspend embed(List<String>)`) + реализация
  `OllamaEmbedder(httpClient, model="nomic-embed-text", baseUrl="http://localhost:11434")` — батч
  `POST /api/embed` к локальной Ollama; `EmbedderFake` (`internal`) — детерминированный для тестов.
  `IndexedChunk`/`ScoredChunk`/`VectorIndex.search(query, topK)` (brute-force косинус, стабильная
  сортировка). `cosineSimilarity` — `internal` (ранжирует только `VectorIndex.search`, наружу не отдаётся).
- **Пайплайн**: `IndexingPipeline(chunking, embedder).index(docs)` → `VectorIndex`;
  `Retriever(embedder, index).retrieve(query, topK)` → `List<ScoredChunk>`.
- **Персист**: `IndexStore(fs).save(index, path)` / `load(path)` — индекс как один JSON-документ через
  `LocalFileSystem` (absent → `null`).
- **`di/`**: приватный `sharedRagModule` держит общие для прод и теста factory —
  `IndexingPipeline` (на `ChunkingStrategy`) и `Retriever` (на `VectorIndex`); оба публичных модуля
  подключают его через `includes(...)` (без дублирования). `ragModule` = `sharedRagModule` +
  `single { IndexStore }` + `single<Embedder> { OllamaEmbedder(get()) }` (`HttpClient` — из
  `networkModule`/`platform:network`). `ragTestModule` = `sharedRagModule` + `factory { IndexStore }` +
  `factory<Embedder> { EmbedderFake() }` (offline, перекрывает Ollama). Общая дока — [DI.md](../../DI.md).

## Зависимости
- `api(ktor-client-core)` (`HttpClient` протекает через публичный конструктор `OllamaEmbedder`),
  `implementation(platform:fileSystem)` (персист индекса), `implementation(platform:logging)`,
  `serialization-json`, `koin.core`, `coroutines-core`. **Модуль не зависит от `features:llm`** —
  наоборот, `features:llm` тянет rag (`api`) ради `RagContextMapper` (сборка grounding-промпта из
  `ScoredChunk`); полная оркестрация двухрежимного RAG-ответа живёт выше rag (в agent/CLI).

## Тесты
- Offline (по умолчанию): `./gradlew :agenticHubClient:features:rag:jvmTest` — на фейках/детерминированном
  эмбеддере; live-тесты (`*LiveTest`) исключены центральным гейтом.
- Live (нужна поднятая Ollama + `ollama pull nomic-embed-text`):
  `./gradlew :agenticHubClient:features:rag:jvmTest -PliveTests` — реальный embed / семантика /
  end-to-end retrieve + capstone-индексация корпуса; Ollama недоступна → `Assume`-скип, не падает.
- Общий механизм live-тестов (флаг `-PliveTests`, конвенция `*LiveTest`, запуск из Android Studio) —
  [LIVE_TESTS.md](../../../LIVE_TESTS.md).

## Грабли
- **Live-тесты — opt-in** (`OllamaLiveTest`/`DocsIndexingOllamaLiveTest` в `src/jvmTest`; нужен реальный
  `HttpClient` из `platform:network`); гейт и флаг — общие, см. [LIVE_TESTS.md](../../../LIVE_TESTS.md).
- **`Json.encodeToString(index)` (reified) не выводит тип** без импорта `kotlinx.serialization.*` →
  явный `encodeToString(VectorIndex.serializer(), index)` в `IndexStore`.
- **Тесты, трогающие `fileSystemTestModule`, помечены `@IgnoreIos`** — фейк лежит в одном файле с
  eager iOS-TODO `fileSystemModule` val (Kotlin/Native инициализирует все top-level val файла разом).
