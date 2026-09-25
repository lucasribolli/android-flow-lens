pluginManagement {
    includeBuild("../..")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "flow-lens-android-fixture"
include(":app")
