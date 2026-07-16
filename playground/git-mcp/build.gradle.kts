plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

dependencies {
    implementation(libs.mcp.kotlin.sdk)
    // kotlinx-serialization-json: the MCP tool input schemas are built with
    // buildJsonObject/put. No ktor engine — the stdio transport rides kotlinx-io.
    implementation(libs.kotlinx.serializationJson)
    implementation(libs.kotlinx.coroutinesCore)

    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
}

application {
    mainClass = "ru.den.writes.code.agenticHub.mcps.git.MainKt"
}

tasks.withType<AbstractCopyTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
