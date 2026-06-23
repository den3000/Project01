plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

dependencies {
    implementation(libs.mcp.kotlin.sdk)
    // The MCP SDK depends on ktor-client-core but ships no engine — provide the
    // project's standard Java engine (needed by HTTP/SSE transports; the stdio
    // transport works without it but the engine is harmless to keep).
    implementation(libs.ktor.client.java)
    implementation(libs.kotlinx.coroutinesCore)

    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
}

application {
    mainClass = "ru.den.writes.code.project01.mcpLab.MainKt"
}

tasks.withType<AbstractCopyTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
