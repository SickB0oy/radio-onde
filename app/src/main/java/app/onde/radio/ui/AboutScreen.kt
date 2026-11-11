package app.onde.radio.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onde.radio.data.AlarmSettings
import app.onde.radio.data.Station
import java.util.Calendar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onde.radio.R
import app.onde.radio.ui.theme.Onde

private const val AUTHOR = "SickB0oy"
private const val AUTHOR_URL = "https://github.com/SickB0oy"
private const val REPO_URL = "https://github.com/SickB0oy/radio-onde"

@Composable
fun AboutScreen(vm: MainViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val library by vm.libraryData.collectAsStateWithLifecycle()
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Réglages", modifier = Modifier.weight(1f), fontSize = 30.sp, fontWeight = FontWeight.Bold)
            CircleIconButton(Icons.Rounded.Close, "Fermer", onClose)
        }

        // ------------------------------------------------------------ Son
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Son")
            SwitchRow(
                title = "Volume égalisé",
                body = "Toutes les stations sonnent à peu près aussi fort : plus de saut de volume en changeant de radio.",
                checked = library.volumeLeveling,
                onChange = vm.engine::setVolumeLeveling,
            )
        }

        // ------------------------------------------------------------ Réveil
        AlarmSection(vm, library.alarm, library.favorites + library.recent)

        // ------------------------------------------------------------ À propos
        SectionTitle("À propos")

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Onde.Ink)
                    .border(1.dp, Onde.Line, RoundedCornerShape(22.dp)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Radio Onde", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (version.isNotBlank()) Text("Version $version", fontSize = 14.sp, color = Onde.Muted)
                Text(
                    "Créée par $AUTHOR",
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { context.openUrl(AUTHOR_URL) }
                        .padding(vertical = 4.dp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Onde.Accent,
                )
            }
        }

        Text(
            "Une appli radio libre et gratuite : des dizaines de milliers de stations du monde entier, " +
                "la pause du direct, l'historique des titres et Android Auto.",
            fontSize = 15.sp,
            color = Onde.TextSoft,
        )

        AboutCard(
            title = "Ta vie privée",
            body = "Pas de pub, pas de pistage, pas de compte. Tes favoris, ton historique et tes goûts " +
                "restent sur ton téléphone. Seules les recherches de stations partent vers l'annuaire Radio-Browser, " +
                "et le son vient directement de chaque radio.",
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Liens")
            LinkRow("Code source sur GitHub", REPO_URL)
            LinkRow("Mises à jour", "$REPO_URL/releases/latest")
            LinkRow("Signaler un problème ou proposer une idée", "$REPO_URL/issues")
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Remerciements")
            AboutCard(
                title = "Radio-Browser",
                body = "L'annuaire libre et collaboratif qui fournit toutes les stations, leurs genres et leurs tendances.",
                url = "https://www.radio-browser.info",
            )
            AboutCard(
                title = "Bibliothèques libres",
                body = "AndroidX Media3 (ExoPlayer), Jetpack Compose, OkHttp, Coil, Kotlin et kotlinx.",
            )
        }

        Text(
            "Les logos et les flux appartiennent à leurs stations respectives.",
            fontSize = 12.sp,
            color = Onde.Muted,
        )
    }
}

@Composable
private fun AboutCard(title: String, body: String, url: String? = null) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Onde.Surface2)
            .then(if (url != null) Modifier.clickable(role = Role.Button) { context.openUrl(url) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (url != null) Onde.Accent else Onde.Text)
        Text(body, fontSize = 13.sp, color = Onde.TextSoft)
    }
}

@Composable
private fun LinkRow(label: String, url: String) {
    val context = LocalContext.current
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Onde.Line, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button) { context.openUrl(url) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = Onde.Accent,
    )
}

private fun Context.openUrl(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Onde.Surface2)
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(body, fontSize = 13.sp, color = Onde.TextSoft)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Onde.Ink,
                checkedTrackColor = Onde.Accent,
                uncheckedThumbColor = Onde.Muted,
                uncheckedTrackColor = Onde.Surface,
                uncheckedBorderColor = Onde.Line,
            ),
        )
    }
}

/** Jours affichés du lundi au dimanche (valeurs java.util.Calendar). */
private val WEEK = listOf(
    Calendar.MONDAY to "L", Calendar.TUESDAY to "M", Calendar.WEDNESDAY to "M", Calendar.THURSDAY to "J",
    Calendar.FRIDAY to "V", Calendar.SATURDAY to "S", Calendar.SUNDAY to "D",
)
private val WEEK_NAMES = mapOf(
    Calendar.MONDAY to "lundi", Calendar.TUESDAY to "mardi", Calendar.WEDNESDAY to "mercredi",
    Calendar.THURSDAY to "jeudi", Calendar.FRIDAY to "vendredi", Calendar.SATURDAY to "samedi", Calendar.SUNDAY to "dimanche",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmSection(vm: MainViewModel, alarm: AlarmSettings, candidates: List<Station>) {
    var pickTime by remember { mutableStateOf(false) }
    var pickStation by remember { mutableStateOf(false) }
    val choices = remember(candidates) { candidates.distinctBy { it.uuid } }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Réveil radio")
        SwitchRow(
            title = "Me réveiller avec la radio",
            body = when {
                alarm.station == null -> "Choisis d'abord une station ci-dessous."
                alarm.enabled -> "Le son monte doucement pendant une minute."
                else -> "Désactivé."
            },
            checked = alarm.enabled && alarm.station != null,
            onChange = { on -> vm.updateAlarm { it.copy(enabled = on && it.station != null) } },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingButton(
                label = "Heure",
                value = "%02d:%02d".format(alarm.hour, alarm.minute),
                modifier = Modifier.weight(1f),
            ) { pickTime = true }
            SettingButton(
                label = "Station",
                value = alarm.station?.displayName ?: "Choisir",
                modifier = Modifier.weight(2f),
            ) { pickStation = true }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            WEEK.forEach { (day, letter) ->
                val on = day in alarm.days
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (on) Onde.Accent else Onde.Surface2)
                        .clickable(role = Role.Checkbox, onClickLabel = WEEK_NAMES[day]) {
                            vm.updateAlarm { a -> a.copy(days = if (on) a.days - day else a.days + day) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(letter, fontWeight = FontWeight.SemiBold, color = if (on) Onde.Ink else Onde.TextSoft)
                }
            }
        }
    }

    if (pickTime) {
        val timeState = rememberTimePickerState(initialHour = alarm.hour, initialMinute = alarm.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            containerColor = Onde.Surface2,
            title = { Text("Heure du réveil") },
            text = {
                TimePicker(
                    state = timeState,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = Onde.Surface,
                        selectorColor = Onde.Accent,
                        timeSelectorSelectedContainerColor = Onde.Accent,
                        timeSelectorSelectedContentColor = Onde.Ink,
                        timeSelectorUnselectedContainerColor = Onde.Surface,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateAlarm { it.copy(hour = timeState.hour, minute = timeState.minute) }
                    pickTime = false
                }) { Text("OK", color = Onde.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { pickTime = false }) { Text("Annuler", color = Onde.TextSoft) }
            },
        )
    }

    if (pickStation) {
        AlertDialog(
            onDismissRequest = { pickStation = false },
            containerColor = Onde.Surface2,
            title = { Text("Station du réveil") },
            text = {
                if (choices.isEmpty()) {
                    Text("Ajoute d'abord une station à tes favoris (le cœur dans le lecteur).", color = Onde.TextSoft)
                } else {
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(choices, key = { it.uuid }) { station ->
                            StationRow(station, onClick = {
                                vm.updateAlarm { it.copy(station = station) }
                                pickStation = false
                            })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickStation = false }) { Text("Fermer", color = Onde.Accent) }
            },
        )
    }
}

@Composable
private fun SettingButton(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Onde.Surface2)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, fontSize = 12.sp, color = Onde.Muted)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Onde.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
