package app.onde.radio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.onde.radio.data.Station
import app.onde.radio.ui.theme.Onde
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

private const val DEG = (PI / 180).toFloat()

/** Un point sur la sphère (trigonométrie précalculée) : une station, ou un point de la grille. */
private class GeoPoint(val station: Station?, latDeg: Float, lonDeg: Float) {
    val sinLat = sin(latDeg * DEG)
    val cosLat = cos(latDeg * DEG)
    val lon = lonDeg * DEG

    constructor(station: Station) : this(station, station.geoLat!!.toFloat(), station.geoLong!!.toFloat())
}

/**
 * Contours des terres (Natural Earth 1:110m, domaine public), ajoutés à l'appli au moment de la
 * compilation par tools/make_land.py. Liste vide si le fichier est absent : le globe marche quand même.
 */
private fun loadLand(context: Context): List<List<GeoPoint>> = runCatching {
    val bytes = context.assets.open("land.bin").use { it.readBytes() }
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    List(buf.int) {
        List(buf.int) {
            val lon = buf.float
            val lat = buf.float
            GeoPoint(null, lat, lon)
        }
    }
}.getOrDefault(emptyList())

/** Méridiens et parallèles, calculés une seule fois. */
private val GRID: List<GeoPoint> = buildList {
    for (lat in -60..60 step 30) for (lon in -180 until 180 step 3) add(GeoPoint(null, lat.toFloat(), lon.toFloat()))
    for (lon in -180 until 180 step 30) for (lat in -84..84 step 3) add(GeoPoint(null, lat.toFloat(), lon.toFloat()))
}

/**
 * Le globe des radios : chaque point est une station. On fait tourner la Terre du doigt,
 * on zoome à deux doigts, et on touche un point pour l'écouter.
 * Pas de carte à télécharger : ce sont les milliers de stations qui dessinent les continents.
 */
@Composable
fun GlobeScreen(vm: MainViewModel, onClose: () -> Unit) {
    val globe by vm.globe.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val library by vm.libraryData.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadGlobe() }

    val points = remember(globe.stations) { globe.stations.map(::GeoPoint) }
    val context = LocalContext.current
    var land by remember { mutableStateOf<List<List<GeoPoint>>>(emptyList()) }
    LaunchedEffect(Unit) { land = withContext(Dispatchers.IO) { loadLand(context) } }
    var centerLon by remember { mutableFloatStateOf(2f) }   // départ : l'Europe
    var centerLat by remember { mutableFloatStateOf(30f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    var selected by remember { mutableStateOf<Station?>(null) }
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val tapRadiusPx = with(LocalDensity.current) { 28.dp.toPx() }
    val dotPx = with(LocalDensity.current) { 2.6.dp.toPx() }

    // Rotation lente quand on ne touche à rien.
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L && System.currentTimeMillis() - lastTouch > 4_000 && selected == null) {
                    centerLon += (now - last) / 1e9f * 4f
                    if (centerLon > 180f) centerLon -= 360f
                }
                last = now
            }
        }
    }

    fun radius() = min(canvasSize.width, canvasSize.height) * 0.42f * zoom

    /** Projette une station à l'écran ; null si elle est de l'autre côté du globe. */
    fun project(p: GeoPoint): Offset? {
        val lat0 = centerLat * DEG
        val dLon = p.lon - centerLon * DEG
        val cosD = cos(dLon)
        val z = sin(lat0) * p.sinLat + cos(lat0) * p.cosLat * cosD
        if (z <= 0f) return null
        val x = p.cosLat * sin(dLon)
        val y = cos(lat0) * p.sinLat - sin(lat0) * p.cosLat * cosD
        val r = radius()
        return Offset(canvasSize.width / 2 + r * x, canvasSize.height / 2 - r * y)
    }

    Box(Modifier.fillMaxSize().background(Onde.Ink)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it.toSize() }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        lastTouch = System.currentTimeMillis()
                        val r = radius().coerceAtLeast(1f)
                        centerLon = (centerLon - pan.x / r / DEG).let { if (it > 180f) it - 360f else if (it < -180f) it + 360f else it }
                        centerLat = (centerLat + pan.y / r / DEG).coerceIn(-80f, 80f)
                        zoom = (zoom * zoomChange).coerceIn(1f, 8f)
                    }
                }
                .pointerInput(points) {
                    detectTapGestures { tap ->
                        lastTouch = System.currentTimeMillis()
                        var best: GeoPoint? = null
                        var bestDist = tapRadiusPx
                        for (p in points) {
                            val o = project(p) ?: continue
                            val d = hypot(o.x - tap.x, o.y - tap.y)
                            if (d < bestDist) {
                                bestDist = d
                                best = p
                            }
                        }
                        val station = best?.station
                        selected = station
                        if (station != null) vm.play(station)
                    }
                },
        ) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = radius()

            // La sphère
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF0E2A1D), Color(0xFF04100A)),
                    center = c + Offset(-r * 0.35f, -r * 0.35f),
                    radius = r * 1.4f,
                ),
                radius = r,
                center = c,
            )
            drawCircle(Onde.Accent.copy(alpha = 0.35f), radius = r, center = c, style = Stroke(width = 1.dp.toPx()))

            // Méridiens et parallèles, en pointillés discrets
            val grid = ArrayList<Offset>(GRID.size)
            for (g in GRID) project(g)?.let(grid::add)
            drawPoints(grid, PointMode.Points, Color.White.copy(alpha = 0.10f), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)

            // Les continents : on trace chaque contour, en coupant là où il passe derrière le globe.
            if (land.isNotEmpty()) {
                val coast = Path()
                for (ring in land) {
                    var penDown = false
                    for (pt in ring) {
                        val o = project(pt)
                        if (o == null) {
                            penDown = false
                        } else if (penDown) {
                            coast.lineTo(o.x, o.y)
                        } else {
                            coast.moveTo(o.x, o.y)
                            penDown = true
                        }
                    }
                }
                drawPath(coast, Onde.Accent.copy(alpha = 0.55f), style = Stroke(width = 1.2.dp.toPx(), join = StrokeJoin.Round))
            }

            // Les stations
            val dots = ArrayList<Offset>(points.size)
            for (p in points) project(p)?.let(dots::add)
            drawPoints(dots, PointMode.Points, Color.White.copy(alpha = 0.7f), strokeWidth = dotPx, cap = StrokeCap.Round)

            // Station en écoute et station touchée
            val playingUuid = player.station?.uuid
            points.firstOrNull { it.station?.uuid == playingUuid }?.let { p ->
                project(p)?.let { o ->
                    drawCircle(Color.White, radius = 4.dp.toPx(), center = o)
                    drawCircle(Color.White.copy(alpha = 0.5f), radius = 10.dp.toPx(), center = o, style = Stroke(1.5.dp.toPx()))
                }
            }
        }

        // En-tête
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Le globe des radios", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (globe.stations.isEmpty()) "Fais tourner la Terre, touche un point pour écouter"
                    else "${globe.stations.size} stations · touche un point pour écouter",
                    fontSize = 12.sp,
                    color = Onde.Muted,
                )
            }
            CircleIconButton(Icons.Rounded.Close, "Fermer le globe", onClose)
        }

        when {
            globe.loading -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(color = Onde.Accent)
                Text("Chargement des radios du monde…", color = Onde.TextSoft, fontSize = 14.sp)
            }
            globe.error != null -> Box(Modifier.align(Alignment.Center).padding(24.dp)) {
                ErrorRetry(globe.error!!) { vm.loadGlobe(force = true) }
            }
        }

        // Fiche de la station touchée
        selected?.let { station ->
            val isCurrent = player.station?.uuid == station.uuid
            val isFavorite = library.favorites.any { it.uuid == station.uuid }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Onde.Surface2)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StationArt(station, 52.dp, 12.dp)
                Column(Modifier.weight(1f)) {
                    Text(station.displayName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val line = player.current?.takeIf { isCurrent }
                        ?.let { listOf(it.title, it.artist).filter { s -> s.isNotBlank() }.joinToString(" · ") }
                        ?: listOf(station.subtitle, station.country).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                    Text(line, fontSize = 12.sp, color = if (isCurrent && player.current != null) Onde.Accent else Onde.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CircleIconButton(
                    icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
                    onClick = { vm.library.toggleFavorite(station) },
                    tint = Onde.Accent,
                )
                CircleIconButton(
                    icon = if (isCurrent && player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isCurrent && player.isPlaying) "Pause" else "Lecture",
                    onClick = { if (isCurrent) vm.engine.togglePlay() else vm.play(station) },
                    background = Onde.Accent,
                    tint = Onde.Ink,
                )
            }
        }
    }
}
