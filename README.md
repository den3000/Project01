This is a Kotlin Multiplatform project targeting Android, iOS, Desktop (JVM).

* [/iosApp](./iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/shared](./shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./shared/src/jvmMain/kotlin)
    folder is the appropriate location.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :androidApp:assembleDebug`
- Desktop app:
  - Hot reload: `./gradlew :desktopApp:hotRun --auto`
  - Standard run: `./gradlew :desktopApp:run`
- iOS app: open the [/iosApp](./iosApp) directory in Xcode and run it from there.
- CLI JVM app — sends a prompt to Gemini, prints the response with a stats
  footer (wall-clock duration + Gemini's `usageMetadata` token counts:
  `prompt`, `output`, `thoughts` if the model thinks, and `total`), then drops
  into a REPL where each new line becomes the next prompt. Recognised commands:
  - `/quit` or `/exit` (or Ctrl-D) — leave the REPL.
  - `/reuse` — feed the model's last reply back as the next prompt without
    retyping it. Handy for chain-of-thought follow-ups where you want the
    model to keep building on what it just said.
  - Run: `./gradlew :cliJvmApp:run --args="-prompt <text> [-maxTokens <int>] [-stopSequence <text>] [-endSequence <text>]"`
  - Build a packaged launcher: `./gradlew :cliJvmApp:installDist`,
    then run `./cliJvmApp/build/install/cliJvmApp/bin/cliJvmApp -prompt "<text>"`
  - The Gemini API key is **not** a CLI argument. It is read at build time
    from `GEMINI_API_KEY` in `local.properties` (gitignored) or from an
    environment variable of the same name, and exposed to the code via
    `BuildKonfig.GEMINI_API_KEY`.
  - Optional flags persist across REPL iterations — only the prompt changes
    between turns:
    - `-maxTokens <int>` → `generationConfig.maxOutputTokens`.
    - `-stopSequence <words>` → split on whitespace; each word becomes its own
      entry in `generationConfig.stopSequences`. Generation halts the moment
      *any one* of them appears in the output. Up to 5 words (Gemini API limit);
      passing more aborts with an error.
    - `-endSequence <text>` → sent as `systemInstruction`, asking the model
      to end its response with that string. Best-effort (model directive,
      not an API guarantee).
    - `-temperature <number>` → `generationConfig.temperature`. Decimal;
      higher values produce more random / creative output, lower values are
      more deterministic. Gemini accepts roughly `0.0..2.0`; out-of-range
      values are rejected by the API.
    - `-model <model-id>` → picks the Gemini model used for the call (the
      URL path segment in `…/v1beta/models/<id>:generateContent`).
      Default when omitted: `gemini-2.5-flash`. Text-generation models
      available per [Google's catalog](https://ai.google.dev/gemini-api/docs/models)
      as of June 2026:
      - **2.5 (GA):** `gemini-2.5-pro`, `gemini-2.5-flash`,
        `gemini-2.5-flash-lite`
      - **3.1 (mixed):** `gemini-3.1-pro-preview`, `gemini-3-flash-preview`
        (heads-up: the 3.1 Flash id literally omits the `.1` — that's how
        Google ships it), `gemini-3.1-flash-lite` (GA)
      - **3.5:** `gemini-3.5-flash` only — no Pro, no Flash-Lite tier in 3.5

      Unknown / unavailable ids are rejected by the API.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :shared:testAndroidHostTest`
- Desktop tests: `./gradlew :shared:jvmTest`
- iOS tests: `./gradlew :shared:iosSimulatorArm64Test`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…