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
        namespace = "ru.den.writes.code.agenticHub.features.lifecycle.start"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            // First-launch dispatch: StartExecutor routes admin StartCommands through
            // AdminOps (list/clean/inflate/memory) and returns the session command for
            // the caller to run. Exposes StartCommand + AppDatabase through its API.
            api(projects.agenticHubClient.features.lifecycle.command)
            api(projects.agenticHubClient.platform.database)
            implementation(projects.agenticHubClient.features.memory)
            // Direct dep: startModule injects LocalFileSystem into StartExecutor/AdminOps
            // (was transitive through memory); homeDirectory() builds MEMORY_ROOT.
            implementation(projects.agenticHubClient.platform.fileSystem)
            // logErr() for the stderr half of the admin-notice stream.
            implementation(projects.agenticHubClient.platform.logging)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            // @IgnoreIos on StartExecutorTest (TestDb + eager fileSystemModule are TODO там).
            implementation(projects.agenticHubClient.testUtils)
        }
    }
}
