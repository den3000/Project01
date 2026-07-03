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
            implementation(projects.agenticHubClient.platform.logging)
            implementation(projects.scheduling)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest.dependencies {
            // CommandRunnerTest resolves LocalFileSystem from fileSystemModule (koin)
            // and uses java.nio for temp dirs — JVM-only.
            implementation(projects.agenticHubClient.platform.fileSystem)
        }
    }
}
