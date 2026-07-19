plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

dependencies {
    implementation(libs.mcp.kotlin.sdk)
    // kotlinx-serialization-json: MCP tool schemas assembled with buildJsonObject/put,
    // and the users/tickets fixture is decoded via kotlinx.serialization.
    implementation(libs.kotlinx.serializationJson)
    implementation(libs.kotlinx.coroutinesCore)

    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
}

application {
    mainClass = "ru.den.writes.code.agenticHub.mcps.support.MainKt"
}

tasks.withType<AbstractCopyTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
