plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

dependencies {
    implementation(libs.mcp.kotlin.sdk)
    // ktor Java engine: the server calls the aTimeLogger REST API over HTTPS; the MCP SDK
    // also needs an engine for its transports (the stdio probe works without one).
    implementation(libs.ktor.client.java)
    implementation(libs.ktor.client.contentNegotiation)
    implementation(libs.ktor.serialization.kotlinxJson)
    implementation(libs.kotlinx.serializationJson)
    implementation(libs.kotlinx.coroutinesCore)

    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutinesTest)
}

application {
    mainClass = "ru.den.writes.code.agenticHub.mcps.atimelogger.MainKt"
}

tasks.withType<AbstractCopyTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
