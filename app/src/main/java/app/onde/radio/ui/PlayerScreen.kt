package app.onde.radio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onde.radio.player.PlaybackEngine
import app.onde.radio.player.PlayerUiState
import app.onde.radio.ui.theme.Onde
import coil.compose.AsyncImage

@Composable
fun PlayerScreen(
    state: PlayerUiState,
    isFavorite: Boolean,
    engine: PlaybackEngine,
    onToggleFavorite: () -> Unit,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val station = state.station
    if (station == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            EmptyMessage("Rien en écoute", "Choisis une station dans l'accueil ou la recherche.")
        }
        return
    }
    val context = LocalContext.current
    val current = state.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(Icons.Rounded.KeyboardArrowDown, "Retour", onBack)
            Text(
                "En écoute",
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                color = Onde.Muted,
                fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            CircleIconButton(
                icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
                onClick = onToggleFavorite,
                tint = Onde.Amber,
            )
        }

        // Grande pochette
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(342f / 300f)
                .clip(RoundedCornerShape(28.dp))
                .background(Onde.artColor(station.displayName))
                .padding(22.dp),
        ) {
            val favicon = station.favicon.takeIf { it.startsWith("http") }
            var logoLoaded by remember(favicon) { mutableStateOf(false) }
            if (favicon != null) {
                AsyncImage(
                    model = favicon,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onSuccess = { logoLoaded = true },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(140.dp)
                        .clip(RoundedCornerShape(20.dp)),
                )
            }
            Text(
                listOf(station.displayName, station.qualityLabel).filter { it.isNotBlank() }.joinToString(" · "),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Onde.Ink)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!logoLoaded) {
                Text(
                    initials(station.displayName),
                    modifier = Modifier.align(Alignment.BottomStart),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    color = Onde.Ink,
                )
            }
        }

        // Titre en cours
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                current?.title?.takeIf { it.isNotBlank() } ?: station.displayName,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    current?.artist?.takeIf { it.isNotBlank() } ?: station.subtitle,
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                    color = Onde.TextSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (current != null) {
                    Text(
                        "Retrouver ce titre",
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Onde.Line, RoundedCornerShape(14.dp))
                            .clickable(role = Role.Button) { context.findTrack(current.artist, current.title) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Barre de retour en arrière
        Timeline(state, engine)

        // Commandes
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(
                Icons.Rounded.Replay30, "Reculer de 30 secondes", { engine.seekBy(-30_000) },
                size = 56.dp, iconSize = 26.dp, enabled = state.canRewind,
            )
            Box(
                Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(Onde.Amber)
                    .clickable(role = Role.Button, onClickLabel = if (state.isPlaying) "Pause" else "Lecture") { engine.togglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                if (state.isBuffering && !state.isPlaying) {
                    CircularProgressIndicator(color = Onde.Ink, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                } else {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Lecture",
                        tint = Onde.Ink,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            CircleIconButton(
                Icons.Rounded.Forward30, "Avancer de 30 secondes", { engine.seekBy(30_000) },
                size = 56.dp, iconSize = 26.dp, enabled = state.canSeekForward,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WideButton(
                label = "Revenir au direct",
                modifier = Modifier.weight(1f),
                enabled = !state.isLive,
                onClick = engine::goLive,
                dotColor = Onde.Red,
            )
            WideButton(
                label = if (state.isRecording) "Arrêter · ${formatDuration(state.recordingMs)}" else "Enregistrer",
                modifier = Modifier.weight(1f),
                enabled = true,
                onClick = engine::toggleRecording,
                icon = if (state.isRecording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
                highlighted = state.isRecording,
            )
        }

        state.previous?.let { prev ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Onde.Line, RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenHistory)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Juste avant", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOf(prev.title, prev.artist).filter { it.isNotBlank() }.joinToString(" · "),
                        fontSize = 12.sp,
                        color = Onde.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("Historique", fontSize = 13.sp, color = Onde.Amber, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Timeline(state: PlayerUiState, engine: PlaybackEngine) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val buffer = state.bufferMs.coerceAtLeast(1)
    val livePosition = (1f - state.delayMs.toFloat() / buffer).coerceIn(0f, 1f)
    val shown = dragging ?: livePosition
    val shownDelay = ((1f - shown) * buffer).toLong()

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Slider(
            value = if (state.canRewind || state.canSeekForward) shown else 1f,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { engine.seekToDelay(((1f - it) * buffer).toLong()) }
                dragging = null
            },
            enabled = state.canRewind,
            colors = SliderDefaults.colors(
                thumbColor = Onde.Amber,
                activeTrackColor = Onde.Amber,
                inactiveTrackColor = Onde.Line,
                disabledThumbColor = Onde.Muted,
                disabledActiveTrackColor = Onde.Line,
                disabledInactiveTrackColor = Onde.Line,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (state.canRewind) "Tampon : ${formatDuration(state.bufferMs)}" else "Pause du direct",
                fontSize = 12.sp,
                color = Onde.Muted,
            )
            Text(
                when {
                    dragging == null && state.isLive -> "En direct"
                    else -> "−${formatDuration(shownDelay)} du direct"
                },
                fontSize = 12.sp,
                color = Onde.Amber,
                fontWeight = FontWeight.SemiBold,
            )
            Text("Direct", fontSize = 12.sp, color = Onde.Muted)
        }
    }
}

@Composable
private fun WideButton(
    label: String,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    dotColor: androidx.compose.ui.graphics.Color? = null,
    icon: ImageVector? = null,
    highlighted: Boolean = false,
) {
    Row(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (highlighted) Onde.Red.copy(alpha = 0.18f) else Onde.Surface2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val alpha = if (enabled) 1f else 0.4f
        if (dotColor != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor.copy(alpha = alpha)))
        }
        if (icon != null) {
            Icon(icon, null, tint = (if (highlighted) Onde.Red else Onde.Text).copy(alpha = alpha), modifier = Modifier.size(16.dp))
        }
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Onde.Text.copy(alpha = alpha), maxLines = 1)
    }
}
