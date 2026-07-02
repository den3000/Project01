package ru.den.writes.code.agenticHub.mcps.openmeteo

/**
 * Entry point for openmeteo-mcp: an MCP server over stdio exposing the Open-Meteo
 * weather tool plus the scheduler tools. It is meant to be spawned by an MCP client
 * as a subprocess, so it has no modes or flags — any arguments are ignored and the
 * server runs until the client disconnects (stdin closes).
 */
suspend fun main() {
    runWeatherServer()
}
