package app.onde.radio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onde.radio.data.Station
import app.onde.radio.ui.theme.Onde

@Composable
fun SearchScreen(vm: MainViewModel, onPlay: (Station) -> Unit, onClose: () -> Unit) {
    val query by vm.query.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text("Nom d'une station, d'une ville…") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Onde.Amber,
                        unfocusedBorderColor = Onde.Line,
                        cursorColor = Onde.Amber,
                        focusedContainerColor = Onde.Surface,
                        unfocusedContainerColor = Onde.Surface,
                    ),
                )
                CircleIconButton(Icons.Rounded.Close, "Fermer la recherche", onClose)
            }
        }

        when {
            search.loading -> item { Loading() }
            search.error != null -> item { EmptyMessage("Oups", search.error!!) }
            query.trim().length >= 2 && search.results.isEmpty() && search.query == query ->
                item { EmptyMessage("Aucun résultat", "Essaie une autre orthographe ou un nom plus court.") }
            query.isBlank() -> item {
                EmptyMessage("Plus de 50 000 radios", "Les stations viennent de l'annuaire libre Radio-Browser.")
            }
        }

        items(search.results, key = { "search-" + it.uuid }) { station ->
            StationRow(station, onClick = { onPlay(station) })
        }
    }
}
