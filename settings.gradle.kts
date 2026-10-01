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

rootProject.name = "SyncplayTV"

include(":app")
include(":syncplay-protocol")
include(":media-source")
include(":player-mpv")
include(":player-mpv-desktop")
include(":desktop")
