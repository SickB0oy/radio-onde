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

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}
