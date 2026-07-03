import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Кросс-каттинг тест-утилиты (не фейки — те живут рядом со своими реализациями).
// Подключается через `commonTest.dependencies { implementation(projects.agenticHubClient.testUtils) }`.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    jvm()

    androidLibrary {
        namespace = "ru.den.writes.code.agenticHub.testutils"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            // @IgnoreIos актуализируется на iOS через kotlin.test.Ignore.
            api(libs.kotlin.test)
        }
    }
}
