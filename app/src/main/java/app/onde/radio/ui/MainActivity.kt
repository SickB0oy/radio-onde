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

enum class Tab(val label: String) {
    Home("Accueil"),
    Player("Lecteur"),
    History("Historique"),
    Favorites("Favoris"),
}

@Composable
fun OndeRoot(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val player by vm.player.collectAsStateWithLifecycle()
    val library by vm.libraryData.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
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

    BackHandler(enabled = searchOpen || tab != Tab.Home) {
        if (searchOpen) searchOpen = false else tab = Tab.Home
    }

    Scaffold(
        containerColor = Onde.Ink,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.background(Onde.Ink).navigationBarsPadding()) {
                if (player.station != null && tab != Tab.Player && !searchOpen) {
                    MiniPlayer(
                        state = player,
                        onOpen = { tab = Tab.Player },
                        onToggle = vm.engine::togglePlay,
                    )
                }
                BottomNav(
                    selected = tab,
                    searchOpen = searchOpen,
                    onSearch = { searchOpen = true },
                ) {
                    tab = it
                    searchOpen = false
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                searchOpen -> SearchScreen(
                    vm = vm,
                    onPlay = {
                        vm.play(it)
                        searchOpen = false
                        tab = Tab.Player
                    },
                    onClose = { searchOpen = false },
                )
                tab == Tab.Home -> HomeScreen(vm = vm, onPlay = vm::play)
                tab == Tab.Player -> PlayerScreen(
                    state = player,
                    isFavorite = player.station?.let { s -> library.favorites.any { it.uuid == s.uuid } } == true,
                    engine = vm.engine,
                    onToggleFavorite = { player.station?.let(vm.library::toggleFavorite) },
                    onBack = { tab = Tab.Home },
                    onOpenHistory = { tab = Tab.History },
                )
                tab == Tab.History -> HistoryScreen(
                    library = library,
                    player = player,
                    engine = vm.engine,
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
            background = Onde.Amber,
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
        val (left, right) = Tab.entries.partition { it == Tab.Home || it == Tab.Player }
        left.forEach { NavItem(it, active = it == selected && !searchOpen, onSelect = onSelect) }

        // Gros bouton de recherche, au centre et à portée de pouce.
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(if (searchOpen) Onde.Text else Onde.Amber)
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
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Tab, onClick = { onSelect(tab) }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier
                .width(24.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (active) Onde.Amber else Color.Transparent)
        )
        Text(
            tab.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (active) Onde.Amber else Onde.Muted,
            maxLines = 1,
        )
    }
}
