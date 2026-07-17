# :agenticHubClient:features:rag — RAG-ядро (индексация и поиск)

KMP-модуль: локальный пайплайн Retrieval-Augmented Generation — нарезка документов на чанки,
эмбеддинги, векторный индекс с косинусным поиском, второй этап (реранкинг/фильтрация найденного) и
персист индекса. Доменное ядро с конструкторным DI и чистыми функциями; сеть/эмбеддер инжектится
снаружи, так что ядро остаётся портируемым и тестируется на фейках без сети.

## Публичный API

Раскладка по пакетам: `chunking/` (модель + стратегии + сравнение), `embedding/` (`Embedder`/косинус/
фейк), `indexing/` (индекс + пайплайн + персист), `rerank/` (второй этап после поиска); `Retriever` и
`di/` — в корне модуля.

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
- **Embeddings/поиск**: `Embedder` (fun interface, `suspend embed(List<String>)`) + реализации
  `OllamaEmbedder(httpClient, model="nomic-embed-text", baseUrl="http://localhost:11434")` — батч
  `POST /api/embed` к локальной Ollama; `GeminiEmbedder(httpClient, apiKey, model="gemini-embedding-001")`
  — батч `POST :batchEmbedContents` к облаку (**API-ключ — просто аргумент конструктора**, rag НЕ
  зависит от features:llm); `EmbedderFake` (`internal`) — детерминированный для тестов.
  `EmbedderKind` (`ollama`/`gemini`) + `EmbedderSelector` (fun interface) — выбор бэкенда; конкретные
  эмбеддеры с креденшелами строит composition root (cliJvmApp), rag остаётся без ключей.
  `IndexedChunk`/`ScoredChunk`/`VectorIndex.search(query, topK)` (brute-force косинус, стабильная
  сортировка). `cosineSimilarity` — `internal` (ранжирует только `VectorIndex.search`, наружу не отдаётся).
- **Пайплайн**: `IndexingPipeline(chunking, embedder).index(docs)` → `VectorIndex`;
  `Retriever(embedder, index).retrieve(query, topK)` → `List<ScoredChunk>`.
- **Реранк** (`rerank/`, второй этап после поиска — пере-скор кандидатов + отсечение по порогу +
  topK-after): `Reranker` (fun interface, `suspend rerank(query, candidates)`) + `LexicalReranker(
  threshold, topKAfter)` — оффлайн-реализация на перекрытии терминов запрос↔чанк (другой сигнал, чем
  retrieval-косинус: отсекает «похожий-но-не-отвечающий» шум). Модельный CrossEncoder — в `features:llm`
  (`ModelReranker`, ему нужен `LlmApi`; rag на llm не зависит).
- **Персист**: `IndexStore(fs).save(index, path)` / `load(path)` — индекс как один JSON-документ через
  `LocalFileSystem` (absent → `null`).
- **`RagIndexer(indexStore)`**: `suspend index(document | documents, path, chunking, embedder): Int` —
  «собери индекс и запиши сюда» (chunk + embed + save), число чанков. Overload на один `SourceDocument`
  и на `List<SourceDocument>` (корпус — один batched прогон `IndexingPipeline`). `embedder` — параметр
  вызова (caller выбирает ollama/gemini по `-rag add … embedder <…>`). Обёртка над `IndexingPipeline`
  для admin-команды `-rag add <name> src <path>` (cliJvmApp пишет в `~/.project01-cli/rag/<name>.json`,
  грузит обратно `/rag <name>`). **Индекс и запрос обязаны быть на одном эмбеддере** — векторы разных
  моделей несравнимы.
- **`sourceCorpus(fs, root, extensions = SOURCE_EXTENSIONS)`**: обходит директорию
  (`LocalFileSystem.walkFiles`) и собирает по `SourceDocument` на каждый файл с нужным расширением
  (дефолт — `md`/`kt`/`kts`: **документация И код**), срезая шум (`build/`, `.git/`, `.gradle/`,
  `.idea/`, `.claude/`, `node_modules/`); `source` = путь относительно корня. Так
  `-rag add <name> src <dir>` индексирует целый проект в один индекс. В паре с
  `ByExtensionChunking` каждый формат режется по-своему.
- **`di/`**: приватный `sharedRagModule` держит общие для прод и теста factory —
  `IndexingPipeline` (на `ChunkingStrategy`), `Retriever` (на `VectorIndex`) и `Reranker`
  (→ `LexicalReranker`, оффлайн-сигнал безопасен в обоих графах); оба публичных модуля
  подключают его через `includes(...)` (без дублирования). `ragModule` = `sharedRagModule` +
  `single { IndexStore }` + `single<Embedder> { OllamaEmbedder(get()) }` (`HttpClient` — из
  `networkModule`/`platform:network`) + `single { RagIndexer }`. `ragTestModule` = `sharedRagModule` + `factory { IndexStore }` +
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
