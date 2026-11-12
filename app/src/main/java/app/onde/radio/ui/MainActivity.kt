package app.onde.radio.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.onde.radio.player.PlayerUiState
import app.onde.radio.ui.theme.Onde
import app.onde.radio.ui.theme.OndeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OndeTheme {
                OndeRoot()
            }
        }
    }
}

enum class Tab(val label: String, val icon: ImageVector, val activeIcon: ImageVector) {
    Home("Accueil", Icons.Outlined.Home, Icons.Rounded.Home),
    Globe("Globe", Icons.Outlined.Public, Icons.Rounded.Public),
    History("Historique", Icons.Outlined.History, Icons.Rounded.History),
    Favorites("Favoris", Icons.Outlined.FavoriteBorder, Icons.Rounded.Favorite),
}

@Composable
fun OndeRoot(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val player by vm.player.collectAsStateWithLifecycle()
    val library by vm.libraryData.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    var zappingOpen by rememberSaveable { mutableStateOf(false) }
    // Le lecteur s'ouvre par-dessus l'onglet en cours (en touchant le mini-lecteur).
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    val closeOverlays = {
        aboutOpen = false
        zappingOpen = false
        playerOpen = false
        searchOpen = false
    }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(player.message) {
        player.message?.let {
            vm.engine.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }

    // Android 13+ : la notification de lecture a besoin de cette autorisation.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val overlayOpen = aboutOpen || zappingOpen || playerOpen || searchOpen
    BackHandler(enabled = overlayOpen || tab != Tab.Home) {
        if (overlayOpen) closeOverlays() else tab = Tab.Home
    }

    Scaffold(
        containerColor = Onde.Ink,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.background(Onde.Ink).navigationBarsPadding()) {
                // Sur le globe, la fiche de la station fait déjà office de mini-lecteur.
                val globeShown = tab == Tab.Globe && !overlayOpen
                if (player.station != null && !playerOpen && !searchOpen && !zappingOpen && !globeShown) {
                    MiniPlayer(
                        state = player,
                        onOpen = {
                            closeOverlays()
                            playerOpen = true
                        },
                        onToggle = vm.engine::togglePlay,
                    )
                }
                BottomNav(
                    selected = tab,
                    searchOpen = searchOpen,
                    onSearch = {
                        closeOverlays()
                        searchOpen = true
                    },
                ) {
                    tab = it
                    closeOverlays()
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                aboutOpen -> AboutScreen(vm = vm, onClose = { aboutOpen = false })
                zappingOpen -> ZappingScreen(vm = vm, onClose = { zappingOpen = false })
                playerOpen -> PlayerScreen(
                    state = player,
                    isFavorite = player.station?.let { s -> library.favorites.any { it.uuid == s.uuid } } == true,
                    engine = vm.engine,
                    onToggleFavorite = { player.station?.let(vm.library::toggleFavorite) },
                    onBack = { playerOpen = false },
                    onOpenHistory = {
                        playerOpen = false
                        tab = Tab.History
                    },
                )
                searchOpen -> SearchScreen(
                    vm = vm,
                    onPlay = {
                        vm.play(it)
                        searchOpen = false
                        playerOpen = true
                    },
                    onClose = { searchOpen = false },
                )
                tab == Tab.Home -> HomeScreen(
                    vm = vm,
                    onPlay = vm::play,
                    onSettings = { aboutOpen = true },
                    onZapping = { zappingOpen = true },
                )
                tab == Tab.Globe -> GlobeScreen(vm = vm, onClose = null, onOpenPlayer = { playerOpen = true })
                tab == Tab.History -> HistoryScreen(
                    library = library,
                    onToggleSaved = vm.library::toggleSaved,
                )
                else -> FavoritesScreen(library = library, onPlay = vm::play)
            }
        }
    }
}

@Composable
private fun MiniPlayer(state: PlayerUiState, onOpen: () -> Unit, onToggle: () -> Unit) {
    val station = state.station ?: return
    val title = state.current?.let { m -> listOf(m.title, m.artist).filter { it.isNotBlank() }.joinToString(" · ") }
        ?: station.displayName
    val status = when {
        state.isBuffering && !state.isPlaying -> "Connexion…"
        !state.isLive -> "−${formatDuration(state.delayMs)} du direct"
        else -> "En direct"
    }
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Onde.Surface2)
            .clickable(onClickLabel = "Ouvrir le lecteur", onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StationArt(station, 44.dp, 10.dp)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${station.displayName} · $status",
                fontSize = 12.sp,
                color = Onde.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CircleIconButton(
            icon = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (state.isPlaying) "Pause" else "Lecture",
            onClick = onToggle,
            background = Onde.Accent,
            tint = Onde.Ink,
        )
    }
}

@Composable
private fun BottomNav(
    selected: Tab,
    searchOpen: Boolean,
    onSearch: () -> Unit,
    onSelect: (Tab) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(Color(0xFF1F242D), Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (left, right) = Tab.entries.partition { it == Tab.Home || it == Tab.Globe }
        left.forEach { NavItem(it, active = it == selected && !searchOpen, onSelect = onSelect) }

        // Gros bouton de recherche, au centre et à portée de pouce.
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(if (searchOpen) Onde.Text else Onde.Accent)
                    .clickable(role = Role.Button, onClickLabel = "Rechercher une station", onClick = onSearch),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = "Rechercher une station",
                    tint = Onde.Ink,
                    modifier = Modifier.size(30.dp),
                )
            }
        }

        right.forEach { NavItem(it, active = it == selected && !searchOpen, onSelect = onSelect) }
    }
}

@Composable
private fun RowScope.NavItem(tab: Tab, active: Boolean, onSelect: (Tab) -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Tab, onClick = { onSelect(tab) }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        // Icône dans une pastille verte quand l'onglet est actif.
        Box(
            Modifier
                .width(56.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(if (active) Onde.Accent.copy(alpha = 0.18f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (active) tab.activeIcon else tab.icon,
                contentDescription = null,
                tint = if (active) Onde.Accent else Onde.Muted,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            tab.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (active) Onde.Accent else Onde.Muted,
            maxLines = 1,
        )
    }
}
