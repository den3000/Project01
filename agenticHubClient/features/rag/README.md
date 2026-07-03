# :agenticHubClient:features:rag — RAG-ядро (индексация и поиск)

KMP-модуль: локальный пайплайн Retrieval-Augmented Generation — нарезка документов на чанки,
эмбеддинги, векторный индекс с косинусным поиском и персист индекса. Доменное ядро с конструкторным
DI и чистыми функциями; сеть/эмбеддер инжектится снаружи, так что ядро остаётся портируемым и
тестируется на фейках без сети.

## Публичный API
- **Модель**: `SourceDocument(source,title,text)` → вход пайплайна; `Chunk(text,metadata)` +
  `ChunkMetadata(source,title,section,chunkId)` (`@Serializable`; метаданные едут на каждый чанк для
  цитирования источника).
- **Chunking** (`ChunkingStrategy` — fun interface, чистая нарезка):
  - `FixedSizeChunking(chunkSize, overlap)` — скользящее окно фикс-размера с перекрытием
    (structure-blind, `section = null`).
  - _(далее)_ `StructuralChunking` — по markdown-заголовкам/разделам.

## Зависимости
- `api(features:llm)` (`LlmApi`/`Message` протекают через порты для будущего reranking/генерации;
  транзитивно ktor-core + coroutines), `implementation(platform:fileSystem)` (персист индекса),
  `implementation(platform:logging)`, `serialization-json`, `koin.core`.

## Тесты
`./gradlew :agenticHubClient:features:rag:jvmTest` — offline, на фейках/детерминированном эмбеддере.

## Грабли
- (пока нет)
