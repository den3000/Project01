import com.codingfeline.buildkonfig.compiler.FieldSpec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.buildKonfig)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    jvm()

    androidLibrary {
        namespace = "ru.den.writes.code.project01.platform.config"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

fun getStringPropertyOrEnvVar(name: String): String {
    val localProps = rootProject.file("local.properties")
    if (localProps.exists()) {
        val props = Properties().apply { localProps.inputStream().use(::load) }
        props.getProperty(name)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return System.getenv(name).orEmpty()
}

buildkonfig {
    packageName = "ru.den.writes.code.agenticHub"
    objectName = "BuildKonfig"
    exposeObjectWithName = "BuildKonfig"

    defaultConfigs {
        buildConfigField(FieldSpec.Type.STRING, "GEMINI_API_KEY", getStringPropertyOrEnvVar("GEMINI_API_KEY"))
        buildConfigField(FieldSpec.Type.STRING, "OPENROUTER_API_KEY", getStringPropertyOrEnvVar("OPENROUTER_API_KEY"))
        buildConfigField(FieldSpec.Type.STRING, "HUGGINGFACE_API_KEY", getStringPropertyOrEnvVar("HUGGINGFACE_API_KEY"))
    }
}
