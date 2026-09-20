/*
 * You-Tube — settings.gradle.kts
 *
 * Dependency resolution is centralised here (FAIL_ON_PROJECT_REPOS) so that every
 * module resolves from exactly these three repositories. JitPack is required because
 * NewPipeExtractor is consumed as a source-built artifact (no Google-hosted mirror).
 */

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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io") {
            // Only NewPipe / JitPack-built artifacts are ever requested from here.
            content { includeGroup("com.github.TeamNewPipe") }
        }
    }
}

rootProject.name = "You-Tube"
include(":app")
