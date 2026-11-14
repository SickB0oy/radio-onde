package app.onde.radio.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import app.onde.radio.data.PlaceNames
import app.onde.radio.data.Station
import app.onde.radio.ui.theme.Onde
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
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

/** Un résultat de recherche sur le globe : un pays, une région/ville ou une station. */
private class GlobeHit(
    val label: String,
    val detail: String,
    val lat: Float,
    val lon: Float,
    val zoom: Float,
    val station: Station? = null,
)

private fun normalizeText(s: String): String =
    Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").trim()

/**
 * Index des lieux, construit à partir des stations elles-mêmes (rien à télécharger) : chaque pays
 * et chaque région/ville est placé au centre de ses stations, avec un zoom adapté à leur étendue.
 */
private class PlaceIndex(private val points: List<GeoPoint>) {
    private class Place(val hit: GlobeHit, val key: String, val count: Int)

    private val countries: List<Place>
    private val regions: List<Place>
    private val stationNames: List<String> = points.map { normalizeText(it.station?.displayName.orEmpty()) }

    init {
        fun place(name: String, detail: String, pts: List<GeoPoint>): Place {
            val lats = pts.map { it.latDeg }.sorted()
            val lons = pts.map { it.lonDeg }.sorted()
            fun q(l: List<Float>, f: Float) = l[((l.size - 1) * f).toInt()]
            val lat = q(lats, 0.5f)
            val spread = maxOf(q(lats, 0.9f) - q(lats, 0.1f), (q(lons, 0.9f) - q(lons, 0.1f)) * cos(lat * DEG))
            val zoom = (48f / maxOf(spread, 1.5f)).coerceIn(2.5f, 20f)
            return Place(GlobeHit(name, detail, lat, q(lons, 0.5f), zoom), normalizeText(name), pts.size)
        }
        val withStation = points.filter { it.station != null }
        countries = withStation.groupBy { it.station!!.countryCode.uppercase() }
            .filterKeys { it.length == 2 }
            .map { (_, pts) -> place(pts.first().station!!.countryNameFr, "Pays · ${pts.size} stations", pts) }
            .filter { it.key.isNotBlank() }
        regions = withStation.filter { it.station!!.state.isNotBlank() }
            .groupBy { normalizeText(PlaceNames.fr(it.station!!.state)) + "|" + it.station!!.countryCode.uppercase() }
            .map { (_, pts) ->
                val st = pts.first().station!!
                place(PlaceNames.fr(st.state), "${st.countryNameFr} · ${pts.size} stations", pts)
            }
            .filter { it.key.length >= 2 }
    }

    fun search(query: String): List<GlobeHit> {
        val q = normalizeText(query)
        if (q.length < 2) return emptyList()
        fun best(list: List<Place>, max: Int) = list.filter { it.key.contains(q) }
            .sortedWith(compareByDescending<Place> { it.key.startsWith(q) }.thenByDescending { it.count })
            .take(max).map { it.hit }
        val stations = ArrayList<GlobeHit>(4)
        for (i in points.indices) {
            if (stations.size >= 4) break
            if (!stationNames[i].contains(q)) continue
            val p = points[i]
            val st = p.station ?: continue
            // Zoom suffisant pour que le point de cette station soit affiché.
            val zoom = maxOf(8f, sqrt((i + 1) / 150f) + 1f).coerceAtMost(MAX_ZOOM)
            stations += GlobeHit(st.displayName, "Station · ${st.placeLabel}", p.latDeg, p.lonDeg, zoom, st)
        }
        return best(countries, 3) + best(regions, 4) + stations
    }
}

/**
 * Le globe des radios : chaque point est une station. On fait tourner la Terre du doigt,
 * on zoome à deux doigts, et on touche un point pour l'écouter.
 * Pas de carte à télécharger : ce sont les milliers de stations qui dessinent les continents.
 */
@OptIn(FlowPreview::class)
@Composable
fun GlobeScreen(vm: MainViewModel, onClose: (() -> Unit)?, onOpenPlayer: () -> Unit = {}) {
    val globe by vm.globe.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val library by vm.libraryData.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadGlobe() }

    val context = LocalContext.current
    var land by remember { mutableStateOf<List<LandRing>>(emptyList()) }
    var landDetailed by remember { mutableStateOf<List<LandRing>>(emptyList()) }
    var borders by remember { mutableStateOf<List<LandRing>>(emptyList()) }
    var bordersDetailed by remember { mutableStateOf<List<LandRing>>(emptyList()) }
    var boxes by remember { mutableStateOf<Map<String, FloatArray>>(emptyMap()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            land = loadLand(context, "land.bin")
            landDetailed = loadLand(context, "land50.bin")
            borders = loadLand(context, "borders.bin")
            bordersDetailed = loadLand(context, "borders50.bin")
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

    // Recherche d'un pays, d'une région, d'une ville ou d'une station.
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val placeIndex = remember(points, searchOpen) { if (searchOpen) PlaceIndex(points) else null }
    val hits = remember(placeIndex, query) { placeIndex?.search(query).orEmpty() }
    BackHandler(enabled = searchOpen) { searchOpen = false }

    // Déplacement animé vers un lieu (par le plus court chemin autour du globe).
    val scope = rememberCoroutineScope()
    var flyJob by remember { mutableStateOf<Job?>(null) }
    fun flyTo(lat: Float, lon: Float, targetZoom: Float) {
        flyJob?.cancel()
        val lat0 = centerLat
        val lon0 = centerLon
        val z0 = zoom
        var dLon = lon - lon0
        if (dLon > 180f) dLon -= 360f
        if (dLon < -180f) dLon += 360f
        flyJob = scope.launch {
            val start = withFrameNanos { it }
            while (true) {
                val t = withFrameNanos { ((it - start) / 9e8f).coerceIn(0f, 1f) }
                val e = t * t * (3 - 2 * t)
                centerLat = (lat0 + (lat - lat0) * e).coerceIn(-80f, 80f)
                centerLon = (lon0 + dLon * e).let { if (it > 180f) it - 360f else if (it < -180f) it + 360f else it }
                zoom = exp(ln(z0) + (ln(targetZoom) - ln(z0)) * e).coerceIn(1f, MAX_ZOOM)
                lastTouch = System.currentTimeMillis()
                if (t >= 1f) break
            }
        }
    }

    // Quand on zoome sur un pays, on charge toutes ses stations (une seule fois par pays).
    LaunchedEffect(boxes) {
        if (boxes.isEmpty()) return@LaunchedEffect
        snapshotFlow { Triple(zoom >= DETAIL_ZOOM, centerLat.roundToInt(), centerLon.roundToInt()) }
            .debounce(700)
            .collect { (zoomedIn, lat, lon) ->
                if (!zoomedIn) return@collect
                boxes.entries
                    .filter { (_, b) -> lat >= b[0] && lat <= b[1] && lon >= b[2] && lon <= b[3] }
                    .sortedBy { (_, b) -> (b[1] - b[0]) * (b[3] - b[2]) }
                    .take(2)
                    .forEach { vm.loadGlobeCountry(it.key) }
            }
    }

    // Rotation lente quand on ne touche à rien.
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                // Seulement vu de loin : de près, la rotation ferait perdre l'endroit qu'on regarde.
                if (last != 0L && zoom < 1.5f && System.currentTimeMillis() - lastTouch > 4_000 && selected == null) {
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
                        flyJob?.cancel()
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
            // Tracés visibles seulement (on saute ceux hors de l'écran), coupés à l'horizon.
            val lat0r = centerLat * DEG
            val lon0r = centerLon * DEG
            val vx = cos(lat0r) * cos(lon0r)
            val vy = cos(lat0r) * sin(lon0r)
            val vz = sin(lat0r)
            // Angle entre le centre de l'écran et son coin, vu depuis le centre de la Terre.
            val halfDiag = hypot(size.width, size.height) / 2
            val viewAngle = if (halfDiag >= radius()) (PI / 2).toFloat() else asin(halfDiag / radius())
            fun pathOf(rings: List<LandRing>): Path {
                val path = Path()
                for (ring in rings) {
                    val dot = (vx * ring.cx + vy * ring.cy + vz * ring.cz).coerceIn(-1f, 1f)
                    if (acos(dot) - ring.radius > viewAngle) continue
                    var penDown = false
                    for (pt in ring.points) {
                        val o = project(pt)
                        if (o == null) {
                            penDown = false
                        } else if (penDown) {
                            path.lineTo(o.x, o.y)
                        } else {
                            path.moveTo(o.x, o.y)
                            penDown = true
                        }
                    }
                }
                return path
            }
            val detailed = zoom >= DETAIL_ZOOM

            // Frontières entre pays : fines et discrètes, sous les côtes.
            val borderRings = if (detailed && bordersDetailed.isNotEmpty()) bordersDetailed else borders
            if (borderRings.isNotEmpty()) {
                drawPath(
                    pathOf(borderRings),
                    Onde.Accent.copy(alpha = 0.30f),
                    style = Stroke(width = 0.8.dp.toPx(), join = StrokeJoin.Round),
                )
            }

            // Côtes : carte détaillée quand on zoome (si présente).
            val coastRings = if (detailed && landDetailed.isNotEmpty()) landDetailed else land
            if (coastRings.isNotEmpty()) {
                drawPath(
                    pathOf(coastRings),
                    Onde.Accent.copy(alpha = 0.55f),
                    style = Stroke(width = 1.2.dp.toPx(), join = StrokeJoin.Round),
                )
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
                if (x < -bigDotPx || y < -bigDotPx || x > size.width + bigDotPx || y > size.height + bigDotPx) continue
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

        // En-tête, avec la recherche
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Le globe des radios", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            globe.stations.isEmpty() -> "Fais tourner la Terre, touche un point pour écouter"
                            globe.loadingMore > 0 -> "${globe.stations.size} stations · chargement de la zone…"
                            else -> "${globe.stations.size} stations · zoome pour en voir plus"
                        },
                        fontSize = 12.sp,
                        color = Onde.Muted,
                    )
                }
                CircleIconButton(
                    icon = if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                    contentDescription = if (searchOpen) "Fermer la recherche" else "Chercher un lieu ou une station",
                    onClick = {
                        searchOpen = !searchOpen
                        query = ""
                    },
                    tint = Onde.Accent,
                )
                // Bouton fermer seulement quand le globe est ouvert par-dessus un autre écran.
                if (onClose != null) CircleIconButton(Icons.Rounded.Close, "Fermer le globe", onClose)
            }

            if (searchOpen) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    placeholder = { Text("Pays, région, ville ou station…") },
                    singleLine = true,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Onde.Accent,
                        unfocusedBorderColor = Onde.Line,
                        cursorColor = Onde.Accent,
                        focusedContainerColor = Onde.Surface,
                        unfocusedContainerColor = Onde.Surface,
                    ),
                )
                if (hits.isNotEmpty() || query.trim().length >= 2) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Onde.Surface2),
                    ) {
                        if (hits.isEmpty()) {
                            Text(
                                "Aucun lieu ni station trouvé sur le globe.",
                                modifier = Modifier.padding(16.dp),
                                fontSize = 13.sp,
                                color = Onde.Muted,
                            )
                        }
                        hits.forEach { hit ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.Button) {
                                        searchOpen = false
                                        query = ""
                                        hit.station?.let {
                                            selected = it
                                            vm.play(it)
                                        }
                                        flyTo(hit.lat, hit.lon, hit.zoom)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Text(hit.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(hit.detail, fontSize = 12.sp, color = Onde.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
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
        // Une seule fiche : la station touchée, sinon celle en écoute (le mini-lecteur est masqué ici).
        (selected ?: player.station)?.let { station ->
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
                    .clickable(role = Role.Button, onClickLabel = if (isCurrent) "Ouvrir le lecteur" else "Écouter ${station.displayName}") {
                        if (isCurrent) onOpenPlayer() else vm.play(station)
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
