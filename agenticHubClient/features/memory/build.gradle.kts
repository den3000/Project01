import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    jvm()

    androidLibrary {
        namespace = "ru.den.writes.code.agenticHub.features.memory"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Memory domain (MemoryLayer/Profile/TaskState) + compaction
            // (HistoryCompressor) + LLM types (Message/Usage/PricingRegistry,
            // surfaced through the ports) → api on llm. The Room DB
            // (AppDatabase/DAO/entities) backs RoomHistoryStore → api too.
            api(projects.agenticHubClient.features.llm)
            api(projects.agenticHubClient.platform.database)
            implementation(projects.agenticHubClient.platform.fileSystem)
            implementation(projects.agenticHubClient.platform.logging)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(projects.agenticHubClient.testing)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
