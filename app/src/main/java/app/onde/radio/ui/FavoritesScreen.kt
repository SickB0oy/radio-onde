package app.onde.radio.ui

import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onde.radio.data.LibraryData
import app.onde.radio.data.Station
import app.onde.radio.ui.theme.Onde

@Composable
fun FavoritesScreen(
    library: LibraryData,
    onPlay: (Station) -> Unit,
    onAddCustom: (name: String, url: String) -> Unit,
) {
    var showAdd by rememberSaveable { mutableStateOf(false) }

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
        item(key = "add-custom") {
            Row(
                Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Onde.Line, RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button) { showAdd = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = Onde.Accent, modifier = Modifier.size(22.dp))
                Column {
                    Text("Ajouter une station par son adresse", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Pour une radio absente de l'annuaire", fontSize = 12.sp, color = Onde.Muted)
                }
            }
        }
    }

    if (showAdd) {
        AddStationDialog(
            onAdd = { name, url ->
                showAdd = false
                onAddCustom(name, url)
            },
            onDismiss = { showAdd = false },
        )
    }
}

@Composable
private fun AddStationDialog(onAdd: (name: String, url: String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Onde.Accent,
        unfocusedBorderColor = Onde.Line,
        cursorColor = Onde.Accent,
        focusedLabelColor = Onde.Accent,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Onde.Surface2,
        title = { Text("Ajouter une station") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Colle l'adresse du flux audio de la radio (elle commence par http:// ou https://). " +
                        "On la trouve souvent sur le site de la station, sous « écouter en direct ».",
                    fontSize = 13.sp,
                    color = Onde.TextSoft,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("Adresse du flux") },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    isError = error != null,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom (facultatif)") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, fontSize = 13.sp, color = Onde.Red) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cleanUrl = url.trim()
                val host = runCatching { Uri.parse(cleanUrl).host }.getOrNull()
                val valid = (cleanUrl.startsWith("http://", true) || cleanUrl.startsWith("https://", true)) &&
                    !host.isNullOrBlank() && !cleanUrl.contains(' ')
                if (!valid) {
                    error = "Cette adresse n'est pas valide. Elle doit commencer par http:// ou https://."
                } else {
                    onAdd(name.trim().ifBlank { host!!.removePrefix("www.") }, cleanUrl)
                }
            }) { Text("Ajouter et écouter", color = Onde.Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler", color = Onde.TextSoft) }
        },
    )
}
