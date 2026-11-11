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
) {
    val streamUrl: String get() = urlResolved.ifBlank { url }

    val tagList: List<String>
        get() = tags.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    val isHls: Boolean
        get() = hls == 1 || streamUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    val displayName: String get() = name.trim().ifBlank { "Station sans nom" }

    /** Ligne secondaire : deux premiers genres et la ville/le pays. */
    val subtitle: String
        get() {
            val genres = tagList.take(2).joinToString(" · ") { it.replaceFirstChar(Char::uppercase) }
            val place = state.ifBlank { country }
            return listOf(genres, place).filter { it.isNotBlank() }.joinToString(" · ")
        }

    val qualityLabel: String
        get() = listOf(codec.uppercase(), if (bitrate > 0) "$bitrate kbit/s" else "")
            .filter { it.isNotBlank() }.joinToString(" · ")
}
