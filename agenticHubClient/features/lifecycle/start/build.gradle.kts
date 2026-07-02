plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    // First-launch dispatch: StartExecutor routes admin StartCommands through
    // AdminOps (list/clean/inflate/memory) and returns the session command for
    // the caller to run. Exposes StartCommand + AppDatabase through its API.
    api(projects.agenticHubClient.features.lifecycle.command)
    api(projects.agenticHubClient.platform.database)
    implementation(projects.agenticHubClient.features.memory)
    implementation(libs.kotlinx.coroutinesCore)
}
