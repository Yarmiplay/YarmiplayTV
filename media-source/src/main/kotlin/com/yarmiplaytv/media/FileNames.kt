package com.yarmiplaytv.media

import java.net.URLDecoder

/** Filename helpers shared by media sources (server paths may use either separator). */
object FileNames {
    private val syncplayStrip = Regex("[-~_.\\[\\](): ]")
    private val episodeRegex = Regex("""(?i)(?<![a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,4})(?!\d)""")
    private val altEpisodeRegex = Regex("""(?i)(?<![a-z0-9])(\d{1,2})x(\d{2,4})(?!\d)""")
    private val yearRegex = Regex("""(?<![0-9])(19\d{2}|20\d{2})(?![0-9])""")
    private val bracketGroups = Regex("""\[[^\]]*\]|\{[^\}]*\}""")
    private val qualityTokens = Regex("""(?i)\b(2160p|1080p|720p|480p|4k|uhd|hdr10?|dv|x26[45]|h\.?26[45]|hevc|avc|10bit|8bit|bluray|bdrip|brrip|web[- .]?dl|webrip|web|hdtv|remux|dual[ .-]audio|multi|aac\d?(\.\d)?|ac3|eac3|dts(-hd)?|truehd|atmos|flac|opus)\b""")

    fun baseName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    fun withoutExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && name.length - dot <= 5) name.substring(0, dot) else name
    }

    /** Same normalisation as Syncplay's utils.stripfilename + sameFilename (case-insensitive). */
    fun normalize(name: String): String {
        val decoded = runCatching { URLDecoder.decode(name.replace("+", "%2B"), "UTF-8") }.getOrDefault(name)
        return syncplayStrip.replace(baseName(decoded), "").lowercase()
    }

    data class Parsed(val title: String, val season: Int?, val episode: Int?, val year: Int?)

    /** Best-effort "Show - S01E02 - Name [group].mkv" / "Movie (2020) 1080p.mkv" parser. */
    fun parse(fileName: String): Parsed {
        var stem = withoutExtension(baseName(fileName))
        stem = bracketGroups.replace(stem, " ")
        val ep = episodeRegex.find(stem) ?: altEpisodeRegex.find(stem)
        var season: Int? = null
        var episode: Int? = null
        var titlePart = stem
        if (ep != null) {
            season = ep.groupValues[1].toInt()
            episode = ep.groupValues[2].toInt()
            titlePart = stem.substring(0, ep.range.first)
        }
        val year = yearRegex.findAll(titlePart).lastOrNull()?.takeIf { it.range.first > 0 }
        if (year != null && ep == null) titlePart = titlePart.substring(0, year.range.first)
        titlePart = qualityTokens.replace(titlePart, " ")
        val title = titlePart
            .replace(Regex("[._]"), " ")
            .replace(Regex("[()\\[\\]]"), " ")
            .replace(Regex("\\s+-\\s*$|^\\s*-\\s+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-')
        return Parsed(title, season, episode, year?.value?.toInt())
    }
}
