package ru.den.writes.code.agenticHub.desktop

import ru.den.writes.code.project01.App
import ru.den.writes.code.project01.Greeting

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