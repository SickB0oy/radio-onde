package app.onde.radio.player

import java.text.Normalizer
import java.util.Locale

/**
 * Transforme le texte envoyé par une station en (artiste, titre), ou null si ce n'est pas un morceau :
 * nom de la station, publicité, slogan, adresse web, texte vide…
 */
object TitleFilter {

    /** Textes qui, seuls, ne désignent jamais un morceau (comparés sans accents ni ponctuation). */
    private val JUNK = setOf(
        "pub", "pubs", "publicite", "publicites", "ecran publicitaire", "advert", "advertisement",
        "advertising", "ad", "ads", "ad break", "commercial", "commercials", "commercial break",
        "jingle", "jingles", "spot", "promo", "unknown", "unknown artist", "inconnu", "artiste inconnu",
        "titre inconnu", "na", "n a", "null", "undefined", "none", "no title", "untitled", "sans titre",
        "live", "en direct", "direct", "on air", "now playing", "en cours", "info", "infos", "flash info",
        "journal", "meteo", "news", "music", "musique", "radio", "stream", "streaming", "online",
        "listen live", "ecoutez", "bienvenue", "welcome", "station id", "interlude", "pause",
    )

    /** Préfixes ajoutés par certaines stations avant le titre. */
    private val PREFIXES = listOf(
        "now playing:", "now playing -", "now playing", "en cours :", "en cours:", "actuellement :",
        "actuellement:", "on air:", "a l'antenne :", "à l'antenne :", "titre :", "titre:",
    )

    fun parse(raw: String, stationName: String): Pair<String, String>? {
        // Certaines stations envoient du XML ou du JSON au lieu de « Artiste - Titre » :
        // on va chercher l'artiste et le titre dedans, sinon on n'affiche rien.
        if (looksStructured(raw)) {
            val (artist, title) = extractStructured(raw) ?: return null
            return parsePlain(if (artist.isBlank()) title else "$artist - $title", stationName)
        }
        return parsePlain(raw, stationName)
    }

    private fun parsePlain(raw: String, stationName: String): Pair<String, String>? {
        var text = raw.trim().trim('"', '\'', ' ', '-', '|', '*', '~').trim()
        PREFIXES.firstOrNull { text.startsWith(it, ignoreCase = true) }?.let {
            text = text.substring(it.length).trim()
        }
        if (text.length < 3) return null
        if (text.contains("http://", true) || text.contains("https://", true) || text.contains("www.", true)) return null

        val norm = normalize(text)
        if (norm.isEmpty() || norm.all { it.isDigit() || it == ' ' }) return null
        if (norm in JUNK) return null
        val station = normalize(stationName)
        if (station.isNotEmpty() && (norm == station || norm.startsWith("$station ") && !text.contains(" - "))) return null

        val (artist, title) = StreamRecorder.splitTitle(text)
        val normTitle = normalize(title)
        val normArtist = normalize(artist)
        if (normTitle.isEmpty() || normTitle in JUNK) return null
        // « Nom de la station - Slogan » : l'« artiste » est la station elle-même.
        if (station.isNotEmpty() && normArtist == station) return null
        if (normArtist in JUNK && artist.isNotBlank()) return "" to title
        return artist to title
    }

    private val MARKS = Regex("\\p{Mn}+")
    private val NOT_ALNUM = Regex("[^a-z0-9]+")

    private fun looksStructured(raw: String): Boolean {
        val t = raw.trim()
        return t.startsWith("<") || t.contains("<?xml", ignoreCase = true) ||
            (t.startsWith("{") && t.contains("\":")) || Regex("</?[A-Za-z][^>]*>").containsMatchIn(t)
    }

    private val ARTIST_KEYS = "artist|artista|artiste|performer|interpret|interprete|singer|author|creator"
    private val TITLE_KEYS = "title|titre|titulo|título|song|songtitle|track|trackname|songname|chanson"

    /** Premier artiste et premier titre trouvés dans des balises XML ou des champs JSON. */
    private fun extractStructured(raw: String): Pair<String, String>? {
        fun xml(keys: String) = Regex(
            "<(?:$keys)(?:\\s[^>]*)?>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?\\s*</(?:$keys)>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
            .find(raw)?.groupValues?.get(1)
        fun xmlAttr(keys: String) = Regex("\\s(?:$keys)\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1)
        fun json(keys: String) = Regex("\"(?:$keys)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1)?.replace("\\\"", "\"")

        val title = (xml(TITLE_KEYS) ?: json(TITLE_KEYS) ?: xmlAttr(TITLE_KEYS))?.let(::clean)
        val artist = (xml(ARTIST_KEYS) ?: json(ARTIST_KEYS) ?: xmlAttr(ARTIST_KEYS))?.let(::clean).orEmpty()
        if (title.isNullOrBlank() || looksStructured(title)) return null
        return artist to title
    }

    /** Retire les balises restantes et décode les entités XML courantes. */
    private fun clean(s: String): String =
        s.replace(Regex("<[^>]*>"), " ")
            .replace("&amp;", "&").replace("&apos;", "'").replace("&#39;", "'").replace("&quot;", "\"")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(MARKS, "")
            .replace(NOT_ALNUM, " ")
            .trim()
}
