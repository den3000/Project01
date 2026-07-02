import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

dependencies {
    testImplementation(projects.agenticHubClient.testing)
    implementation(projects.agenticHubClient.features.lifecycle.session)
    implementation(projects.agenticHubClient.features.lifecycle.start)
    implementation(projects.agenticHubClient.features.lifecycle.command)
    implementation(projects.agenticHubClient.features.memory)
    // Types resolved via parametersOf at the composition root (LlmApi/ModelProvider,
    // Routed* agents) — partly transitive through :session api, made explicit here.
    implementation(projects.agenticHubClient.features.llm)
    implementation(projects.agenticHubClient.features.agent)
    implementation(projects.agenticHubClient.platform.config)
    implementation(projects.agenticHubClient.platform.database)
    // fileSystemModule for the composition root's startKoin(...) list.
    implementation(projects.agenticHubClient.platform.fileSystem)
    implementation(projects.agenticHubClient.features.mcpClient)
    implementation(projects.scheduling)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.java)
    implementation(libs.ktor.client.contentNegotiation)
    implementation(libs.ktor.serialization.kotlinxJson)
    implementation(libs.kotlinx.serializationJson)
    implementation(libs.kotlinx.coroutinesCore)
    // TUI (-tui): Kotter drives the interactive screen, Mordant renders the
    // stats panel into a string (AnsiLevel.NONE) that Kotter then colours.
    implementation(libs.kotter)
    implementation(libs.mordant)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.koin.core)

    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutinesTest)
}

application {
    mainClass = "ru.den.writes.code.agenticHub.cliJvm.MainKt"
}

// Hook the user's terminal into the `run` task so the REPL can actually
// read stdin when launched via `./gradlew :cliJvmApp:run`.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.withType<AbstractCopyTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-Xexplicit-backing-fields"))
}