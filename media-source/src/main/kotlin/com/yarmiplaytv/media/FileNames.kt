package com.yarmiplaytv.media

import java.net.URLDecoder

/** Filename helpers shared by media sources (server paths may use either separator). */
object FileNames {
    private val syncplayStrip = Regex("[-~_.\\[\\](): ]")
    private val episodeRegex = Regex("""(?i)(?<![a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,4})(?!\d)""")
    private val altEpisodeRegex = Regex("""(?i)(?<![a-z0-9])(\d{1,2})x(\d{2,4})(?!\d)""")
    private val yearRegex = Regex("""(?<![0-9])(19\d{2}|20\d{2})(?![0-9])""")
    private val bracketGroups = Regex("""\[[^\]]*\]|\{[^\}]*\}""")
    /** Anime release style: "Show - 05", "Show - 05v2 (1080p)", "Show - E05". */
    private val dashEpisode = Regex("""(?i)\s-\s*(?:e|ep|episode\s*)?(\d{1,4})(?:v\d)?(?=\s|$|[(\[._])""")
    private val wordEpisode = Regex("""(?i)(?<![a-z0-9])(?:e|ep|episode\s*)(\d{1,4})(?:v\d)?(?!\d)""")
    private val seasonWords = Regex("""(?i)(?<![a-z0-9])(?:s(\d{1,2})|season\s*(\d{1,2})|(\d{1,2})(?:st|nd|rd|th)\s+season)(?![a-z0-9])""")
    private val bareEpisode = Regex("""(?i)(\d{1,4})(?:v\d)?""")
    private val seasonFolder = Regex("""(?i)^(?:season|series|s)\s*(\d{1,2})$""")
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
        return Parsed(cleanTitle(titlePart), season, episode, year?.value?.toInt())
    }

    private fun cleanTitle(part: String): String = qualityTokens.replace(part, " ")
        .replace(Regex("[._]"), " ")
        .replace(Regex("[()\\[\\]]"), " ")
        .replace(Regex("\\s+-\\s*$|^\\s*-\\s+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-')

    /**
     * Which episode of which show a video file is, for list tracking: [parse]'s `S01E02`/`1x02`, plus anime release
     * names (`[Group] Show - 05 (1080p)`, `Show S2 - 05`, `Show 2nd Season - 05`, `Show E05`, `- 05v2`). [directory]
     * (e.g. `Show/Season 2`) gives the season, and the title when the file name has none. Null for files that don't
     * look like an episode (movies).
     */
    fun parseEpisode(fileName: String, directory: String? = null): Parsed? {
        val base = parse(fileName)
        var title = base.title
        var season = base.season
        var episode = base.episode
        var year: Int? = null
        if (episode == null) {
            var stem = bracketGroups.replace(withoutExtension(baseName(fileName)), " ").replace('_', ' ')
            if (' ' !in stem.trim()) stem = stem.replace('.', ' ')
            // "05.mkv" in a show's folder is just the episode number.
            val marker = dashEpisode.find(stem) ?: wordEpisode.find(stem) ?: bareEpisode.matchEntire(stem.trim()) ?: return null
            episode = marker.groupValues[1].toInt()
            var head = stem.substring(0, marker.range.first)
            seasonWords.findAll(head).lastOrNull()?.let { s ->
                season = s.groupValues.drop(1).first { it.isNotEmpty() }.toInt()
                head = head.removeRange(s.range)
            }
            yearRegex.findAll(head).lastOrNull()?.takeIf { it.range.first > 0 }?.let { y ->
                year = y.value.toInt()
                head = head.removeRange(y.range)
            }
            title = cleanTitle(head)
        }
        val folders = directory.orEmpty().split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() }
        if (season == null) folders.lastOrNull()?.let { seasonFolder.matchEntire(it) }?.let { season = it.groupValues[1].toInt() }
        if (title.isBlank()) title = folders.lastOrNull { seasonFolder.matchEntire(it) == null }?.let(::cleanTitle).orEmpty()
        if (title.isBlank()) return null
        return Parsed(title, season, episode, year)
    }
}
