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

/** The packaged app's launcher (YarmiplayTV.exe, the .app's binary), resolved from jpackage or the running app image. */
internal val installedLauncher: File? get() = resolveInstalledLauncher()

internal const val MS_STORE_ID = "9PDBVR6W069J"
internal const val FLATPAK_ID = "com.yarmiplay.TV"
internal const val SNAP_NAME = "yarmiplaytv"
internal const val AUR_PACKAGE = "yarmiplaytv-bin"

private val windowsInvalidPathChars = charArrayOf('<', '>', '"', '|', '?', '*')

/**
 * Cleans a candidate path string and rejects corrupted values such as empty strings, invalid path characters,
 * or two drive paths concatenated together (e.g. `C:\Users\Dor: C:\Users\Dor\app\.jpackage.xml`).
 */
internal fun cleanInstallPath(raw: String?): File? {
    val trimmed = raw?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (trimmed.any { it in windowsInvalidPathChars || it.code < 32 }) return null
    // On Windows a colon is only valid as the drive separator at index 1 (e.g. C:\...).
    val firstColon = trimmed.indexOf(':')
    if (firstColon >= 0) {
        if (firstColon != 1 || !trimmed[0].isLetter()) return null
        if (trimmed.indexOf(':', startIndex = 2) >= 0) return null
        // A drive-relative path like "C:foo" resolves against the per-drive working directory; require "C:\" or "C:/".
        if (trimmed.length < 3 || (trimmed[2] != '\\' && trimmed[2] != '/')) return null
    }
    return File(trimmed)
}

/**
 * Whether [dir] is a safe, dedicated application install folder rather than a filesystem root, `C:\Users`,
 * a user profile root (`C:\Users\Dor`, `C:\Users\Dor Farhi`), `AppData`, `Program Files` root, or a top-level
 * user shell/OneDrive folder (`Desktop`, `Documents`, `OneDrive`, etc.).
 */
internal fun isSafeWindowsInstallDir(
    dir: File?,
    home: String = System.getProperty("user.home").orEmpty(),
    env: (String) -> String? = System::getenv,
): Boolean {
    if (dir == null) return false
    val cleaned = cleanInstallPath(dir.path) ?: return false
    val norm = normalizeSlashes(cleaned.path)
    // Require an absolute path (drive root C:/... or UNC //server/share/... or POSIX /... in cross-platform tests).
    val isDriveAbs = norm.length >= 3 && norm[0].isLetter() && norm[1] == ':' && norm[2] == '/'
    val isUnc = norm.startsWith("//")
    val isPosixAbs = norm.startsWith("/")
    if (!isDriveAbs && !isUnc && !isPosixAbs) return false

    val segments = norm.trim('/').split('/').filter { it.isNotEmpty() }
    // Never a drive root ("C:/") or top-level system/container folder ("C:/Users", "C:/Windows", "C:/Program Files").
    if (isDriveAbs && segments.size < 2) return false
    if (isUnc && segments.size < 3) return false
    if (isPosixAbs && !isUnc && segments.isEmpty()) return false

    val lower = norm.lowercase()
    val lowerSegments = segments.map { it.lowercase() }

    if (isDriveAbs) {
        when (lowerSegments[1]) {
            "windows" -> return false
            "users" -> {
                // C:/Users and every direct child C:/Users/<user> (user profile root, Public, Default) are unsafe.
                if (lowerSegments.size <= 3) return false
                val sub = lowerSegments[3]
                // Profile container folders directly under C:/Users/<user>/...
                if (lowerSegments.size == 4 && sub in profileContainerNames) return false
                if (sub == "appdata") {
                    // C:/Users/<user>/AppData and C:/Users/<user>/AppData/Local|Roaming|LocalLow
                    if (lowerSegments.size <= 5) return false
                    // C:/Users/<user>/AppData/Local/Programs or C:/Users/<user>/AppData/Local/Temp itself
                    if (lowerSegments.size == 6 && lowerSegments[4] == "local" && lowerSegments[5] in setOf("programs", "temp")) {
                        return false
                    }
                }
                if (sub.startsWith("onedrive")) {
                    // C:/Users/<user>/OneDrive or C:/Users/<user>/OneDrive/Desktop|Documents|Pictures|...
                    if (lowerSegments.size == 4) return false
                    if (lowerSegments.size == 5 && lowerSegments[4] in profileContainerNames) return false
                }
            }
            "program files", "program files (x86)", "programdata" -> {
                if (lowerSegments.size <= 2) return false
            }
        }
    } else if (isPosixAbs && !isUnc) {
        if (lowerSegments[0] in setOf("users", "home") && lowerSegments.size <= 2) return false
    }

    val forbiddenExact = mutableSetOf<String>()
    fun addForbiddenProfile(profileRaw: String?) {
        val p = cleanInstallPath(profileRaw)?.let { normalizeSlashes(it.path).lowercase() }?.takeIf { it.isNotEmpty() } ?: return
        forbiddenExact += p
        for (name in profileContainerNames) forbiddenExact += "$p/$name"
        forbiddenExact += "$p/appdata/local"
        forbiddenExact += "$p/appdata/roaming"
        forbiddenExact += "$p/appdata/locallow"
        forbiddenExact += "$p/appdata/local/programs"
        forbiddenExact += "$p/appdata/local/temp"
    }
    addForbiddenProfile(home)
    addForbiddenProfile(env("USERPROFILE"))

    for (key in listOf("APPDATA", "LOCALAPPDATA", "ProgramFiles", "ProgramFiles(x86)", "ProgramW6432", "ProgramData", "SystemRoot", "windir", "TEMP", "TMP")) {
        cleanInstallPath(env(key))?.let { forbiddenExact += normalizeSlashes(it.path).lowercase() }
    }
    for (key in listOf("OneDrive", "OneDriveConsumer", "OneDriveCommercial")) {
        val od = cleanInstallPath(env(key))?.let { normalizeSlashes(it.path).lowercase() } ?: continue
        forbiddenExact += od
        for (name in profileContainerNames) forbiddenExact += "$od/$name"
    }

    if (lower in forbiddenExact) return false
    return true
}

private val profileContainerNames = setOf(
    "appdata",
    "desktop",
    "documents",
    "documenten",
    "dokumente",
    "documentos",
    "downloads",
    "pictures",
    "afbeeldingen",
    "music",
    "videos",
    "onedrive",
    "contacts",
    "favorites",
    "links",
    "saved games",
    "searches",
)

private fun normalizeSlashes(path: String): String =
    path.replace('\\', '/').trimEnd('/').ifEmpty { "/" }

/** Whether [dir] lives under a per-machine system folder (`Program Files`, `Program Files (x86)`, `ProgramData`). */
internal fun isPerMachineInstallDir(
    dir: File?,
    env: (String) -> String? = System::getenv,
): Boolean {
    if (dir == null) return false
    val cleaned = cleanInstallPath(dir.path) ?: return false
    val lower = normalizeSlashes(cleaned.path).lowercase()
    val roots = buildList {
        for (key in listOf("ProgramFiles", "ProgramFiles(x86)", "ProgramW6432", "ProgramData")) {
            cleanInstallPath(env(key))?.let { add(normalizeSlashes(it.path).lowercase()) }
        }
    }
    if (roots.any { lower == it || lower.startsWith("$it/") }) return true
    val segs = lower.trim('/').split('/')
    return segs.size >= 3 && segs[0].endsWith(":") && segs[1] in setOf("program files", "program files (x86)", "programdata")
}

/**
 * Whether replacing `app` and `runtime` in place with [PortableUpdater] is safe in [dir].
 * It is unsafe if [dir] is a user profile/container folder, a per-machine `Program Files` directory,
 * or an existing read-only directory; in those cases the updater runs `setup.exe` instead.
 */
internal fun isSafePortableDir(
    dir: File?,
    home: String = System.getProperty("user.home").orEmpty(),
    env: (String) -> String? = System::getenv,
): Boolean {
    if (!isSafeWindowsInstallDir(dir, home, env)) return false
    if (isPerMachineInstallDir(dir, env)) return false
    if (dir != null && dir.exists() && !dir.canWrite()) return false
    return true
}

/**
 * Resolves the running packaged launcher from `jpackage.app-path`, the bundled `runtime` / `app` image layout,
 * or the current process command, never returning a bare file in the user profile root when a real install exists.
 */
internal fun resolveInstalledLauncher(
    osName: String = System.getProperty("os.name").orEmpty(),
    appPath: String? = System.getProperty("jpackage.app-path"),
    javaHome: String? = System.getProperty("java.home"),
    classPath: String? = System.getProperty("java.class.path"),
    processCommand: String? = runCatching { ProcessHandle.current().info().command().orElse(null) }.getOrNull(),
    home: String = System.getProperty("user.home").orEmpty(),
    env: (String) -> String? = System::getenv,
): File? {
    val isWin = osName.startsWith("Windows", ignoreCase = true)
    val exeName = if (isWin) "YarmiplayTV.exe" else "YarmiplayTV"

    fun validLauncher(file: File?, requireSafeDir: Boolean): File? {
        val candidate = file?.let { cleanInstallPath(it.path) }?.takeIf { it.isFile } ?: return null
        val abs = candidate.absoluteFile
        if (!isWin || !requireSafeDir) return abs
        return abs.takeIf { isSafeWindowsInstallDir(it.parentFile, home, env) }
    }

    // 1. jpackage.app-path when it sits in a safe install folder.
    val fromProp = appPath?.let(::cleanInstallPath)
    validLauncher(fromProp, requireSafeDir = true)?.let { return it }

    // 2. Running app image: <installDir>/runtime (java.home) or <installDir>/app/*.jar (java.class.path).
    if (!javaHome.isNullOrBlank()) {
        val runtimeDir = cleanInstallPath(javaHome)
        if (runtimeDir?.name.equals("runtime", ignoreCase = true)) {
            val imageRoot = runtimeDir?.parentFile
            val candidate = imageRoot?.let {
                if (isWin) File(it, exeName)
                else File(it, "bin/$exeName").takeIf(File::isFile) ?: File(it, "Contents/MacOS/$exeName")
            }
            validLauncher(candidate, requireSafeDir = true)?.let { return it }
        }
    }
    if (!classPath.isNullOrBlank()) {
        for (entry in classPath.split(File.pathSeparatorChar)) {
            val jar = cleanInstallPath(entry) ?: continue
            val parent = jar.parentFile ?: continue
            if (parent.name.equals("app", ignoreCase = true)) {
                val imageRoot = parent.parentFile ?: continue
                val candidate = if (isWin) File(imageRoot, exeName) else File(imageRoot, "bin/$exeName")
                validLauncher(candidate, requireSafeDir = true)?.let { return it }
            }
        }
    }

    // 3. ProcessHandle command when it names the launcher directly.
    if (!processCommand.isNullOrBlank()) {
        val cmdFile = cleanInstallPath(processCommand)
        if (cmdFile?.name.equals(exeName, ignoreCase = true)) {
            validLauncher(cmdFile, requireSafeDir = true)?.let { return it }
        }
    }

    // 4. Fallback: if jpackage.app-path is an existing file in an unsafe folder (e.g. a previous broken install in C:\Users\Dor),
    // return it so the app still knows it is packaged and WindowsUpdater can migrate it into %LOCALAPPDATA%\YarmiplayTV.
    return validLauncher(fromProp, requireSafeDir = false)
}

/**
 * Resolves the Windows installation directory from the launcher, the running app image, the registry uninstall
 * entry, or `%LOCALAPPDATA%\YarmiplayTV` — never the user profile root or a container folder.
 */
internal fun resolveWindowsInstallDir(
    launcher: File?,
    appImageDir: File? = null,
    registryDir: File? = null,
    home: String = System.getProperty("user.home").orEmpty(),
    env: (String) -> String? = System::getenv,
): File {
    val launcherDir = launcher?.let { it.parentFile ?: it.absoluteFile.parentFile }
    if (isSafeWindowsInstallDir(launcherDir, home, env)) return launcherDir!!
    if (isSafeWindowsInstallDir(appImageDir, home, env)) return appImageDir!!
    if (isSafeWindowsInstallDir(registryDir, home, env)) return registryDir!!
    val local = env("LOCALAPPDATA")?.let(::cleanInstallPath)?.path?.takeIf { it.isNotBlank() }
        ?: File(home.ifBlank { "." }, "AppData${File.separator}Local").path
    return File(local, "YarmiplayTV")
}

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
            portable && launcher != null && isSafePortableDir(launcher.parentFile ?: launcher.absoluteFile.parentFile, env = env) ->
                InstallKind.PORTABLE
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
                InstallKind.PORTABLE -> {
                    val dir = it.parentFile ?: it.absoluteFile.parentFile
                    if (isSafePortableDir(dir)) PortableUpdater(it, args) else WindowsUpdater(it, args)
                }
                InstallKind.DMG -> if (MacUpdater.bundleOf(it) != null) MacUpdater(it, args) else null
                InstallKind.DEB -> DebUpdater(it, args)
                else -> null
            }
        }
        val effectivePlatform = if (kind == InstallKind.PORTABLE && installer is WindowsUpdater) "windows" else platform
        return UpdateSource.DownloadPage(effectivePlatform, installer)
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
