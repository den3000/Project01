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
        namespace = "ru.den.writes.code.agenticHub.platform.network"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            // networkModule (di) binds the shared HttpClient. The HttpClient type is
            // this module's public vocabulary (consumers resolve it from the graph) →
            // api. The concrete engine (Java) + JSON/timeout config are JVM-only and
            // live in jvmMain; android/ios actuals are TODO until those apps need HTTP.
            api(libs.ktor.client.core)
            implementation(libs.koin.core)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.java)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.kotlinxJson)
            implementation(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // @IgnoreIos (skip networkModule test on iOS — actual is TODO там).
            implementation(projects.agenticHubClient.testUtils)
        }
    }
}
