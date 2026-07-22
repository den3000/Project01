rootProject.name = "Project01"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":agenticHubClient:platform:logging")
include(":agenticHubClient:platform:config")
include(":agenticHubClient:platform:database")
include(":agenticHubClient:platform:fileSystem")
include(":agenticHubClient:platform:network")
include(":agenticHubClient:testUtils")
include(":agenticHubClient:platform:greeting")
include(":agenticHubClient:features:mcpClient")
include(":agenticHubClient:features:llm")
include(":agenticHubClient:features:agent")
include(":agenticHubClient:features:memory")
include(":agenticHubClient:features:rag")
include(":agenticHubClient:features:lifecycle:command")
include(":agenticHubClient:features:lifecycle:session")
include(":agenticHubClient:features:lifecycle:start")
include(":agenticHubClient:features:composeApp")
include(":agenticHubClient:apps:androidApp")
include(":agenticHubClient:apps:desktopApp")
include(":agenticHubClient:apps:cliJvmApp")
include(":playground:cliTui")
include(":playground:openmeteo-mcp")
include(":playground:localfs-mcp")
include(":playground:git-mcp")
include(":playground:support-mcp")
include(":playground:atimelogger-mcp")
include(":scheduling")