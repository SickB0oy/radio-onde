package app.onde.radio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onde.radio.data.Genres
import app.onde.radio.data.RadioBrowserApi
import app.onde.radio.data.Station
import app.onde.radio.data.Suggestion
import app.onde.radio.ui.theme.Onde
import java.util.Calendar
import java.util.Locale

private val COUNTRIES = listOf(
    RadioBrowserApi.WORLD, "FR", "BE", "CH", "CA", "LU", "MC", "MA", "DZ", "TN", "SN", "CI",
    "GB", "US", "DE", "ES", "IT", "PT", "NL", "BR", "JP",
)

fun countryName(code: String): String =
    if (code == RadioBrowserApi.WORLD) "Monde entier"
    else Locale("", code).getDisplayCountry(Locale.FRENCH).ifBlank { code }

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onPlay: (Station) -> Unit,
    onSettings: () -> Unit,
    onZapping: () -> Unit,
) {
    val home by vm.home.collectAsStateWithLifecycle()
    var showCountries by remember { mutableStateOf(false) }
    val tabs = remember {
        listOf(Genres.FOR_YOU to "Pour toi") + Genres.ALL.map { it.key to it.label } + (Genres.OTHERS to "Autres genres")
    }
    val tabsState = rememberLazyListState()
    // Garde l'onglet choisi visible.
    LaunchedEffect(home.selectedGenre) {
        val i = tabs.indexOfFirst { it.first == home.selectedGenre }
        if (i >= 0) tabsState.animateScrollToItem(maxOf(0, i - 1))
    }
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..17 -> "Bonjour"
            else -> "Bonsoir"
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(greeting, fontSize = 13.sp, color = Onde.Muted)
                    Text("Radio Onde", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircleIconButton(Icons.Rounded.Shuffle, "Zapping", onZapping, tint = Onde.Accent)
                    CircleIconButton(Icons.Rounded.Settings, "Réglages et à propos", onSettings)
                }
            }
        }

        // Onglets : Pour toi, chaque style musical, puis tous les autres genres de l'annuaire.
        item {
            LazyRow(
                state = tabsState,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 8.dp),
            ) {
                items(tabs, key = { it.first }) { (key, label) ->
                    Pill(label, selected = key == home.selectedGenre) { vm.selectGenre(key) }
                }
            }
        }

        when (home.selectedGenre) {
            Genres.FOR_YOU -> forYou(home, vm, onPlay) { showCountries = true }
            Genres.OTHERS -> otherGenres(home, vm, onPlay) { showCountries = true }
            else -> genreStations(home.selectedGenre, home, vm, onPlay) { showCountries = true }
        }
    }

    if (showCountries) {
        AlertDialog(
            onDismissRequest = { showCountries = false },
            containerColor = Onde.Surface2,
            title = { Text("Choisir un pays") },
            text = {
                LazyColumn(Modifier.height(360.dp)) {
                    items(COUNTRIES) { code ->
                        Text(
                            countryName(code),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showCountries = false
                                    vm.setCountry(code)
                                }
                                .padding(vertical = 14.dp),
                            color = if (code == home.country) Onde.Accent else Onde.Text,
                            fontSize = 15.sp,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCountries = false }) { Text("Fermer", color = Onde.Accent) }
            },
        )
    }
}

private fun LazyListScope.forYou(
    home: HomeState,
    vm: MainViewModel,
    onPlay: (Station) -> Unit,
    onChangeCountry: () -> Unit,
) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(home.recoTitle)
            when {
                home.loading && home.reco.isEmpty() -> Loading()
                home.error != null -> ErrorRetry(home.error) { vm.refreshHome() }
                home.reco.isEmpty() -> EmptyMessage("Rien à suggérer pour l'instant", "Écoute quelques stations : les suggestions s'adapteront à tes goûts.")
                else -> SuggestionRow(home.reco, onPlay)
            }
        }
    }

    item {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Tendances : ${countryName(home.country)}", Modifier.weight(1f))
            TextButton(onClick = onChangeCountry) {
                Text("Changer de pays", color = Onde.Accent, fontSize = 13.sp)
            }
        }
    }

    if (home.loading && home.trends.isEmpty()) {
        item { Loading() }
    } else if (home.trends.isEmpty() && home.error == null) {
        item { EmptyMessage("Pas de tendances", "Aucune donnée pour ce pays pour le moment.") }
    }

    itemsIndexed(home.trends, key = { _, s -> "trend-" + s.uuid }) { index, station ->
        StationRow(
            station = station,
            onClick = { onPlay(station) },
            leading = { Rank(index) },
            trailing = {
                if (station.clickTrend > 0) {
                    Text("+${station.clickTrend}", fontSize = 12.sp, color = Onde.Accent, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }
    if (home.trends.isNotEmpty() && !home.trendsEnd) {
        item(key = "more-trends") { LoadMore(home.trends.size) { vm.loadMoreTrends() } }
    }
}

/** Les stations d'un genre : celles du pays choisi en premier, complétées par le reste du monde. */
private fun LazyListScope.genreStations(
    key: String,
    home: HomeState,
    vm: MainViewModel,
    onPlay: (Station) -> Unit,
    onChangeCountry: () -> Unit,
) {
    val genre = Genres.find(key) ?: return
    val state = home.genres[key]
    item(key = "genre-title-$key") {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(genre.label, Modifier.weight(1f))
                TextButton(onClick = onChangeCountry) {
                    Text("Changer de pays", color = Onde.Accent, fontSize = 13.sp)
                }
            }
            Text(
                if (home.country == RadioBrowserApi.WORLD) "Les plus appréciées dans le monde entier"
                else "Les plus appréciées : ${countryName(home.country)} d'abord, puis le monde",
                fontSize = 12.sp,
                color = Onde.Muted,
            )
        }
    }
    when {
        state == null || state.loading -> item { Loading() }
        state.error != null -> item { ErrorRetry(state.error) { vm.loadGenre(key, force = true) } }
        state.stations.isEmpty() -> item { EmptyMessage("Aucune station trouvée", "Essaie un autre genre.") }
    }
    itemsIndexed(state?.stations.orEmpty(), key = { _, s -> "genre-$key-" + s.uuid }) { index, station ->
        StationRow(station = station, onClick = { onPlay(station) }, leading = { Rank(index) })
    }
    if (state != null && state.stations.isNotEmpty() && !state.endReached) {
        item(key = "more-$key") { LoadMore(state.stations.size) { vm.loadMoreGenre(key) } }
    }
}

/** Onglet « Autres genres » : tous les genres de l'annuaire, puis les stations de celui qu'on ouvre. */
private fun LazyListScope.otherGenres(
    home: HomeState,
    vm: MainViewModel,
    onPlay: (Station) -> Unit,
    onChangeCountry: () -> Unit,
) {
    val opened = home.openedTag
    if (opened != null) {
        item(key = "back-to-tags") {
            TextButton(onClick = { vm.openTag(null) }, contentPadding = PaddingValues(0.dp)) {
                Text("← Tous les genres", color = Onde.Accent, fontSize = 14.sp)
            }
        }
        genreStations("tag:$opened", home, vm, onPlay, onChangeCountry)
        return
    }
    item(key = "others-title") {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionTitle("Tous les genres")
            Text("Tous les styles de l'annuaire Radio-Browser, des plus fournis aux plus rares", fontSize = 12.sp, color = Onde.Muted)
        }
    }
    when {
        home.otherTagsLoading -> item { Loading() }
        home.otherTagsError != null -> item { ErrorRetry(home.otherTagsError) { vm.retryOtherTags() } }
    }
    items(home.otherTags, key = { "tag-" + it.name }) { tag ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { vm.openTag(tag.name) }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tag.name.replaceFirstChar(Char::uppercase),
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${tag.stationCount} stations", fontSize = 12.sp, color = Onde.Muted)
        }
    }
}

@Composable
private fun Rank(index: Int) {
    Text("${index + 1}", modifier = Modifier.width(26.dp), fontWeight = FontWeight.Bold, color = Onde.Muted)
}

@Composable
private fun SuggestionRow(items: List<Suggestion>, onPlay: (Station) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.station.uuid }) { s ->
            Column(
                Modifier
                    .width(108.dp)
                    .clickable { onPlay(s.station) },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StationArt(s.station, 108.dp, 16.dp)
                Column {
                    Text(s.station.displayName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.reason, fontSize = 12.sp, color = Onde.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Dernière ligne d'une liste : dès qu'elle apparaît à l'écran, la suite se charge.
 * [loadedCount] relance le chargement si la ligne est toujours visible après une page.
 */
@Composable
fun LoadMore(loadedCount: Int, onLoad: () -> Unit) {
    LaunchedEffect(loadedCount) { onLoad() }
    Loading()
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Onde.Accent, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

@Composable
fun ErrorRetry(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, fontSize = 14.sp, color = Onde.TextSoft)
        TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
            Text("Réessayer", color = Onde.Accent)
        }
    }
}
