package ru.den.writes.code.agenticHub.features.composeapp

import ru.den.writes.code.agenticHub.platform.greeting.Greeting

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController() = ComposeUIViewController { App(Greeting()) }