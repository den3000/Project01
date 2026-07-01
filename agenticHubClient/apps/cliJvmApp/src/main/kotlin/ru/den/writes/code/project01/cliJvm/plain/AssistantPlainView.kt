package ru.den.writes.code.project01.cliJvm.plain

import ru.den.writes.code.project01.cliJvm.AgentRef

/**
 * The model's reply on stdout, prefixed by the `[[AGENT:]]` tag in a multi-agent
 * session ([agent] non-null; a null profile shows as `default`).
 */
internal data class AssistantPlainView(val reply: String, val agent: AgentRef?) : PlainView {
    override fun stdout(): List<String> = buildList {
        if (agent != null) add("[[AGENT: ${agent.profileName ?: "default"}:${agent.modelId}]]")
        add(reply)
    }
}
