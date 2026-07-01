plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    // MemoryStore port lives in the conversation runtime; this module supplies
    // the file-backed impl. Profile/rule/task domain types come from features:agent
    // (re-exported by features:viewModel).
    implementation(projects.agenticHubClient.features.viewModel)
    implementation(projects.agenticHubClient.features.agent)
}
