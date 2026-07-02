plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    // Neutral command/config vocabulary (StartCommand/SessionConfig/ScheduleSpec/
    // MemoryAction) shared by lifecycle:start and lifecycle:session. Exposes
    // domain types through its API (ContextStrategyKind, StageAgentSpec/MemoryMode,
    // ModelProvider) → api.
    api(projects.agenticHubClient.features.memory)
    api(projects.agenticHubClient.features.agent)
    api(projects.agenticHubClient.features.llm)
}
