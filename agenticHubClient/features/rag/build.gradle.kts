import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    jvm()

    androidLibrary {
        namespace = "ru.den.writes.code.agenticHub.features.rag"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            // RAG domain (chunking / embeddings / index / retrieval). LLM types
            // (LlmApi/Message) surface through the ports for later reranking and
            // answer generation → api. The built index is persisted as JSON via
            // the filesystem port; embedding is suspend → coroutines. @Serializable
            // on the index model needs the serialization plugin + json runtime.
            api(projects.agenticHubClient.features.llm)
            implementation(projects.agenticHubClient.platform.fileSystem)
            implementation(projects.agenticHubClient.platform.logging)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            // @IgnoreIos on tests that touch fileSystemTestModule (it shares a
            // file with the eager, iOS-TODO fileSystemModule val).
            implementation(projects.agenticHubClient.testUtils)
        }
        jvmTest.dependencies {
            // JVM-only tests hitting a real HTTP client: platform:network gives the
            // shared HttpClient (Java engine + CN) via networkModule; junit for the
            // org.junit.Assume skip when Ollama isn't running.
            implementation(projects.agenticHubClient.platform.network)
            implementation(libs.junit)
        }
    }
}

// Live Ollama tests (class *OllamaLiveTest) are opt-in: excluded from the normal
// jvmTest run, included only with -PollamaLive (they need a local Ollama up).
tasks.named<Test>("jvmTest") {
    if (!project.hasProperty("ollamaLive")) {
        filter { excludeTestsMatching("*OllamaLiveTest") }
    }
}
