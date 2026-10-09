pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name="F1MultiView"
include(":app")

// CI and reproducible source-integration builds can opt into the pinned, patched
// AndroidX Media3 checkout. Normal local builds remain on published Media3 artifacts.
if (providers.gradleProperty("usePinnedMedia3Source").orNull == "true") {
    includeBuild("media3-source") {
        dependencySubstitution {
            substitute(module("androidx.media3:media3-exoplayer-hls"))
                .using(project(":lib-exoplayer-hls"))
            substitute(module("androidx.media3:media3-exoplayer-dash"))
                .using(project(":lib-exoplayer-dash"))
        }
    }
}
