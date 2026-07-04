import org.gradle.api.tasks.testing.Test

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.buildKonfig) apply false
}

// Central gate for live tests (see LIVE_TESTS.md). A test class named `*LiveTest` hits a
// real external service (Ollama, …) and is opt-in: excluded from every module's test
// task (`test` and `jvmTest` alike) unless `-PliveTests` is set. One place, so any new
// `*LiveTest` in any module is auto-gated — no per-module boilerplate.
subprojects {
    tasks.withType<Test>().configureEach {
        if (!project.hasProperty("liveTests")) {
            filter {
                isFailOnNoMatchingTests = false
                excludeTestsMatching("*LiveTest")
            }
        }
    }
}