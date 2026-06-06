plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

dependencies {
    implementation(projects.shared)
}

application {
    mainClass = "ru.den.writes.code.project01.cli.MainKt"
}
