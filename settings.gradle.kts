pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Repositories are declared here only. A module that declares its own
    // repositories fails the build on purpose: it keeps the dependency graph
    // reproducible for CI and for anyone auditing this repo.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tailnet-byok"

include(":app")

// The `tailnet` module holds the gomobile-bound Go bridge and is only
// published as a prebuilt .aar (see docs/TSNET.md). It is intentionally NOT a
// Gradle module: the AAR is dropped into app/libs/ so that a plain
// `./gradlew assembleDebug` works without Go, gomobile or the NDK installed.
