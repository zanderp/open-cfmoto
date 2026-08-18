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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Open CfMoto"

// Composite build: consume the extracted Overtake map/routing/search library (:overtake-maps) from
// the sibling repo without a publish step. Overtake lives at E:\Desarrollo\Activos\overtake, a direct
// sibling of this fork, so the relative path is stable across clones. Gradle auto-substitutes an
// external `dev.overtake:overtake-maps` dependency (see app/build.gradle.kts) for that included
// build's project (its `group`/`name` match). Both builds share the same AGP 9.2.1 / Kotlin 2.2.0 /
// Gradle 9.4.1 toolchain, so the composite loads a single, compatible plugin classpath.
includeBuild("../overtake")

include(":app")
// BRouter offline routing engine now lives in the Overtake library (:overtake-maps depends on the
// lib's own vendored :brouter). The fork's identical copy was retired in the Router-extraction stage,
// so routing pulls BRouter transitively from the composite build — no duplicate btools.* on the
// classpath (which would fail dex merge).
 