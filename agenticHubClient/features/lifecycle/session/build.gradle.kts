import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexplicit-backing-fields")
    }

    iosArm64()
    iosSimulatorArm64()

    jvm()

    androidLibrary {
        namespace = "ru.den.writes.code.agenticHub.features.lifecycle.session"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Portable conversation runtime (MVI stack + turn engine + intent/prompt
            // sources + scheduler glue). The two platform primitives (clock + scheduler
            // dispatcher) live behind expect/actual (SessionPlatform).
            api(projects.agenticHubClient.features.lifecycle.command)
            api(projects.agenticHubClient.features.memory)
            api(projects.agenticHubClient.features.agent)
            // Task FSM: an engine implementation delegates its stage decisions here,
            // and TurnResult carries the machine's verdict out to the view-model.
            // `api` because RetryOutcome crosses the public surface of TurnResult.
            api(projects.agenticHubClient.features.fsm)
            // RagControl (load a saved index) + turn-time retrieval/context injection.
            implementation(projects.agenticHubClient.features.rag)
            implementation(projects.agenticHubClient.platform.logging)
            implementation(projects.scheduling)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            // CommandRunnerTest: LocalFileSystemFake via fileSystemTestModule (koin) +
            // @IgnoreIos (its branch cases open a real DB via TestDb).
            implementation(projects.agenticHubClient.platform.fileSystem)
            implementation(projects.agenticHubClient.testUtils)
        }
        jvmTest.dependencies {
            implementation(projects.agenticHubClient.platform.network)
            implementation(projects.agenticHubClient.platform.config)
        }
    }
}