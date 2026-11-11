package app.onde.radio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onde.radio.data.LibraryData
import app.onde.radio.data.Station

@Composable
fun FavoritesScreen(library: LibraryData, onPlay: (Station) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text("Favoris", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
        }
        if (library.favorites.isEmpty()) {
            item {
                EmptyMessage("Aucun favori", "Touche le cœur dans le lecteur pour garder une station ici.")
            }
        }
        items(library.favorites, key = { "fav-" + it.uuid }) { station ->
            StationRow(station, onClick = { onPlay(station) })
        }

    }
}
