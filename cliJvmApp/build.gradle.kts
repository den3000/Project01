plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    application
}

dependencies {
    implementation(projects.shared)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.java)
    implementation(libs.ktor.client.contentNegotiation)
    implementation(libs.ktor.serialization.kotlinxJson)
    implementation(libs.kotlinx.serializationJson)
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutinesTest)
}

application {
    mainClass = "ru.den.writes.code.project01.cliJvm.MainKt"
}

// Hook the user's terminal into the `run` task so the REPL can actually
// read stdin when launched via `./gradlew :cliJvmApp:run`.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.withType<AbstractCopyTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
