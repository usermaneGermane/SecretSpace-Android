rootProject.name = "SecretSpace-Android"
include(":core")

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
