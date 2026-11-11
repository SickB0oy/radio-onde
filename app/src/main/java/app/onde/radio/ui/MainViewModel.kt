package app.onde.radio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.onde.radio.OndeApp
import app.onde.radio.data.Recommender
import app.onde.radio.data.Station
import app.onde.radio.data.Suggestion
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

enum class Mood(val label: String, val tag: String?) {
    ForYou("Pour toi", null),
    Calm("Calme", "chillout"),
    Energy("Énergie", "dance"),
    News("Infos", "news"),
}

data class HomeState(
    val loading: Boolean = true,
    val error: String? = null,
    val country: String = "FR",
    val recoTitle: String = "Pour toi",
    val reco: List<Suggestion> = emptyList(),
    val trends: List<Station> = emptyList(),
    val mood: Mood = Mood.ForYou,
    val moodLoading: Boolean = false,
    val moodStations: List<Station> = emptyList(),
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
            if (_home.value.mood != Mood.ForYou) selectMood(_home.value.mood)
        }
    }

    fun selectMood(mood: Mood) {
        _home.update { it.copy(mood = mood) }
        val tag = mood.tag ?: return
        val country = currentCountry()
        viewModelScope.launch {
            _home.update { it.copy(moodLoading = true) }
            val result = runCatching {
                onde.api.byTag(tag, 15, country).ifEmpty { onde.api.byTag(tag, 15) }
            }
            _home.update { it.copy(moodLoading = false, moodStations = result.getOrDefault(emptyList())) }
        }
    }

    fun setCountry(code: String) {
        library.setCountry(code)
        refreshHome()
    }

    fun play(station: Station) = engine.play(station)
}
