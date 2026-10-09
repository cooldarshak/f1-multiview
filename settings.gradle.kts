pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.gradleProperty("usePinnedMedia3Source").orNull == "true") {
            // The pinned source build publishes patched HLS/DASH artifacts to this runner's
            // local Maven repository before the app build. Avoid composite-build classpath
            // collisions between the two independent Gradle build-logic environments.
            mavenLocal()
        }
        google()
        mavenCentral()
    }
}
rootProject.name="F1MultiView"
include(":app")
