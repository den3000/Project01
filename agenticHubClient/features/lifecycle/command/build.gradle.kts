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
        namespace = "ru.den.writes.code.agenticHub.features.lifecycle.command"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Neutral command/config vocabulary (StartCommand/SessionConfig/ScheduleSpec/
            // MemoryAction) shared by lifecycle:start and lifecycle:session. Exposes
            // domain types through its API (ContextStrategyKind, StageAgentSpec/MemoryMode,
            // ModelProvider) → api.
            api(projects.agenticHubClient.features.memory)
            api(projects.agenticHubClient.features.agent)
            api(projects.agenticHubClient.features.llm)
        }
    }
}
