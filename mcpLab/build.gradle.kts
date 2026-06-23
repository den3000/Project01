plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

dependencies {
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
