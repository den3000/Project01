plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    // MemoryStore port lives in features:memory; this module supplies the
    // file-backed impl. Profile/rule/task domain types come from features:agent.
    implementation(projects.agenticHubClient.features.memory)
    implementation(projects.agenticHubClient.features.agent)
}
