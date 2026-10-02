package com.yarmiplaytv.ui.shared

data class OpenSourceComponent(val name: String, val license: String, val url: String)

const val SOURCE_URL = "https://github.com/Yarmiplay/YarmiplayTV"

const val LICENSE_SUMMARY =
    "YarmiplayTV's own code is open source under the Apache License 2.0. The Android app and the Windows " +
        "installer include mpv and FFmpeg built under the GNU GPL version 3, so those packages as a whole are " +
        "distributed under the GPL 3.0. The complete source code is at $SOURCE_URL."

/** Everything the Android app and desktop packages ship, for the licenses screen. */
val openSourceComponents = listOf(
    OpenSourceComponent("mpv", "GPL 2.0 or later", "https://mpv.io"),
    OpenSourceComponent("FFmpeg", "GPL 3.0 or later (as built here)", "https://ffmpeg.org"),
    OpenSourceComponent("libmpv-android", "MIT", "https://github.com/jarnedemeulemeester/libmpv-android"),
    OpenSourceComponent("libass", "ISC", "https://github.com/libass/libass"),
    OpenSourceComponent("libplacebo", "LGPL 2.1 or later", "https://code.videolan.org/videolan/libplacebo"),
    OpenSourceComponent("dav1d", "BSD 2-Clause", "https://code.videolan.org/videolan/dav1d"),
    OpenSourceComponent("FreeType", "FreeType License", "https://freetype.org"),
    OpenSourceComponent("HarfBuzz", "MIT", "https://harfbuzz.github.io"),
    OpenSourceComponent("FriBidi", "LGPL 2.1 or later", "https://github.com/fribidi/fribidi"),
    OpenSourceComponent("Fontconfig", "MIT", "https://www.freedesktop.org/wiki/Software/fontconfig"),
    OpenSourceComponent("libxml2", "MIT", "https://gitlab.gnome.org/GNOME/libxml2"),
    OpenSourceComponent("libunibreak", "zlib", "https://github.com/adah1972/libunibreak"),
    OpenSourceComponent("Mbed TLS", "Apache 2.0", "https://www.trustedfirmware.org/projects/mbed-tls"),
    OpenSourceComponent("Lua", "MIT", "https://www.lua.org"),
    OpenSourceComponent("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache 2.0", "https://kotlinlang.org"),
    OpenSourceComponent("Jetpack Compose, AndroidX", "Apache 2.0", "https://developer.android.com/jetpack"),
    OpenSourceComponent("Compose Multiplatform", "Apache 2.0", "https://www.jetbrains.com/compose-multiplatform"),
    OpenSourceComponent("OkHttp, Okio", "Apache 2.0", "https://square.github.io/okhttp"),
    OpenSourceComponent("Coil", "Apache 2.0", "https://coil-kt.github.io/coil"),
    OpenSourceComponent("JNA (desktop)", "Apache 2.0 or LGPL 2.1", "https://github.com/java-native-access/jna"),
    OpenSourceComponent("LWJGL, GLFW (desktop)", "BSD 3-Clause, zlib", "https://www.lwjgl.org"),
)
