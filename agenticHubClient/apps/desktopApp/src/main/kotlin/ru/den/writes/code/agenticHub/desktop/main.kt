package ru.den.writes.code.agenticHub.desktop

import ru.den.writes.code.agenticHub.features.composeapp.App
import ru.den.writes.code.agenticHub.platform.greeting.Greeting

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Project01",
    ) {
        App(Greeting())
    }
}