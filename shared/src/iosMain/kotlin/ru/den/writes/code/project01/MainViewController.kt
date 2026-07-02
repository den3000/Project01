package ru.den.writes.code.project01

import ru.den.writes.code.agenticHub.platform.greeting.Greeting

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController() = ComposeUIViewController { App(Greeting()) }