package app.onde.radio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.onde.radio.OndeApp
import app.onde.radio.alarm.AlarmScheduler
import app.onde.radio.data.AlarmSettings
import app.onde.radio.data.Genres
import app.onde.radio.data.Recommender
import app.onde.radio.data.Station
import app.onde.radio.data.Suggestion
import app.onde.radio.data.Tag
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class GenreState(
    val loading: Boolean = false,
    val stations: List<Station> = emptyList(),
    val error: String? = null,
)

data class HomeState(
    val loading: Boolean = true,
    val error: String? = null,
    val country: String = "FR",
    val recoTitle: String = "Pour toi",
    val reco: List<Suggestion> = emptyList(),
    val trends: List<Station> = emptyList(),
    /** Onglet affiché : [Genres.FOR_YOU], [Genres.OTHERS] ou la clé d'un genre. */
    val selectedGenre: String = Genres.FOR_YOU,
    /** Stations déjà chargées, par clé de genre. */
    val genres: Map<String, GenreState> = emptyMap(),
    /** Onglet « Autres genres » : tous les genres de l'annuaire, et celui qu'on a ouvert. */
    val otherTags: List<Tag> = emptyList(),
    val otherTagsLoading: Boolean = false,
    val otherTagsError: String? = null,
    val openedTag: String? = null,
)

data class GlobeState(
    val loading: Boolean = false,
    val stations: List<Station> = emptyList(),
    val error: String? = null,
)

data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<Station> = emptyList(),
    val error: String? = null,
)

@OptIn(FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val onde = app as OndeApp
    val engine = onde.engine
    val library = onde.library
    val player = engine.state
    val libraryData = library.data
    private val recommender = Recommender(onde.api)

    private val _home = MutableStateFlow(HomeState(country = currentCountry()))
    val home: StateFlow<HomeState> = _home.asStateFlow()

    val query = MutableStateFlow("")
    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    init {
        refreshHome()
        viewModelScope.launch {
            query.debounce(400).distinctUntilChanged().collectLatest { q ->
                val text = q.trim()
                if (text.length < 2) {
                    _search.value = SearchState(query = q)
                    return@collectLatest
                }
                _search.value = SearchState(query = q, loading = true)
                _search.value = runCatching { onde.api.search(text) }.fold(
                    onSuccess = { SearchState(query = q, results = it) },
                    onFailure = { SearchState(query = q, error = "Recherche impossible. Vérifie ta connexion.") },
                )
            }
        }
    }

    fun currentCountry(): String =
        library.data.value.countryCode
            ?: Locale.getDefault().country.takeIf { it.length == 2 }
            ?: "FR"

    fun refreshHome() {
        val country = currentCountry()
        _home.update { it.copy(loading = true, error = null, country = country) }
        viewModelScope.launch {
            val trends = async { runCatching { onde.api.trending(country) } }
            val reco = async { runCatching { recommender.forYou(library.data.value, country) } }
            val t = trends.await()
            val r = reco.await()
            _home.update {
                it.copy(
                    loading = false,
                    trends = t.getOrDefault(emptyList()),
                    recoTitle = r.getOrNull()?.title ?: it.recoTitle,
                    reco = r.getOrNull()?.items ?: emptyList(),
                    error = if (t.isFailure && r.isFailure) "Impossible de joindre l'annuaire des radios." else null,
                )
            }
        }
    }

    fun selectGenre(key: String) {
        _home.update { it.copy(selectedGenre = key) }
        when (key) {
            Genres.FOR_YOU -> Unit
            Genres.OTHERS -> {
                val tag = _home.value.openedTag
                if (tag != null) loadGenre("tag:$tag") else loadOtherTags()
            }
            else -> loadGenre(key)
        }
    }

    /** Onglet « Autres genres » : ouvre un genre de l'annuaire, ou revient à la liste (null). */
    fun openTag(tag: String?) {
        _home.update { it.copy(openedTag = tag) }
        if (tag != null) loadGenre("tag:$tag") else loadOtherTags()
    }

    fun loadGenre(key: String, force: Boolean = false) {
        val genre = Genres.find(key) ?: return
        val cached = _home.value.genres[key]
        if (!force && cached != null && (cached.loading || cached.stations.isNotEmpty())) return
        val country = currentCountry()
        _home.update { it.copy(genres = it.genres + (key to GenreState(loading = true))) }
        viewModelScope.launch {
            val result = runCatching { onde.api.byGenre(genre.tags, country) }
            val state = result.fold(
                onSuccess = { GenreState(stations = it) },
                onFailure = { GenreState(error = "Impossible de charger ce genre. Vérifie ta connexion.") },
            )
            _home.update { it.copy(genres = it.genres + (key to state)) }
        }
    }

    private fun loadOtherTags(force: Boolean = false) {
        val h = _home.value
        if (!force && (h.otherTagsLoading || h.otherTags.isNotEmpty())) return
        _home.update { it.copy(otherTagsLoading = true, otherTagsError = null) }
        viewModelScope.launch {
            val result = runCatching {
                onde.api.topTags().filter { t ->
                    val name = t.name.trim().lowercase()
                    name.length > 1 && t.stationCount >= 5 &&
                        name !in Genres.genericTags &&
                        Genres.coveredTags.none { covered -> name == covered }
                }.distinctBy { it.name.trim().lowercase() }
            }
            _home.update {
                it.copy(
                    otherTagsLoading = false,
                    otherTags = result.getOrDefault(emptyList()),
                    otherTagsError = if (result.isFailure) "Impossible de charger la liste des genres." else null,
                )
            }
        }
    }

    fun retryOtherTags() = loadOtherTags(force = true)

    fun setCountry(code: String) {
        library.setCountry(code)
        // Les listes par genre dépendent du pays : on les recharge.
        _home.update { it.copy(genres = emptyMap()) }
        refreshHome()
        val selected = _home.value.selectedGenre
        if (selected != Genres.FOR_YOU) selectGenre(selected)
    }

    fun play(station: Station) = engine.play(station)

    // ---------------------------------------------------------------- Réveil

    fun updateAlarm(transform: (AlarmSettings) -> AlarmSettings) {
        library.updateAlarm(transform)
        AlarmScheduler.schedule(getApplication(), library.data.value.alarm)
    }

    // ---------------------------------------------------------------- Zapping

    /** Les stations de l'onglet affiché, mélangées, pour le Zapping. */
    fun zapStations(): List<Station> {
        val h = _home.value
        val list = when (h.selectedGenre) {
            Genres.FOR_YOU -> h.reco.map { it.station } + h.trends
            Genres.OTHERS -> h.openedTag?.let { h.genres["tag:$it"]?.stations }.orEmpty()
            else -> h.genres[h.selectedGenre]?.stations.orEmpty()
        }
        return list.distinctBy { it.uuid }.shuffled()
    }

    fun zapTitle(): String = when (val key = _home.value.selectedGenre) {
        Genres.FOR_YOU -> "Pour toi"
        Genres.OTHERS -> _home.value.openedTag?.replaceFirstChar(Char::uppercase) ?: "Autres genres"
        else -> Genres.find(key)?.label ?: "Zapping"
    }

    // ---------------------------------------------------------------- Globe

    private val _globe = MutableStateFlow(GlobeState())
    val globe: StateFlow<GlobeState> = _globe.asStateFlow()

    fun loadGlobe(force: Boolean = false) {
        val g = _globe.value
        if (!force && (g.loading || g.stations.isNotEmpty())) return
        _globe.value = GlobeState(loading = true)
        viewModelScope.launch {
            _globe.value = runCatching { onde.api.geoStations() }.fold(
                onSuccess = { GlobeState(stations = it) },
                onFailure = { GlobeState(error = "Impossible de charger les radios du monde. Vérifie ta connexion.") },
            )
        }
    }
}
