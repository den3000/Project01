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
include(":agenticHubClient:platform:memoryProvider")
include(":agenticHubClient:features:llm")
include(":agenticHubClient:features:agent")
include(":agenticHubClient:features:viewModel")
include(":androidApp")
include(":desktopApp")
include(":agenticHubClient:apps:cliJvmApp")
include(":shared")
include(":playground:cliTui")
include(":playground:openmeteo-mcp")
include(":playground:localfs-mcp")
include(":scheduling")