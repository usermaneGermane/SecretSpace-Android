rootProject.name = "SecretSpace-Android"
include(":core", ":app")

dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
