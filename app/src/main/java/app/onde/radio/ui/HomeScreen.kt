package app.onde.radio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import app.onde.radio.data.Station
import app.onde.radio.data.Suggestion
import app.onde.radio.ui.theme.Onde
import java.util.Calendar
import java.util.Locale

private val COUNTRIES = listOf(
    "FR", "BE", "CH", "CA", "LU", "MC", "MA", "DZ", "TN", "SN", "CI",
    "GB", "US", "DE", "ES", "IT", "PT", "NL", "BR", "JP",
)

fun countryName(code: String): String =
    Locale("", code).getDisplayCountry(Locale.FRENCH).ifBlank { code }

@Composable
fun HomeScreen(vm: MainViewModel, onPlay: (Station) -> Unit, onSearch: () -> Unit) {
    val home by vm.home.collectAsStateWithLifecycle()
    val library by vm.libraryData.collectAsStateWithLifecycle()
    var showCountries by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
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
                    CircleIconButton(Icons.Rounded.Settings, "Réglages", { showSettings = true })
                    CircleIconButton(Icons.Rounded.Search, "Rechercher une station", onSearch)
                }
            }
        }

        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Mood.entries.forEach { m ->
                    Pill(m.label, selected = m == home.mood) { vm.selectMood(m) }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (home.mood == Mood.ForYou) {
                    SectionTitle(home.recoTitle)
                    when {
                        home.loading && home.reco.isEmpty() -> Loading()
                        home.error != null -> ErrorRetry(home.error!!) { vm.refreshHome() }
                        home.reco.isEmpty() -> EmptyMessage("Rien à suggérer pour l'instant", "Écoute quelques stations : les suggestions s'adapteront à tes goûts.")
                        else -> SuggestionRow(home.reco, onPlay)
                    }
                } else {
                    SectionTitle("Ambiance ${home.mood.label.lowercase()}")
                    when {
                        home.moodLoading -> Loading()
                        home.moodStations.isEmpty() -> EmptyMessage("Aucune station trouvée", "Essaie une autre ambiance ou un autre pays.")
                        else -> SuggestionRow(home.moodStations.map { Suggestion(it, it.subtitle) }, onPlay)
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Tendances : ${countryName(home.country)}", Modifier.weight(1f))
                TextButton(onClick = { showCountries = true }) {
                    Text("Changer de pays", color = Onde.Amber, fontSize = 13.sp)
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
                leading = {
                    Text(
                        "${index + 1}",
                        modifier = Modifier.width(22.dp),
                        fontWeight = FontWeight.Bold,
                        color = Onde.Muted,
                    )
                },
                trailing = {
                    if (station.clickTrend > 0) {
                        Text("+${station.clickTrend}", fontSize = 12.sp, color = Onde.Amber, fontWeight = FontWeight.SemiBold)
                    }
                },
            )
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            containerColor = Onde.Surface2,
            title = { Text("Réglages") },
            text = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.engine.setTimeShiftEnabled(!library.timeShiftEnabled) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Retour en arrière (30 min)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Garde les 30 dernières minutes de la station dans un fichier temporaire, " +
                                "effacé quand tu changes de station. Permet de reculer, de réécouter un titre " +
                                "et d'enregistrer une émission.\n\nDésactivé : rien n'est gardé pendant l'écoute. " +
                                "Seule la pause du direct reste possible, en mémoire.",
                            fontSize = 13.sp,
                            color = Onde.TextSoft,
                        )
                    }
                    Switch(
                        checked = library.timeShiftEnabled,
                        onCheckedChange = { vm.engine.setTimeShiftEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Onde.Ink,
                            checkedTrackColor = Onde.Amber,
                            uncheckedThumbColor = Onde.Muted,
                            uncheckedTrackColor = Onde.Surface,
                            uncheckedBorderColor = Onde.Line,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("Fermer", color = Onde.Amber) }
            },
        )
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
                            color = if (code == home.country) Onde.Amber else Onde.Text,
                            fontSize = 15.sp,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCountries = false }) { Text("Fermer", color = Onde.Amber) }
            },
        )
    }
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

@Composable
fun Loading() {
    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Onde.Amber, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

@Composable
fun ErrorRetry(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, fontSize = 14.sp, color = Onde.TextSoft)
        TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
            Text("Réessayer", color = Onde.Amber)
        }
    }
}
