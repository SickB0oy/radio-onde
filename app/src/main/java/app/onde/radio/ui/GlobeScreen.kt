package app.onde.radio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

private const val DEG = (PI / 180).toFloat()

/** Zoom maximal (×40 : on voit une région). */
private const val MAX_ZOOM = 40f

/** À partir de ce zoom, on dessine la carte détaillée (Natural Earth 1:50m). */
private const val DETAIL_ZOOM = 4f

/** Un point sur la sphère (trigonométrie précalculée) : une station, ou un point de la grille. */
private class GeoPoint(val station: Station?, val latDeg: Float, val lonDeg: Float) {
    val sinLat = sin(latDeg * DEG)
    val cosLat = cos(latDeg * DEG)
    val lon = lonDeg * DEG

    constructor(station: Station) : this(station, station.geoLat!!.toFloat(), station.geoLong!!.toFloat())
}

/**
 * Contours des terres (Natural Earth 1:110m, domaine public), ajoutés à l'appli au moment de la
 * compilation par tools/make_land.py. Liste vide si le fichier est absent : le globe marche quand même.
 */
private fun loadLand(context: Context, file: String): List<LandRing> = runCatching {
    val bytes = context.assets.open(file).use { it.readBytes() }
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    List(buf.int) {
        LandRing(
            List(buf.int) {
                val lon = buf.float
                val lat = buf.float
                GeoPoint(null, lat, lon)
            }
        )
    }
}.getOrDefault(emptyList())

/**
 * Un contour de côte, avec son centre et son « rayon » (en angle sur la sphère) : permet de
 * sauter d'un coup les contours hors de l'écran quand on est très zoomé.
 */
private class LandRing(val points: List<GeoPoint>) {
    val cx: Float
    val cy: Float
    val cz: Float
    val radius: Float

    init {
        var x = 0f; var y = 0f; var z = 0f
        for (p in points) {
            x += p.cosLat * cos(p.lon); y += p.cosLat * sin(p.lon); z += p.sinLat
        }
        val n = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
        cx = x / n; cy = y / n; cz = z / n
        var maxAngle = 0f
        for (p in points) {
            val dot = cx * p.cosLat * cos(p.lon) + cy * p.cosLat * sin(p.lon) + cz * p.sinLat
            maxAngle = maxOf(maxAngle, acos(dot.coerceIn(-1f, 1f)))
        }
        radius = maxAngle
    }
}

/**
 * Rectangle englobant de chaque pays (Natural Earth, domaine public), généré à la compilation
 * par tools/make_countries.py. Sert à écarter les stations dont la position déclarée dans
 * l'annuaire ne correspond pas à leur pays (coordonnées inversées, fantaisistes…).
 */
private fun loadCountryBoxes(context: Context): Map<String, FloatArray> = runCatching {
    context.assets.open("countries.txt").bufferedReader().readLines().mapNotNull { line ->
        val parts = line.trim().split(' ')
        if (parts.size != 5) return@mapNotNull null
        val nums = parts.drop(1).map { it.toFloatOrNull() ?: return@mapNotNull null }
        parts[0] to nums.toFloatArray()
    }.toMap()
}.getOrDefault(emptyMap())

/**
 * Nombre de stations affichées selon le zoom : 150 vue de loin, puis de plus en plus
 * (au carré du zoom, comme la surface visible) jusqu'à toutes les afficher.
 */
private fun visibleCount(total: Int, zoom: Float): Int =
    minOf(total, (150f * zoom * zoom).toInt())

/** Garde une station si son pays est inconnu, ou si elle tombe dans son pays (avec une marge). */
private fun plausible(p: GeoPoint, boxes: Map<String, FloatArray>): Boolean {
    val box = boxes[p.station?.countryCode?.uppercase() ?: return true] ?: return true
    val margin = 1.5f
    val lonOk = box[2] <= -179f && box[3] >= 179f ||
        p.lonDeg in (box[2] - margin)..(box[3] + margin)
    return p.latDeg in (box[0] - margin)..(box[1] + margin) && lonOk
}

/**
 * Le globe des radios : chaque point est une station. On fait tourner la Terre du doigt,
 * on zoome à deux doigts, et on touche un point pour l'écouter.
 * Pas de carte à télécharger : ce sont les milliers de stations qui dessinent les continents.
 */
@Composable
fun GlobeScreen(vm: MainViewModel, onClose: (() -> Unit)?) {
    val globe by vm.globe.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val library by vm.libraryData.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadGlobe() }

    val context = LocalContext.current
    var land by remember { mutableStateOf<List<LandRing>>(emptyList()) }
    var landDetailed by remember { mutableStateOf<List<LandRing>>(emptyList()) }
    var boxes by remember { mutableStateOf<Map<String, FloatArray>>(emptyMap()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            land = loadLand(context, "land.bin")
            landDetailed = loadLand(context, "land50.bin")
            boxes = loadCountryBoxes(context)
        }
    }
    // Stations triées de la plus écoutée à la moins écoutée (ordre de l'annuaire), hors positions douteuses.
    val points = remember(globe.stations, boxes) {
        globe.stations.map(::GeoPoint).filter { plausible(it, boxes) }
    }
    var centerLon by remember { mutableFloatStateOf(2f) }   // départ : l'Europe
    var centerLat by remember { mutableFloatStateOf(30f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    var selected by remember { mutableStateOf<Station?>(null) }
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val tapRadiusPx = with(LocalDensity.current) { 28.dp.toPx() }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val dotPx = with(LocalDensity.current) { 2.6.dp.toPx() }
    val midDotPx = with(LocalDensity.current) { 3.6.dp.toPx() }
    val bigDotPx = with(LocalDensity.current) { 5.dp.toPx() }

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
                        zoom = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
                    }
                }
                .pointerInput(points) {
                    detectTapGestures { tap ->
                        lastTouch = System.currentTimeMillis()
                        var best: GeoPoint? = null
                        var bestDist = tapRadiusPx
                        for (p in points.take(visibleCount(points.size, zoom))) {
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

            // Les continents : on trace chaque contour, en coupant là où il passe derrière le globe.
            // Carte détaillée quand on zoome (si présente), et seulement les contours visibles.
            val rings = if (zoom >= DETAIL_ZOOM && landDetailed.isNotEmpty()) landDetailed else land
            if (rings.isNotEmpty()) {
                val lat0r = centerLat * DEG
                val lon0r = centerLon * DEG
                val vx = cos(lat0r) * cos(lon0r)
                val vy = cos(lat0r) * sin(lon0r)
                val vz = sin(lat0r)
                // Angle entre le centre de l'écran et son coin, vu depuis le centre de la Terre.
                val halfDiag = hypot(size.width, size.height) / 2
                val viewAngle = if (halfDiag >= radius()) (PI / 2).toFloat() else asin(halfDiag / radius())
                val coast = Path()
                for (ring in rings) {
                    val dot = (vx * ring.cx + vy * ring.cy + vz * ring.cz).coerceIn(-1f, 1f)
                    if (acos(dot) - ring.radius > viewAngle) continue
                    var penDown = false
                    for (pt in ring.points) {
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

            // Les stations : seulement les plus écoutées quand on est loin, de plus en plus quand on
            // zoome. Ce choix ne dépend que du zoom (pas de la position à l'écran) : rien ne clignote
            // quand la Terre tourne. Les plus écoutées sont plus grosses ; près du bord, plus discrètes.
            val r0 = radius()
            val lat0 = centerLat * DEG
            val sinLat0 = sin(lat0)
            val cosLat0 = cos(lat0)
            val count = visibleCount(points.size, zoom)
            for (index in 0 until count) {
                val p = points[index]
                val dLon = p.lon - centerLon * DEG
                val cosD = cos(dLon)
                val z = sinLat0 * p.sinLat + cosLat0 * p.cosLat * cosD
                if (z <= 0f) continue
                val x = c.x + r0 * p.cosLat * sin(dLon)
                val y = c.y - r0 * (cosLat0 * p.sinLat - sinLat0 * p.cosLat * cosD)
                val dot = when {
                    index < 40 -> bigDotPx
                    index < 250 -> midDotPx
                    else -> dotPx
                }
                // Fondu continu vers le bord du globe (pas de palier qui saute).
                val alpha = 0.25f + 0.75f * (z / 0.5f).coerceAtMost(1f)
                drawCircle(Color.White.copy(alpha = alpha), radius = dot / 2, center = Offset(x, y))
            }

            // Station touchée : un anneau et, en petit, la ville et le pays à côté du point.
            selected?.let { sel ->
                val sp = points.firstOrNull { it.station?.uuid == sel.uuid } ?: return@let
                val o = project(sp) ?: return@let
                drawCircle(Onde.Accent, radius = 7.dp.toPx(), center = o, style = Stroke(2.dp.toPx()))
                val place = sel.placeLabel
                if (place.isNotBlank()) {
                    val layout = textMeasurer.measure(place, labelStyle)
                    val pad = 4.dp.toPx()
                    val gap = 10.dp.toPx()
                    val w = layout.size.width + 2 * pad
                    val h = layout.size.height + 2 * pad
                    // À droite du point, ou à gauche s'il n'y a pas la place.
                    val left = if (o.x + gap + w < size.width) o.x + gap else o.x - gap - w
                    val top = o.y - h / 2
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.65f),
                        topLeft = Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(w, h),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                    drawText(layout, topLeft = Offset(left + pad, top + pad))
                }
            }

            // Station en écoute
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
            // Bouton fermer seulement quand le globe est ouvert par-dessus un autre écran.
            if (onClose != null) CircleIconButton(Icons.Rounded.Close, "Fermer le globe", onClose)
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
                    // La fiche garde le toucher pour elle (sinon il passait au globe, qui la fermait) :
                    // toucher la fiche lance la station si elle n'est pas déjà en écoute.
                    .clickable(role = Role.Button, onClickLabel = "Écouter ${station.displayName}") {
                        if (!isCurrent) vm.play(station)
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StationArt(station, 52.dp, 12.dp)
                Column(Modifier.weight(1f)) {
                    Text(station.displayName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (station.placeLabel.isNotBlank()) {
                        Text("📍 " + station.placeLabel, fontSize = 11.sp, color = Onde.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val line = player.current?.takeIf { isCurrent }
                        ?.let { listOf(it.title, it.artist).filter { s -> s.isNotBlank() }.joinToString(" · ") }
                        ?: station.subtitle
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
