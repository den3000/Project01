import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

// Shared test support, consumed ONLY via testImplementation(projects.agenticHubClient.testing)
// — never shipped in production. Kotlin MP has no java-test-fixtures, so a dedicated module
// is the idiomatic way to share test fakes without duplicating them per module.
kotlin {
    iosArm64()
    iosSimulatorArm64()

    jvm()

    androidLibrary {
        namespace = "ru.den.writes.code.agenticHub.testing"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            // FakeLlmApi implements LlmApi.
            api(projects.agenticHubClient.features.llm)
            // testLocalFileSystem() hands consumers a LocalFileSystem resolved from
            // fileSystemModule (api: the type appears in helper return signatures).
            api(projects.agenticHubClient.platform.fileSystem)
            implementation(libs.koin.core)
        }
    }
}
