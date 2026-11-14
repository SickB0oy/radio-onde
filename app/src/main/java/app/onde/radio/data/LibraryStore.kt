package app.onde.radio.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HistoryEntry(
    val id: Long,
    val title: String,
    val artist: String,
    val stationUuid: String,
    val stationName: String,
    val timeMs: Long,
    val saved: Boolean = false,
)

@Serializable
data class LibraryData(
    val favorites: List<Station> = emptyList(),
    val recent: List<Station> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    /** Minutes d'écoute par genre : c'est ce qui nourrit les suggestions. */
    val tagScores: Map<String, Double> = emptyMap(),
    val countryCode: String? = null,
    /** Dernière mise à jour des fiches des favoris et stations récentes. */
    val stationsRefreshedMs: Long = 0L,
    /** Volume égalisé entre les stations (activé par défaut). */
    val volumeLeveling: Boolean = true,
    /** Relancer la dernière station dès l'ouverture de l'appli (désactivé par défaut). */
    val autoPlayOnLaunch: Boolean = false,
    val alarm: AlarmSettings = AlarmSettings(),
)

/** Réveil radio. [days] utilise les valeurs de java.util.Calendar (DIMANCHE = 1 … SAMEDI = 7). */
@Serializable
data class AlarmSettings(
    val enabled: Boolean = false,
    val hour: Int = 7,
    val minute: Int = 0,
    val days: List<Int> = listOf(2, 3, 4, 5, 6),
    val station: Station? = null,
)

/** Favoris, stations récentes, historique des titres et goûts, enregistrés dans un fichier JSON. */
class LibraryStore(private val file: File, private val scope: CoroutineScope) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val writeLock = Mutex()
    private val _data = MutableStateFlow(load())
    val data: StateFlow<LibraryData> = _data.asStateFlow()

    private fun load(): LibraryData = runCatching {
        if (file.exists()) json.decodeFromString<LibraryData>(file.readText()) else LibraryData()
    }.getOrDefault(LibraryData())

    private fun edit(block: (LibraryData) -> LibraryData) {
        _data.update(block)
        scope.launch(Dispatchers.IO) {
            writeLock.withLock {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.encodeToString(LibraryData.serializer(), _data.value))
                tmp.renameTo(file)
            }
        }
    }

    fun isFavorite(uuid: String) = _data.value.favorites.any { it.uuid == uuid }

    fun toggleFavorite(station: Station) = edit { d ->
        if (d.favorites.any { it.uuid == station.uuid }) d.copy(favorites = d.favorites.filterNot { it.uuid == station.uuid })
        else d.copy(favorites = listOf(station) + d.favorites)
    }

    /** Ajoute aux favoris une station saisie à la main ; renvoie la station (existante ou nouvelle). */
    fun addCustomStation(name: String, url: String): Station {
        val station = Station.custom(name, url)
        edit { d ->
            if (d.favorites.any { it.uuid == station.uuid }) d else d.copy(favorites = listOf(station) + d.favorites)
        }
        return station
    }

    fun onStationPlayed(station: Station) = edit { d ->
        val recent = (listOf(station) + d.recent.filterNot { it.uuid == station.uuid }).take(20)
        d.copy(recent = recent, tagScores = d.tagScores.addTo(station.tagList, 1.0))
    }

    fun addListeningMinutes(station: Station, minutes: Double) = edit { d ->
        d.copy(tagScores = d.tagScores.addTo(station.tagList, minutes))
    }

    fun addHistory(entry: HistoryEntry) = edit { d ->
        d.copy(history = (listOf(entry) + d.history).take(MAX_HISTORY))
    }

    fun toggleSaved(id: Long) = edit { d ->
        d.copy(history = d.history.map { if (it.id == id) it.copy(saved = !it.saved) else it })
    }

    fun setCountry(code: String) = edit { it.copy(countryCode = code) }

    fun setVolumeLeveling(enabled: Boolean) = edit { it.copy(volumeLeveling = enabled) }

    fun setAutoPlayOnLaunch(enabled: Boolean) = edit { it.copy(autoPlayOnLaunch = enabled) }

    fun updateAlarm(transform: (AlarmSettings) -> AlarmSettings) = edit { it.copy(alarm = transform(it.alarm)) }

    /**
     * Met à jour, au plus une fois par jour, les adresses des favoris et des stations récentes
     * (une radio peut changer d'adresse de flux). Une station disparue de l'annuaire est gardée telle quelle.
     */
    suspend fun refreshStationsIfNeeded(api: RadioBrowserApi) {
        val d = _data.value
        if (System.currentTimeMillis() - d.stationsRefreshedMs < REFRESH_INTERVAL_MS) return
        val uuids = (d.favorites + d.recent).filterNot { it.isCustom }.map { it.uuid }
        if (uuids.isEmpty()) return
        val fresh = runCatching { api.byUuids(uuids) }.getOrNull()?.associateBy { it.uuid } ?: return
        edit { cur ->
            cur.copy(
                favorites = cur.favorites.map { fresh[it.uuid] ?: it },
                recent = cur.recent.map { fresh[it.uuid] ?: it },
                stationsRefreshedMs = System.currentTimeMillis(),
            )
        }
    }

    private fun Map<String, Double>.addTo(tags: List<String>, amount: Double): Map<String, Double> {
        if (tags.isEmpty()) return this
        val share = amount / tags.size
        val m = toMutableMap()
        tags.forEach { m[it] = (m[it] ?: 0.0) + share }
        return m
    }

    companion object {
        const val MAX_HISTORY = 1000
        const val REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
