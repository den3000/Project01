package ru.den.writes.code.project01.cliJvm.tui

import com.github.ajalt.mordant.terminal.Terminal
import com.varabyte.kotter.foundation.text.green
import com.varabyte.kotter.foundation.text.magenta
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope
import ru.den.writes.code.project01.cliJvm.AgentRef

/**
 * The model's reply as an `"assistant │ …"` column. In a multi-agent session
 * ([agent] non-null) the `[[AGENT:]]` tag rides as the first line (magenta) and
 * the reply follows under the bar (green); otherwise just the reply (green).
 */
internal data class AssistantTuiView(val reply: String, val agent: AgentRef?) : TuiView {
    override fun RenderScope.render(terminal: Terminal, width: Int) {
        if (agent == null) {
            wrapWords("assistant", reply, width).forEach { green { textLine(it) } }
            return
        }
        val tag = "[[AGENT: ${agent.profileName ?: "default"}:${agent.modelId}]]"
        val tagRows = wrapWords("assistant", tag, width).size
        wrapWords("assistant", "$tag\n$reply", width).forEachIndexed { i, l ->
            if (i < tagRows) magenta { textLine(l) } else green { textLine(l) }
        }
    }
}
