import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

// No iOS target: mcp-kotlin-sdk publishes no iOS klib. With only JVM-family
// targets (jvm + android) Kotlin skips the common-metadata compile, so commonMain
// is effectively JVM — McpToolClient (ProcessBuilder + mcp-sdk stdio) stays common
// as-is, no expect/actual needed.
kotlin {
    jvm()

    androidLibrary {
        namespace = "ru.den.writes.code.agenticHub.features.mcpclient"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Implements the ToolExecutor port (features:llm) over an MCP server spawned
            // on stdio (mcp-sdk rides kotlinx-io for the transport).
            implementation(projects.agenticHubClient.features.llm)
            implementation(libs.mcp.kotlin.sdk)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
