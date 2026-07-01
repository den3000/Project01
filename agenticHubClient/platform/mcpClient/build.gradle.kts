plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    // Implements the ToolExecutor port (features:llm) by spawning an MCP server
    // as a subprocess over stdio (mcp-sdk rides kotlinx-io for the transport).
    implementation(projects.agenticHubClient.features.llm)
    implementation(libs.mcp.kotlin.sdk)
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.kotlinx.serializationJson)

    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
}
