package com.yarmiplaytv.ui.shared

/** A page Settings > About links to: opened in the browser on phones and desktop, shown as a QR code on TV. */
data class AboutLink(val title: String, val subtitle: String, val url: String, val tag: String)

const val SITE_URL = "https://tv.yarmiplay.com/"
const val MANUAL_URL = "https://tv.yarmiplay.com/manual/"
const val CHANGELOG_URL = "https://tv.yarmiplay.com/changelog/"
const val ISSUES_URL = "$SOURCE_URL/issues"
const val SERVER_PROJECT_URL = "https://github.com/Yarmiplay/YarmiplayServerTV#readme"

val aboutLinks = listOf(
    AboutLink("User manual", "How everything works, step by step", MANUAL_URL, "about_manual"),
    AboutLink("All patch notes", "What changed in every version", CHANGELOG_URL, "about_changelog"),
    AboutLink("Website and downloads", "tv.yarmiplay.com: every device's download", SITE_URL, "about_site"),
    AboutLink("Privacy policy", "The app collects no data", PRIVACY_POLICY_URL, "about_privacy"),
    AboutLink("Community rules", "What's not allowed in rooms, and how to block names or hide chat", ROOM_RULES_URL, "about_rules"),
    AboutLink("Source code", "YarmiplayTV on GitHub", SOURCE_URL, "about_source"),
    AboutLink("Report a problem", "Open an issue on GitHub", ISSUES_URL, "about_issues"),
    AboutLink("YarmiplayServerTV", "The Syncplay server with managed rooms, file sharing and shared media servers", SERVER_PROJECT_URL, "about_server"),
)

/** "tv.yarmiplay.com/manual/": a link as people would type it, under a QR code. */
fun shortUrl(url: String): String = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('#')
