plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
}

dependencies {
    // HistoryStore port + SessionStats + Summary/FactsSnapshot live in the
    // conversation runtime; this module supplies the Room-backed impl. LLM
    // domain types (Message/Role/Usage, PricingRegistry) come transitively via
    // features:viewModel → features:agent → features:llm.
    implementation(projects.agenticHubClient.features.viewModel)
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room.compiler)
}
