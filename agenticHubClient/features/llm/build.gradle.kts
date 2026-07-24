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
        namespace = "ru.den.writes.code.agenticHub.features.llm"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.agenticHubClient.platform.logging)

            // ApiKey — the env-var names for the provider keys; buildModelProvider
            // raises MissingApiKey with them. platform:config also exposes BuildKonfig.
            implementation(projects.agenticHubClient.platform.config)

            // RagContextMapper (retrieved chunks → grounding Message) is production
            // glue for RAG-answering; ScoredChunk leaks through its public signature
            // → api(features:rag). No cycle: features:rag does NOT depend on llm.
            api(projects.agenticHubClient.features.rag)

            // Domain core (LLM API + DTOs). The ktor engine is intentionally
            // absent — callers inject an HttpClient, so the HttpClient type
            // leaks through public *Api constructors via api(); coroutines
            // likewise (suspend API). content-negotiation + serialization stay
            // implementation: they are internal to the *Api / DTO code.
            api(libs.ktor.client.core)
            api(libs.kotlinx.coroutinesCore)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.kotlinxJson)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest.dependencies {
            // JVM-only live tests hitting a real local Ollama: platform:network gives the
            // shared HttpClient (Java engine + ContentNegotiation) via networkModule; junit
            // for the org.junit.Assume skip when Ollama isn't running (mirrors features:rag).
            // features:rag comes transitively via commonMain api (RagContextMapper).
            implementation(projects.agenticHubClient.platform.network)
            implementation(libs.junit)
            // platform:config (BuildKonfig, for the Gemini live test's GEMINI_API_KEY)
            // now comes transitively from commonMain.
        }
    }
}
// Live tests (*LiveTest) are gated centrally in the root build.gradle.kts (-PliveTests).
