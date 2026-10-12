package com.yarmiplaytv.desktop

import com.yarmiplaytv.data.DesktopPaths
import com.yarmiplaytv.update.StoreUpdates
import com.yarmiplaytv.update.UpdateInstaller
import java.io.File
import java.util.concurrent.TimeUnit

/** How this copy of the app was installed, which decides where its updates come from. */
internal enum class InstallKind(
    /** The download page's name for its package in version.json; null when a store updates it. */
    val platform: String?,
) {
    /** The Windows installer (.msi). */
    MSI("windows"),
    /** The portable Windows zip. */
    PORTABLE("windows-portable"),
    /** The macOS disk image's app, in its .app bundle. */
    DMG("macos"),
    /** The download page's .deb. */
    DEB("linux"),
    /** Run from a build, or a package the app can't update itself (.rpm, AppImage): the notice links to the page. */
    WINDOWS_OTHER("windows"),
    MAC_OTHER("macos"),
    LINUX_OTHER("linux"),
    MS_STORE(null),
    FLATPAK(null),
    SNAP(null),
    AUR(null),
    /** Another store's package (yarmiplaytv.store set to something else). */
    OTHER_STORE(null),
}

/** Where this copy's updates come from. */
internal sealed interface UpdateSource {
    /** The download page: [platform] picks the package in version.json, [installer] installs it (null: link to the page). */
    data class DownloadPage(val platform: String, val installer: UpdateInstaller?) : UpdateSource

    /** A store updates the app; it never looks at the download page. */
    data class Store(val store: StoreUpdates) : UpdateSource
}

/** A store that updates the app outside it: its page, and the command that updates it where there is one. */
internal data class DesktopStore(
    override val name: String,
    override val pageUrl: String?,
    override val command: String? = null,
) : StoreUpdates

/** The packaged app's launcher (YarmiplayTV.exe, the .app's binary), which jpackage names in jpackage.app-path. */
internal val installedLauncher: File? get() = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }

internal const val MS_STORE_ID = "9PDBVR6W069J"
internal const val FLATPAK_ID = "com.yarmiplay.TV"
internal const val SNAP_NAME = "yarmiplaytv"
internal const val AUR_PACKAGE = "yarmiplaytv-bin"

/**
 * Tells how the app was installed. The Microsoft Store and AUR packages set yarmiplaytv.store ([store]); Flatpak
 * and Snap are recognised by the variables their sandboxes set; the portable zip's launcher sets
 * yarmiplaytv.portable ([portable]); every packaged launcher sets jpackage.app-path ([launcher]); [dpkgOwns] tells
 * whether the .deb installed a file.
 */
internal fun installKind(
    osName: String = System.getProperty("os.name").orEmpty(),
    store: String? = System.getProperty("yarmiplaytv.store"),
    env: (String) -> String? = System::getenv,
    launcher: File? = installedLauncher,
    portable: Boolean = DesktopPaths.portableDir() != null,
    dpkgOwns: (File) -> Boolean = ::dpkgOwns,
): InstallKind {
    when (store.orEmpty()) {
        "" -> Unit
        "msstore" -> return InstallKind.MS_STORE
        "aur" -> return InstallKind.AUR
        else -> return InstallKind.OTHER_STORE
    }
    if (!env("FLATPAK_ID").isNullOrEmpty()) return InstallKind.FLATPAK
    if (!env("SNAP").isNullOrEmpty()) return InstallKind.SNAP
    val os = osName.lowercase()
    return when {
        os.startsWith("windows") -> when {
            portable && launcher != null -> InstallKind.PORTABLE
            launcher != null -> InstallKind.MSI
            else -> InstallKind.WINDOWS_OTHER
        }
        os.startsWith("mac") || os.startsWith("darwin") ->
            if (launcher != null && MacUpdater.bundleOf(launcher) != null) InstallKind.DMG else InstallKind.MAC_OTHER
        else -> if (launcher != null && dpkgOwns(launcher)) InstallKind.DEB else InstallKind.LINUX_OTHER
    }
}

/**
 * Where updates come from for an app installed as [kind]: the download page with the installer that fits, or the
 * store. [launcher] is the app's launcher and [args] the arguments it started with, to start it again.
 */
internal fun updateSource(
    kind: InstallKind = installKind(),
    launcher: File? = installedLauncher,
    args: List<String> = emptyList(),
): UpdateSource {
    kind.platform?.let { platform ->
        val installer = launcher?.let {
            when (kind) {
                InstallKind.MSI -> WindowsUpdater(it, args)
                InstallKind.PORTABLE -> PortableUpdater(it, args)
                InstallKind.DMG -> if (MacUpdater.bundleOf(it) != null) MacUpdater(it, args) else null
                InstallKind.DEB -> DebUpdater(it, args)
                else -> null
            }
        }
        return UpdateSource.DownloadPage(platform, installer)
    }
    return UpdateSource.Store(
        when (kind) {
            InstallKind.MS_STORE -> DesktopStore("Microsoft Store", "ms-windows-store://pdp/?productid=$MS_STORE_ID")
            InstallKind.FLATPAK -> DesktopStore("Flathub", "appstream://$FLATPAK_ID", "flatpak update $FLATPAK_ID")
            InstallKind.SNAP -> DesktopStore("Snap Store", "snap://$SNAP_NAME", "snap refresh $SNAP_NAME")
            InstallKind.AUR -> DesktopStore("AUR", "https://aur.archlinux.org/packages/$AUR_PACKAGE", "yay -S $AUR_PACKAGE")
            else -> DesktopStore("the store it came from", null)
        },
    )
}

/** The download page's name for this OS's package; null in a store build, which mustn't update outside its store. */
internal fun updatePlatform(
    osName: String = System.getProperty("os.name").orEmpty(),
    store: String? = System.getProperty("yarmiplaytv.store"),
    env: (String) -> String? = System::getenv,
): String? = installKind(osName, store, env, launcher = null, portable = false).platform

/** Whether the .deb package installed [file] (`dpkg -S` knows it). */
private fun dpkgOwns(file: File): Boolean = runCatching {
    val process = ProcessBuilder("dpkg", "-S", file.absolutePath)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
}.getOrDefault(false)
