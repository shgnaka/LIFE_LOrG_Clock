pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

val syncCoreDir = (
    gradle.startParameter.projectProperties["synccore.dir"]
        ?: System.getenv("SYNC_CORE_DIR")
    )
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

if (syncCoreDir != null) {
    includeBuild(syncCoreDir) {
        dependencySubstitution {
            substitute(module("io.github.shgnaka.synccore:sync-core-engine"))
                .using(project(":sync-core-engine"))
        }
    }
}

rootProject.name = "org-clock-android"
include(":app")
include(":benchmark")
include(":shared")
