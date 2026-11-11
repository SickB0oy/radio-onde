package app.onde.radio.data

import app.onde.radio.player.StreamRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

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

    private suspend fun stations(path: String, params: Map<String, String>): List<Station> {
        var last: Exception? = null
        for (host in hosts()) {
            try {
                val url = HttpUrl.Builder().scheme("https").host(host).addPathSegments(path).apply {
                    params.forEach { (k, v) -> addQueryParameter(k, v) }
                    addQueryParameter("hidebroken", "true")
                }.build()
                return json.decodeFromString<List<Station>>(get(url))
                    .filter { it.streamUrl.startsWith("http") }
                    .distinctBy { it.name.lowercase().trim() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("Aucun serveur Radio-Browser disponible")
    }

    /** Stations dont l'écoute progresse le plus dans un pays. */
    suspend fun trending(countryCode: String, limit: Int = 10) = stations(
        "json/stations/search",
        mapOf("countrycode" to countryCode, "order" to "clicktrend", "reverse" to "true", "limit" to "$limit"),
    )

    /** Les plus appréciées d'un pays (utile quand on n'a pas encore d'historique). */
    suspend fun topVoted(countryCode: String, limit: Int = 12) = stations(
        "json/stations/search",
        mapOf("countrycode" to countryCode, "order" to "votes", "reverse" to "true", "limit" to "$limit"),
    )

    suspend fun byTag(tag: String, limit: Int = 15, countryCode: String? = null) = stations(
        "json/stations/search",
        buildMap {
            put("tag", tag)
            put("order", "votes")
            put("reverse", "true")
            put("limit", "$limit")
            if (countryCode != null) put("countrycode", countryCode)
        },
    )

    suspend fun search(query: String, limit: Int = 40) = stations(
        "json/stations/search",
        mapOf("name" to query, "order" to "clickcount", "reverse" to "true", "limit" to "$limit"),
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
