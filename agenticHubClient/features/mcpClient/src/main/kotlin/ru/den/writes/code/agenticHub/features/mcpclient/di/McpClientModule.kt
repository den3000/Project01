package ru.den.writes.code.agenticHub.features.mcpclient.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.features.mcpclient.McpToolClient

/**
 * Koin module for MCP tool clients. Each server command spawns its own client →
 * parameterized factory. Lifecycle (`connect`/`close`) stays with the caller —
 * a Koin factory doesn't own closing.
 */
val mcpClientModule: Module = module {
    factory { (command: List<String>) -> McpToolClient(command) }
}
