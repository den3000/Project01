package ru.den.writes.code.project01.cliTui

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Panel
import com.varabyte.kotter.foundation.collections.liveListOf
import com.varabyte.kotter.foundation.input.Completions
import com.varabyte.kotter.foundation.input.Key
import com.varabyte.kotter.foundation.input.Keys
import com.varabyte.kotter.foundation.input.input
import com.varabyte.kotter.foundation.input.onInputEntered
import com.varabyte.kotter.foundation.input.onKeyPressed
import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.runUntilSignal
import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.foundation.text.green
import com.varabyte.kotter.foundation.text.magenta
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.render.RenderScope
import kotlin.sequences.forEach
import kotlin.text.trimEnd

sealed interface KotterView {
    fun RenderScope.render()

    fun renderIn(renderScope: RenderScope) = with(renderScope) { render() }

    data class SimpleListLine(val text: String): KotterView {
        override fun RenderScope.render() {
            green { textLine("you've entered: $text") }
        }
    }

    data class ProfileChangedLine(val text: String): KotterView {
        override fun RenderScope.render() {
            magenta { textLine("you've selected: $text") }
        }
    }

    data class BtmPanel(val terminal: Terminal, val title: String, val content: String): KotterView {
        override fun RenderScope.render() {
            val panel = terminal.render(
                Panel(
                    content = content,
                    title = title,
                ),
            )
            yellow { panel.trimEnd().lineSequence().forEach { textLine(it) } }
        }
    }

    sealed interface Picker: KotterView {
        val terminal: Terminal
        val options: List<String>
        val cursor: Int
        val onSelected: (Int) -> Unit

        fun copyOnCursorChanged(index: Int): Picker

        fun processInput(text: String) = when {
            text.isEmpty() -> {
                onSelected(cursor)
                true
            }
            (text.toIntOrNull() ?: 0) in 1..options.size -> {
                onSelected(text.toInt() - 1)
                true
            }
            else -> false
        }
        fun maybeCopyOnCursorMoveBy(key: Key): Picker? {
            return when (key) {
                Keys.Up -> copyOnCursorChanged(next())
                Keys.Down -> copyOnCursorChanged(previous())
                Keys.Escape -> null
                else -> this
            }
        }

        fun next(): Int = (cursor - 1 + options.size) % options.size
        fun previous(): Int = (cursor + 1) % options.size
    }

    data class ProfilePicker(
        override val terminal: Terminal,
        override val options: List<String>,
        override val cursor: Int,
        override val onSelected: (Int) -> Unit
    ): Picker {
        override fun RenderScope.render() {
            val body = options.mapIndexed { i, opt ->
                val pointer = if (i == cursor) "▶" else " "
                val mark = if (opt == options[cursor]) "  ← активный" else ""
                "$pointer ${i + 1}. $opt$mark"
            }.joinToString("\n")
            val box = terminal.render(Panel(content = body, title = "профили  ↑↓ · Enter · Esc"))
            magenta { box.trimEnd().lineSequence().forEach { textLine(it) } }
        }

        override fun copyOnCursorChanged(index: Int) = copy(cursor=index)
    }
}

fun runMyChat() = session {
    val widgets = Terminal(ansiLevel = AnsiLevel.NONE, width = 60)
    val inputs = liveListOf<KotterView>()
    var picker by liveVarOf<KotterView.Picker?>(null)
    var log: String  by liveVarOf("")

    section {
        inputs.forEach { it.renderIn(this) }

        text("Please enter something: "); input()
        textLine()

        if (picker != null) {
            picker?.renderIn(this)
        } else {
            KotterView.BtmPanel(
                terminal = widgets,
                title = "stats",
                content = "number of inputs: ${inputs.size}"
            ).renderIn(this)
        }

        if (log.isNotEmpty()) {
            textLine(log)
        }
    }.runUntilSignal {
        onKeyPressed {
            picker = picker?.maybeCopyOnCursorMoveBy(key)
        }

        onInputEntered {
            val entered = input.trim()
            clearInput()

            when {
                picker?.processInput(entered) == true -> picker = null
                entered == "/profiles" -> {
                    val options = listOf("admin", "user", "owner")
                    picker = KotterView.ProfilePicker(
                        terminal = widgets,
                        options = options,
                        cursor = 0,
                    ) {
                        log = "selectedItem: $it"
                        val selected = options[it]
                        inputs.add(KotterView.ProfileChangedLine(selected))
                    }
                    log += "> picker created"
                }
                entered == "/exit" -> signal()
                else -> {
                    inputs.add(KotterView.SimpleListLine(entered))
                    log += "> entered: $entered"
                    clearInput()
                }
            }
        }
    }
}