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
        namespace = "ru.den.writes.code.project01.features.memory"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Domain types (ProfileData/RuleEntry/TaskNotes, Message/Usage,
            // PricingRegistry) surface through the ports → api. The Room DB
            // (AppDatabase/DAO/entities) backs RoomHistoryStore → api too.
            api(projects.agenticHubClient.features.agent)
            api(projects.agenticHubClient.platform.database)
            implementation(libs.kotlinx.coroutinesCore)
        }
    }
}
