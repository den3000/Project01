import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.ksp)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    jvm()

    androidLibrary {
        namespace = "ru.den.writes.code.agenticHub.platform.database"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Room-KMP DB layer (AppDatabase/DAO/entities) + the common
            // buildDatabase() that navigates driver/migrations/WAL. The bundled
            // SQLite driver is multiplatform (jvm/android/native).
            // api: AppDatabase extends RoomDatabase, so the Room runtime is part of
            // this module's public surface (consumers call AppDatabase methods).
            api(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutinesCore)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(projects.agenticHubClient.testing)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}

// Room's KSP runs per target (it generates a platform-specific implementation
// of the @Database + @ConstructedBy constructor). exportSchema = false, so no
// schema directory is configured.
dependencies {
    add("kspJvm", libs.androidx.room.compiler)
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}
