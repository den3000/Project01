import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    // Portable conversation runtime (MVI stack + turn engine + intent/prompt
    // sources + scheduler glue). Depends on the command vocabulary + domain
    // features; scheduling is JVM-only, so this module is JVM for now.
    api(projects.agenticHubClient.features.lifecycle.command)
    api(projects.agenticHubClient.features.memory)
    api(projects.agenticHubClient.features.agent)
    implementation(projects.agenticHubClient.platform.logging)
    implementation(projects.scheduling)
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.kotlinx.serializationJson)
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    // SessionViewModel exposes `state` via an explicit backing field.
    freeCompilerArgs.set(listOf("-Xexplicit-backing-fields"))
}
