plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// settings.gradle.kts leaves out the Android modules with -PdesktopOnly; this module then has no Android target.
val android = !providers.gradleProperty("desktopOnly").isPresent
if (android) apply(plugin = libs.plugins.android.library.get().pluginId)

kotlin {
    jvmToolchain(17)

    if (android) androidTarget()
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(project(":syncplay-protocol"))
            api(project(":media-source"))
            api(project(":player-api"))
            api(libs.kotlinx.coroutines.core)
            api(libs.androidx.datastore.preferences.core)
            implementation(libs.kotlinx.serialization.json)

            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.ui)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.jetbrains.compose.material.icons)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.okhttp)
        }
        if (android) {
            androidMain.dependencies {
                implementation(project(":player-mpv"))
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
            }
        }
        val desktopMain by getting {
            // The logo is one Android vector drawable, read on desktop with loadXmlImageVector.
            resources.srcDir("src/androidMain/res/drawable")
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.okhttp.mockwebserver)
            }
        }
    }
}

if (android) {
    extensions.configure<com.android.build.api.dsl.LibraryExtension> {
        namespace = "com.yarmiplaytv.shared"
        compileSdk = 36

        defaultConfig {
            minSdk = 26
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}

tasks.named<Test>("desktopTest") {
    testLogging {
        events("passed", "skipped", "failed")
    }
}

/**
 * Writes ReleaseNotes.kt from the Linux metainfo's <releases>, the same patch notes the website's /changelog/
 * shows, so every platform has them offline for "What's new".
 */
@CacheableTask
abstract class GenerateReleaseNotes : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val metainfo: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val doc = factory.newDocumentBuilder().parse(metainfo.get().asFile)
        fun org.w3c.dom.Node.elements(): List<org.w3c.dom.Element> =
            (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>()
        fun String.tidy() = trim().replace(Regex("\\s+"), " ")
        fun String.literal() = "\"" + map {
            when (it) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '$' -> "\\$"
                else -> it.toString()
            }
        }.joinToString("") + "\""
        fun parts(version: String) = version.split('.').map { it.toIntOrNull() ?: 0 }
        val newestFirst = Comparator<org.w3c.dom.Element> { a, b ->
            val x = parts(a.getAttribute("version"))
            val y = parts(b.getAttribute("version"))
            (0 until maxOf(x.size, y.size)).map { y.getOrElse(it) { 0 }.compareTo(x.getOrElse(it) { 0 }) }.firstOrNull { it != 0 } ?: 0
        }
        val releases = doc.getElementsByTagName("release").let { list -> (0 until list.length).map { list.item(it) as org.w3c.dom.Element } }
            .sortedWith(newestFirst)
        val entries = releases.map { release ->
            val paragraphs = release.elements().filter { it.tagName == "description" }.flatMap { it.elements() }.flatMap { block ->
                when (block.tagName) {
                    "ul", "ol" -> block.elements().filter { it.tagName == "li" }.map { "• " + it.textContent.tidy() }
                    else -> listOf(block.textContent.tidy())
                }
            }.filter { it.isNotEmpty() }
            "        ReleaseNote(${release.getAttribute("version").literal()}, ${release.getAttribute("date").literal()}, listOf(" +
                paragraphs.joinToString(", ") { it.literal() } + ")),"
        }
        val file = outputDir.get().asFile.resolve("com/yarmiplaytv/update/ReleaseNotes.kt")
        file.parentFile.mkdirs()
        file.writeText(
            listOf(
                "// Generated by :shared:generateReleaseNotes from packaging/linux/com.yarmiplay.TV.metainfo.xml. Don't edit.",
                "package com.yarmiplaytv.update",
                "",
                "/** One version's patch notes: its [paragraphs], list items starting with \"• \". [date] is YYYY-MM-DD. */",
                "data class ReleaseNote(val version: String, val date: String, val paragraphs: List<String>)",
                "",
                "/** Every version's patch notes, the same as the website's /changelog/. */",
                "object ReleaseNotes {",
                "    /** Newest first. */",
                "    val all: List<ReleaseNote> = listOf(",
                *entries.toTypedArray(),
                "    )",
                "",
                "    /** [version]'s notes; \"1.8\" and \"1.8.0\" are the same version. */",
                "    fun of(version: String): ReleaseNote? {",
                "        val wanted = AppVersions.parse(version) ?: return null",
                "        return all.firstOrNull { note ->",
                "            val v = AppVersions.parse(note.version) ?: return@firstOrNull false",
                "            (0 until maxOf(v.size, wanted.size)).all { v.getOrElse(it) { 0 } == wanted.getOrElse(it) { 0 } }",
                "        }",
                "    }",
                "}",
                "",
            ).joinToString("\n"),
        )
    }
}

val generateReleaseNotes = tasks.register<GenerateReleaseNotes>("generateReleaseNotes") {
    metainfo.set(rootProject.layout.projectDirectory.file("packaging/linux/com.yarmiplay.TV.metainfo.xml"))
    outputDir.set(layout.buildDirectory.dir("generated/releaseNotes/commonMain/kotlin"))
}
kotlin.sourceSets.commonMain { kotlin.srcDir(generateReleaseNotes.map { it.outputDir }) }
