plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

dependencies {
    // Чистый JVM-стек, без Compose: Kotter — интерактивный declarative каркас,
    // Mordant — движок виджетов (Panel/таблицы/цвета). Mosaic выброшен: его
    // нативный raw-ввод (mosaic-tty) не перехватывал клавиши на macOS-терминале.
    implementation(libs.kotter)
    implementation(libs.mordant)
    implementation(libs.kotlinx.coroutinesCore)
}

application {
    mainClass = "ru.den.writes.code.project01.cliTui.MainKt"
}

// Прокинуть терминал в `run`, чтобы прототипы читали stdin.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
