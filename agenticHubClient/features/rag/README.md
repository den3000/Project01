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
- **Embeddings/поиск**: `Embedder` (fun interface, `suspend embed(List<String>)`);
  `IndexedChunk`/`ScoredChunk`/`VectorIndex.search(query, topK)` (brute-force косинус, стабильная
  сортировка). `cosineSimilarity` — `internal` (ранжирует только `VectorIndex.search`, наружу не отдаётся).
- **Пайплайн**: `IndexingPipeline(chunking, embedder).index(docs)` → `VectorIndex`;
  `Retriever(embedder, index).retrieve(query, topK)` → `List<ScoredChunk>`.
- **Персист**: `IndexStore(fs).save(index, path)` / `load(path)` — индекс как один JSON-документ через
  `LocalFileSystem` (absent → `null`).
- **`di/`**: приватный `sharedRagModule` держит общие для прод и теста factory —
  `IndexingPipeline` (на `ChunkingStrategy`) и `Retriever` (на `VectorIndex`); оба публичных модуля
  подключают его через `includes(...)` (без дублирования). `ragModule` = `sharedRagModule` +
  `single { IndexStore }`. `ragTestModule` = `sharedRagModule` + `factory { IndexStore }` +
  `factory<Embedder> { EmbedderFake() }` (offline). `Embedder` **прод-модулем не биндится**. Общая
  дока — [DI.md](../../DI.md).

## Зависимости
- `api(features:llm)` (`LlmApi`/`Message` протекают через порты для будущего reranking/генерации;
  транзитивно ktor-core + coroutines), `implementation(platform:fileSystem)` (персист индекса),
  `implementation(platform:logging)`, `serialization-json`, `koin.core`.

## Тесты
`./gradlew :agenticHubClient:features:rag:jvmTest` — offline, на фейках/детерминированном эмбеддере.

## Грабли
- **`ragModule` не биндит `Embedder`** — реального (Ollama) эмбеддера пока нет; резолв
  `IndexingPipeline`/`Retriever` из чистого прод-графа неполон by design, эмбеддер надо докомпоновать
  (`ragTestModule` в тестах). Появится сетевой эмбеддер — забиндится здесь.
- **`Json.encodeToString(index)` (reified) не выводит тип** без импорта `kotlinx.serialization.*` →
  явный `encodeToString(VectorIndex.serializer(), index)` в `IndexStore`.
- **Тесты, трогающие `fileSystemTestModule`, помечены `@IgnoreIos`** — фейк лежит в одном файле с
  eager iOS-TODO `fileSystemModule` val (Kotlin/Native инициализирует все top-level val файла разом).
