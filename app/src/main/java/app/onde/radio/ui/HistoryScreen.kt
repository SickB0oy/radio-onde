package app.onde.radio.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onde.radio.data.HistoryEntry
import app.onde.radio.data.LibraryData
import app.onde.radio.ui.theme.Onde
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val FILTERS = listOf("Aujourd'hui", "Cette semaine", "Enregistrés")

@Composable
fun HistoryScreen(
    library: LibraryData,
    onToggleSaved: (Long) -> Unit,
) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.FRANCE) }
    val dayFormat = remember { SimpleDateFormat("EEEE d MMMM", Locale.FRANCE) }

    val entries = remember(library.history, filter) {
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        when (filter) {
            0 -> library.history.filter { it.timeMs >= startOfToday }
            1 -> library.history.filter { it.timeMs >= startOfToday - 6 * DAY_MS }
            else -> library.history.filter { it.saved }
        }
    }
    val groups = remember(entries) { entries.groupBy { dayLabel(it.timeMs, dayFormat) } }

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Historique", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("Tous les titres entendus, sur toutes tes stations", fontSize = 13.sp, color = Onde.Muted)
            }
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FILTERS.forEachIndexed { i, label -> Pill(label, selected = i == filter) { filter = i } }
            }
        }

        if (entries.isEmpty()) {
            item {
                EmptyMessage(
                    if (filter == 2) "Aucun titre enregistré" else "Aucun titre pour l'instant",
                    if (filter == 2) "Appuie sur + à côté d'un titre pour le garder ici."
                    else "Les titres apparaissent ici dès que la station les annonce.",
                )
            }
        }

        groups.forEach { (day, list) ->
            item(key = "day-$day") {
                Text(
                    day.uppercase(Locale.FRANCE),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Onde.Muted,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(list, key = { it.id }) { entry ->
                HistoryRow(
                    entry = entry,
                    time = timeFormat.format(Date(entry.timeMs)),
                    onToggleSaved = { onToggleSaved(entry.id) },
                    onFind = { context.findTrack(entry.artist, entry.title) },
                )
            }
        }

        item {
            Column(
                Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Onde.Surface2)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Un titre t'a plu ?", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Touche-le pour le retrouver sur le web, ou appuie sur + pour le garder dans « Enregistrés ».",
                    fontSize = 12.sp,
                    color = Onde.TextSoft,
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    time: String,
    onToggleSaved: () -> Unit,
    onFind: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Retrouver ce titre", onClick = onFind)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(time, modifier = Modifier.width(40.dp), fontSize = 12.sp, color = Onde.Muted)
            Box(
                Modifier
                    .width(6.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Onde.artColor(entry.stationName))
            )
            Column(Modifier.weight(1f)) {
                Text(entry.title.ifBlank { "Titre inconnu" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(entry.artist, entry.stationName).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 12.sp,
                    color = Onde.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CircleIconButton(
                icon = if (entry.saved) Icons.Rounded.Check else Icons.Rounded.Add,
                contentDescription = if (entry.saved) "Retirer des titres enregistrés" else "Enregistrer le titre",
                onClick = onToggleSaved,
                tint = if (entry.saved) Onde.Amber else Onde.Text,
                iconSize = 18.dp,
            )
        }
        HorizontalDivider(color = Onde.Surface2)
    }
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

private fun dayLabel(timeMs: Long, format: SimpleDateFormat): String {
    val day = Calendar.getInstance().apply { timeInMillis = timeMs }
    val today = Calendar.getInstance()
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (sameDay(day, today)) return "Aujourd'hui"
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (sameDay(day, today)) return "Hier"
    return format.format(Date(timeMs))
}
