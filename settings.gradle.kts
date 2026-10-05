// -PofflineRepo=<dir>: resolve plugins and libraries only from that Maven folder (the Flatpak build, which has no
// network; scripts/flatpak_gradle_sources.py lists its files).
pluginManagement {
    val offlineRepo = providers.gradleProperty("offlineRepo").orNull
    repositories {
        if (offlineRepo != null) {
            maven { url = uri(offlineRepo) }
        } else {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val offlineRepo = providers.gradleProperty("offlineRepo").orNull
    repositories {
        if (offlineRepo != null) {
            maven { url = uri(offlineRepo) }
        } else {
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "YarmiplayTV"

// -PdesktopOnly: only the desktop app, without the Android SDK (the Linux packages).
val desktopOnly = providers.gradleProperty("desktopOnly").isPresent

if (!desktopOnly) include(":app")
include(":syncplay-protocol")
include(":media-source")
include(":player-api")
if (!desktopOnly) include(":player-mpv")
include(":player-mpv-desktop")
include(":shared")
include(":desktop")
