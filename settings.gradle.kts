pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        // Pleos SDK
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}
rootProject.name = "car-care-agent"
include(":core:domain", ":integrations:pleos", ":app")
