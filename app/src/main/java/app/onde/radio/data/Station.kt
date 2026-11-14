package app.onde.radio.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Une station telle que renvoyée par l'API Radio-Browser. */
@Serializable
data class Station(
    @SerialName("stationuuid") val uuid: String,
    val name: String = "",
    val url: String = "",
    @SerialName("url_resolved") val urlResolved: String = "",
    val favicon: String = "",
    val tags: String = "",
    @SerialName("countrycode") val countryCode: String = "",
    val country: String = "",
    val state: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val hls: Int = 0,
    val votes: Int = 0,
    @SerialName("clickcount") val clickCount: Int = 0,
    @SerialName("clicktrend") val clickTrend: Int = 0,
    @SerialName("geo_lat") val geoLat: Double? = null,
    @SerialName("geo_long") val geoLong: Double? = null,
) {
    /** Position utilisable sur le globe (certaines fiches ont 0,0 ou des valeurs fantaisistes). */
    val hasGeo: Boolean
        get() {
            val lat = geoLat ?: return false
            val lon = geoLong ?: return false
            return lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
        }

    val streamUrl: String get() = urlResolved.ifBlank { url }

    /** Station ajoutée à la main par son adresse (absente de l'annuaire Radio-Browser). */
    val isCustom: Boolean get() = uuid.startsWith(CUSTOM_PREFIX)

    val tagList: List<String>
        get() = tags.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    val isHls: Boolean
        get() = hls == 1 || streamUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    val displayName: String get() = name.trim().ifBlank { "Station sans nom" }

    /** Ligne secondaire : deux premiers genres et la ville/le pays. */
    val subtitle: String
        get() {
            if (isCustom) return "Ajoutée par son adresse"
            val genres = tagList.take(2).joinToString(" · ") { it.replaceFirstChar(Char::uppercase) }
            val place = PlaceNames.fr(state).ifBlank { countryNameFr }
            return listOf(genres, place).filter { it.isNotBlank() }.joinToString(" · ")
        }

    /** « Ville ou région, Pays » quand l'annuaire les connaît. */
    val placeLabel: String
        get() = listOf(PlaceNames.fr(state), countryNameFr).filter { it.isNotBlank() }.distinct().joinToString(", ")

    /** Nom du pays en français à partir de son code (« Maroc » plutôt que « Morocco »). */
    val countryNameFr: String
        get() = countryCode.takeIf { it.length == 2 }
            ?.let { java.util.Locale("", it).getDisplayCountry(java.util.Locale.FRENCH) }
            ?.takeIf { it.isNotBlank() && it != countryCode }
            ?: country.trim()

    val qualityLabel: String
        get() = listOf(codec.uppercase(), if (bitrate > 0) "$bitrate kbit/s" else "")
            .filter { it.isNotBlank() }.joinToString(" · ")

    companion object {
        const val CUSTOM_PREFIX = "custom:"

        /** Crée une station à partir d'un nom et de l'adresse de son flux. */
        fun custom(name: String, url: String): Station =
            Station(uuid = CUSTOM_PREFIX + Integer.toHexString(url.hashCode()), name = name, url = url, urlResolved = url)
    }
}
