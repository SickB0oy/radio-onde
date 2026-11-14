package app.onde.radio.data

import app.onde.radio.player.StreamRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Un genre de l'annuaire Radio-Browser. */
@Serializable
data class Tag(
    val name: String = "",
    @SerialName("stationcount") val stationCount: Int = 0,
)

/**
 * Client minimal de l'API publique et gratuite Radio-Browser (https://www.radio-browser.info).
 * Plusieurs miroirs existent : on récupère la liste puis on bascule de l'un à l'autre en cas d'erreur.
 */
class RadioBrowserApi(private val client: OkHttpClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Serializable
    private data class Server(val name: String = "")

    @Volatile private var hosts: List<String>? = null

    companion object {
        /** Code « pays » qui veut dire : toutes les stations du monde. */
        const val WORLD = "ALL"

        fun countryFilter(code: String?): String? = code?.takeIf { it.isNotBlank() && it != WORLD }
    }

    private suspend fun hosts(): List<String> {
        hosts?.let { return it }
        val fallback = listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info")
        val found = runCatching {
            val body = get(HttpUrl.Builder().scheme("https").host("all.api.radio-browser.info")
                .addPathSegments("json/servers").build())
            json.decodeFromString<List<Server>>(body).map { it.name }.filter { it.isNotBlank() }.distinct().shuffled()
        }.getOrNull()
        return (found?.takeIf { it.isNotEmpty() } ?: fallback).also { hosts = it }
    }

    private suspend fun get(url: HttpUrl): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", StreamRecorder.USER_AGENT).build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body!!.string()
        }
    }

    /** Interroge le premier miroir qui répond. */
    private suspend fun fetch(path: String, params: Map<String, String>): String {
        var last: Exception? = null
        for (host in hosts()) {
            try {
                val url = HttpUrl.Builder().scheme("https").host(host).addPathSegments(path).apply {
                    params.forEach { (k, v) -> addQueryParameter(k, v) }
                    addQueryParameter("hidebroken", "true")
                }.build()
                return get(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("Aucun serveur Radio-Browser disponible")
    }

    private suspend fun stations(path: String, params: Map<String, String>): List<Station> =
        json.decodeFromString<List<Station>>(fetch(path, params))
            .filter { it.streamUrl.startsWith("http") }
            .distinctBy { it.name.lowercase().trim() }

    /** Les stations les plus écoutées qui ont une position : de quoi dessiner le globe. */
    suspend fun geoStations(limit: Int = 4000): List<Station> =
        stations(
            "json/stations/search",
            mapOf("has_geo_info" to "true", "order" to "clickcount", "reverse" to "true", "limit" to "$limit"),
        ).filter { it.hasGeo }

    /** Toutes les stations géolocalisées d'un pays (chargées quand on zoome dessus sur le globe). */
    suspend fun geoStationsByCountry(countryCode: String, limit: Int = 20000): List<Station> =
        stations(
            "json/stations/search",
            mapOf(
                "countrycode" to countryCode, "has_geo_info" to "true",
                "order" to "clickcount", "reverse" to "true", "limit" to "$limit",
            ),
        ).filter { it.hasGeo }

    /** Tous les genres de l'annuaire, des plus fournis aux moins fournis. */
    suspend fun topTags(limit: Int = 300): List<Tag> =
        json.decodeFromString<List<Tag>>(
            fetch("json/tags", mapOf("order" to "stationcount", "reverse" to "true", "limit" to "$limit"))
        )

    /**
     * Stations d'un genre (plusieurs étiquettes possibles, ex. « hip » et « rap ») :
     * d'abord celles du pays, complétées par le reste du monde si besoin.
     */
    suspend fun byGenre(tags: List<String>, countryCode: String, limit: Int = 40): List<Station> = coroutineScope {
        val country = countryFilter(countryCode)
        val local = if (country == null) emptyList() else tags.map { tag -> async { runCatching { byTag(tag, limit, countryCode) }.getOrDefault(emptyList()) } }
            .awaitAll().flatten()
        val world = if (country != null && local.size >= 15) emptyList() else tags.map { tag ->
            async { runCatching { byTag(tag, limit) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
        if (local.isEmpty() && world.isEmpty()) throw IOException("Aucune station trouvée ou annuaire injoignable")
        (local.sortedByDescending { it.votes } + world.sortedByDescending { it.votes })
            .distinctBy { it.uuid }
            .distinctBy { it.name.lowercase().trim() }
            .take(limit)
    }

    /** Stations dont l'écoute progresse le plus dans un pays. */
    suspend fun trending(countryCode: String, limit: Int = 10, offset: Int = 0) = stations(
        "json/stations/search",
        buildMap {
            countryFilter(countryCode)?.let { put("countrycode", it) }
            put("order", "clicktrend"); put("reverse", "true"); put("limit", "$limit"); put("offset", "$offset")
        },
    )

    /** Les plus appréciées d'un pays (utile quand on n'a pas encore d'historique). */
    suspend fun topVoted(countryCode: String, limit: Int = 12) = stations(
        "json/stations/search",
        buildMap {
            countryFilter(countryCode)?.let { put("countrycode", it) }
            put("order", "votes"); put("reverse", "true"); put("limit", "$limit")
        },
    )

    suspend fun byTag(tag: String, limit: Int = 15, countryCode: String? = null, offset: Int = 0) = stations(
        "json/stations/search",
        buildMap {
            put("tag", tag)
            put("order", "votes")
            put("reverse", "true")
            put("limit", "$limit")
            put("offset", "$offset")
            countryFilter(countryCode)?.let { put("countrycode", it) }
        },
    )

    /**
     * Une page de stations pour un genre (plusieurs étiquettes possibles), des plus appréciées
     * aux moins appréciées. [countryCode] nul = monde entier. Échoue si l'annuaire est injoignable.
     */
    suspend fun genrePage(tags: List<String>, countryCode: String?, limit: Int, offset: Int): List<Station> = coroutineScope {
        val results = tags.map { tag -> async { runCatching { byTag(tag, limit, countryCode, offset) } } }.awaitAll()
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull() ?: IOException("Annuaire injoignable")
        results.flatMap { it.getOrDefault(emptyList()) }
            .sortedByDescending { it.votes }
            .distinctBy { it.uuid }
            .distinctBy { it.name.lowercase().trim() }
    }

    suspend fun search(query: String, limit: Int = 40, offset: Int = 0) = stations(
        "json/stations/search",
        mapOf("name" to query, "order" to "clickcount", "reverse" to "true", "limit" to "$limit", "offset" to "$offset"),
    )

    /** Fiches à jour de plusieurs stations, par identifiant (pour rafraîchir les favoris). */
    suspend fun byUuids(uuids: List<String>): List<Station> =
        uuids.distinct().chunked(50).flatMap { chunk ->
            stations("json/stations/byuuid", mapOf("uuids" to chunk.joinToString(",")))
        }

    /** Signale une écoute à Radio-Browser (c'est ce qui alimente les tendances). */
    suspend fun countClick(uuid: String) {
        runCatching {
            val host = hosts().first()
            get(HttpUrl.Builder().scheme("https").host(host).addPathSegments("json/url").addPathSegment(uuid).build())
        }
    }
}
