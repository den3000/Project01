import com.codingfeline.buildkonfig.compiler.FieldSpec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.buildKonfig)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    jvm()
    
    androidLibrary {
       namespace = "ru.den.writes.code.project01.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            // Domain core (LLM API + DTOs), shared across all targets. The
            // ktor engine is intentionally absent — callers inject an
            // HttpClient, so the HttpClient type leaks through public *Api
            // constructors via api(); coroutines likewise (suspend API).
            // content-negotiation + serialization stay implementation: they
            // are internal to the *Api / DTO implementations.
            api(libs.ktor.client.core)
            api(libs.kotlinx.coroutinesCore)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.kotlinxJson)
            implementation(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
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
    packageName = "ru.den.writes.code.project01"
    objectName = "BuildKonfig"
    exposeObjectWithName = "BuildKonfig"

    defaultConfigs {
        buildConfigField(FieldSpec.Type.STRING, "GEMINI_API_KEY", getStringPropertyOrEnvVar("GEMINI_API_KEY"))
        buildConfigField(FieldSpec.Type.STRING, "OPENROUTER_API_KEY", getStringPropertyOrEnvVar("OPENROUTER_API_KEY"))
        buildConfigField(FieldSpec.Type.STRING, "HUGGINGFACE_API_KEY", getStringPropertyOrEnvVar("HUGGINGFACE_API_KEY"))
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}