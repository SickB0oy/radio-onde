package app.onde.radio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.onde.radio.OndeApp
import app.onde.radio.alarm.AlarmScheduler
import app.onde.radio.data.AlarmSettings
import app.onde.radio.data.Genres
import app.onde.radio.data.RadioBrowserApi
import app.onde.radio.data.Recommender
import app.onde.radio.data.Station
import app.onde.radio.data.Suggestion
import app.onde.radio.data.Tag
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import java.util.Locale

data class GenreState(
    val loading: Boolean = false,
    val stations: List<Station> = emptyList(),
    val error: String? = null,
    val loadingMore: Boolean = false,
    /** Plus rien à charger pour ce genre. */
    val endReached: Boolean = false,
)

data class HomeState(
    val loading: Boolean = true,
    val error: String? = null,
    val country: String = "FR",
    val recoTitle: String = "Pour toi",
    val reco: List<Suggestion> = emptyList(),
    val trends: List<Station> = emptyList(),
    val trendsLoadingMore: Boolean = false,
    val trendsEnd: Boolean = false,
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
    /** Nombre de pays en cours de chargement (stations supplémentaires quand on zoome). */
    val loadingMore: Int = 0,
)

data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<Station> = emptyList(),
    val error: String? = null,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
)

private const val PAGE = 40

/**
 * Parcourt un genre page par page : d'abord les stations du pays choisi, puis, quand il n'y en
 * a plus, celles du reste du monde.
 */
private class GenrePager(private val tags: List<String>, countryCode: String) {
    private val country = RadioBrowserApi.countryFilter(countryCode)
    private var localOffset = 0
    private var worldOffset = 0
    private var localDone = country == null
    var done = false
        private set

    suspend fun next(api: RadioBrowserApi): List<Station> {
        val out = ArrayList<Station>()
        if (!localDone) {
            val page = api.genrePage(tags, country, PAGE, localOffset)
            localOffset += PAGE
            out += page
            if (page.size < PAGE / 4) localDone = true
        }
        if (localDone && out.size < 15) {
            val page = api.genrePage(tags, null, PAGE, worldOffset)
            worldOffset += PAGE
            out += page
            if (page.isEmpty()) done = true
        }
        return out
    }
}

private fun List<Station>.plusNew(more: List<Station>): List<Station> {
    val ids = mapTo(HashSet()) { it.uuid }
    val names = mapTo(HashSet()) { it.name.trim().lowercase() }
    return this + more.filter { ids.add(it.uuid) && names.add(it.name.trim().lowercase()) }
}

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
                searchOffset = PAGE
                _search.value = runCatching { onde.api.search(text) }.fold(
                    onSuccess = { SearchState(query = q, results = it, endReached = it.isEmpty()) },
                    onFailure = { SearchState(query = q, error = "Recherche impossible. Vérifie ta connexion.") },
                )
            }
        }
    }

    private var searchOffset = 0

    /** Suite des résultats de recherche (appelée quand on arrive en bas de la liste). */
    fun loadMoreSearch() {
        val s = _search.value
        if (s.loading || s.loadingMore || s.endReached || s.results.isEmpty()) return
        val q = s.query
        _search.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val page = runCatching { onde.api.search(q.trim(), PAGE, searchOffset) }.getOrNull()
            if (_search.value.query != q) return@launch
            searchOffset += PAGE
            _search.update {
                val merged = it.results.plusNew(page.orEmpty())
                it.copy(results = merged, loadingMore = false, endReached = page.isNullOrEmpty())
            }
        }
    }

    fun currentCountry(): String =
        library.data.value.countryCode
            ?: Locale.getDefault().country.takeIf { it.length == 2 }
            ?: "FR"

    fun refreshHome() {
        val country = currentCountry()
        _home.update { it.copy(loading = true, error = null, country = country, trendsEnd = false) }
        trendsOffset = 10
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

    private var trendsOffset = 10

    /** Suite des tendances, tant que l'écoute des stations progresse vraiment. */
    fun loadMoreTrends() {
        val h = _home.value
        if (h.loading || h.trendsLoadingMore || h.trendsEnd || h.trends.isEmpty()) return
        val country = h.country
        _home.update { it.copy(trendsLoadingMore = true) }
        viewModelScope.launch {
            val page = runCatching { onde.api.trending(country, PAGE, trendsOffset) }.getOrNull()
            if (_home.value.country != country) return@launch
            trendsOffset += PAGE
            val rising = page.orEmpty().filter { it.clickTrend > 0 }
            _home.update {
                it.copy(
                    trends = it.trends.plusNew(rising),
                    trendsLoadingMore = false,
                    trendsEnd = page.isNullOrEmpty() || rising.size < page.size,
                )
            }
        }
    }

    private val pagers = HashMap<String, GenrePager>()

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
        val pager = GenrePager(genre.tags, currentCountry())
        pagers[key] = pager
        _home.update { it.copy(genres = it.genres + (key to GenreState(loading = true))) }
        viewModelScope.launch {
            val result = runCatching { pager.next(onde.api) }
            if (pagers[key] !== pager) return@launch
            val state = result.fold(
                onSuccess = { GenreState(stations = emptyList<Station>().plusNew(it), endReached = pager.done || it.isEmpty()) },
                onFailure = { GenreState(error = "Impossible de charger ce genre. Vérifie ta connexion.") },
            )
            _home.update { it.copy(genres = it.genres + (key to state)) }
        }
    }

    /** Suite d'un genre (appelée quand on arrive en bas de la liste). */
    fun loadMoreGenre(key: String) {
        val state = _home.value.genres[key] ?: return
        val pager = pagers[key] ?: return
        if (state.loading || state.loadingMore || state.endReached) return
        _home.update { it.copy(genres = it.genres + (key to state.copy(loadingMore = true))) }
        viewModelScope.launch {
            var stations = state.stations
            var failed = false
            // Une page peut ne contenir que des doublons : on en demande jusqu'à trois d'affilée.
            for (attempt in 1..3) {
                val page = runCatching { pager.next(onde.api) }.getOrElse { failed = true; emptyList() }
                stations = stations.plusNew(page)
                if (failed || pager.done || stations.size > state.stations.size) break
            }
            if (pagers[key] !== pager) return@launch
            val end = failed || pager.done || stations.size == state.stations.size
            _home.update { it.copy(genres = it.genres + (key to GenreState(stations = stations, endReached = end))) }
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
        pagers.clear()
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
        globeCountries.clear()
        _globe.value = GlobeState(loading = true)
        viewModelScope.launch {
            _globe.value = runCatching { onde.api.geoStations() }.fold(
                onSuccess = { GlobeState(stations = it) },
                onFailure = { GlobeState(error = "Impossible de charger les radios du monde. Vérifie ta connexion.") },
            )
        }
    }

    private val globeCountries = mutableSetOf<String>()

    /**
     * Quand on zoome sur un pays, on charge toutes ses stations géolocalisées (une seule fois par
     * pays) et on les ajoute au globe, toujours classées de la plus écoutée à la moins écoutée.
     */
    fun loadGlobeCountry(code: String) {
        val cc = code.uppercase()
        if (_globe.value.stations.isEmpty() || !globeCountries.add(cc)) return
        _globe.update { it.copy(loadingMore = it.loadingMore + 1) }
        viewModelScope.launch {
            val extra = runCatching { onde.api.geoStationsByCountry(cc) }.getOrNull()
            if (extra == null) globeCountries.remove(cc) // on réessaiera au prochain passage
            val current = _globe.value.stations
            // Fusion et tri en arrière-plan : avec des milliers de stations, ça ferait saccader le globe.
            val merged = withContext(Dispatchers.Default) {
                val knownIds = current.mapTo(HashSet()) { it.uuid }
                val knownNames = current.mapTo(HashSet()) { it.countryCode.uppercase() + "|" + it.name.trim().lowercase() }
                val fresh = extra.orEmpty().filter {
                    it.uuid !in knownIds && (it.countryCode.uppercase() + "|" + it.name.trim().lowercase()) !in knownNames
                }
                if (fresh.isEmpty()) null else (current + fresh).sortedByDescending { it.clickCount }
            }
            _globe.update { g ->
                g.copy(
                    // Si un autre pays a été ajouté entre-temps, on réessaiera celui-ci au prochain passage.
                    stations = if (merged != null && g.stations === current) merged else g.stations,
                    loadingMore = (g.loadingMore - 1).coerceAtLeast(0),
                )
            }
            if (merged != null && _globe.value.stations !== merged) globeCountries.remove(cc)
        }
    }
}
