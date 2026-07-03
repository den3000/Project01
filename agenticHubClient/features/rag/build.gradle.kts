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
    }
}
