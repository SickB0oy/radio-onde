package app.onde.radio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onde.radio.ui.theme.Onde
import kotlinx.coroutines.delay

/**
 * Zapping : une station par page, on glisse vers le haut pour passer à la suivante.
 * La station se lance dès qu'on s'arrête sur une page.
 */
@Composable
fun ZappingScreen(vm: MainViewModel, onClose: () -> Unit) {
    val stations = remember { vm.zapStations() }
    val title = remember { vm.zapTitle() }
    val player by vm.player.collectAsStateWithLifecycle()
    val library by vm.libraryData.collectAsStateWithLifecycle()

    if (stations.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircleIconButton(Icons.Rounded.Close, "Fermer le Zapping", onClose)
            EmptyMessage("Rien à zapper pour l'instant", "Ouvre d'abord un onglet de style sur l'accueil, puis relance le Zapping.")
        }
        return
    }

    val pager = rememberPagerState(pageCount = { stations.size })

    // On lance la station quand on s'arrête sur une page (petit délai pour ne pas lancer chaque page survolée).
    LaunchedEffect(pager.settledPage) {
        delay(350)
        val station = stations[pager.settledPage]
        if (vm.player.value.station?.uuid != station.uuid) vm.play(station)
    }

    Box(Modifier.fillMaxSize()) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val station = stations[page]
            val isCurrent = player.station?.uuid == station.uuid
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
            ) {
                StationArt(station, 240.dp, 36.dp)
                Text(
                    station.displayName,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (station.subtitle.isNotBlank()) {
                    Text(station.subtitle, fontSize = 15.sp, color = Onde.TextSoft, textAlign = TextAlign.Center)
                }
                val nowPlaying = player.current?.takeIf { isCurrent }
                Text(
                    when {
                        nowPlaying != null -> listOf(nowPlaying.title, nowPlaying.artist).filter { it.isNotBlank() }.joinToString(" · ")
                        isCurrent && player.isBuffering && !player.isPlaying -> "Connexion…"
                        isCurrent -> "En direct"
                        else -> " "
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Onde.Accent,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // En-tête
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(Icons.Rounded.Close, "Fermer le Zapping", onClose)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Zapping", fontSize = 13.sp, color = Onde.Muted)
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${pager.currentPage + 1}/${stations.size}", fontSize = 13.sp, color = Onde.Muted)
        }

        // Commandes du bas
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val station = stations[pager.currentPage]
            val isFavorite = library.favorites.any { it.uuid == station.uuid }
            val isCurrent = player.station?.uuid == station.uuid
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(
                    icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
                    onClick = { vm.library.toggleFavorite(station) },
                    size = 56.dp,
                    iconSize = 26.dp,
                    tint = Onde.Accent,
                )
                Box(
                    Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(Onde.Accent)
                        .clickable(role = Role.Button) {
                            if (isCurrent) vm.engine.togglePlay() else vm.play(station)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isCurrent && player.isBuffering && !player.isPlaying) {
                        CircularProgressIndicator(color = Onde.Ink, modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
                    } else {
                        Icon(
                            if (isCurrent && player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isCurrent && player.isPlaying) "Pause" else "Lecture",
                            tint = Onde.Ink,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
                Box(Modifier.size(56.dp))
            }
            if (pager.currentPage < stations.size - 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.KeyboardArrowUp, null, tint = Onde.Muted, modifier = Modifier.size(18.dp))
                    Text("Glisse vers le haut pour la station suivante", fontSize = 12.sp, color = Onde.Muted)
                }
            }
        }
    }
}
