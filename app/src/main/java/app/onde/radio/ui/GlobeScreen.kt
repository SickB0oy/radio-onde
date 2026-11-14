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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
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

/** Une station placée sur la sphère : sa position est précalculée en vecteur 3D (aucun calcul d'angle à l'affichage). */
private class GeoPoint(val station: Station, val latDeg: Float, val lonDeg: Float) {
    val x = cos(latDeg * DEG) * cos(lonDeg * DEG)
    val y = cos(latDeg * DEG) * sin(lonDeg * DEG)
    val z = sin(latDeg * DEG)

    constructor(station: Station) : this(station, station.geoLat!!.toFloat(), station.geoLong!!.toFloat())
}

/** Les stations du globe, avec leurs positions rangées dans un seul tableau pour un affichage rapide. */
private class StationPoints(val list: List<GeoPoint>) {
    val xyz = FloatArray(list.size * 3).also { a ->
        list.forEachIndexed { i, p -> a[3 * i] = p.x; a[3 * i + 1] = p.y; a[3 * i + 2] = p.z }
    }
}

/**
 * Le point de vue : trois axes (vers l'œil, vers l'est, vers le nord) calculés une fois par image.
 * Projeter un point revient alors à quelques multiplications.
 */
private class Camera(latDeg: Float, lonDeg: Float, val r: Float, val cx: Float, val cy: Float) {
    private val sinLat = sin(latDeg * DEG)
    private val cosLat = cos(latDeg * DEG)
    private val sinLon = sin(lonDeg * DEG)
    private val cosLon = cos(lonDeg * DEG)
    val fx = cosLat * cosLon; val fy = cosLat * sinLon; val fz = sinLat
    val ex = -sinLon; val ey = cosLon
    val nx = -sinLat * cosLon; val ny = -sinLat * sinLon; val nz = cosLat

    /** Position à l'écran, ou null si le point est de l'autre côté du globe. */
    fun project(p: GeoPoint): Offset? {
        if (p.x * fx + p.y * fy + p.z * fz <= 0f) return null
        return Offset(cx + r * (p.x * ex + p.y * ey), cy - r * (p.x * nx + p.y * ny + p.z * nz))
    }
}

/** Garde le dégradé de la sphère tant que ni le zoom ni la taille de l'écran ne changent. */
private class BrushCache {
    private var r = -1f
    private var center = Offset.Unspecified
    private var brush: Brush? = null

    fun get(radius: Float, c: Offset): Brush {
        brush?.let { if (radius == r && c == center) return it }
        r = radius
        center = c
        return Brush.radialGradient(
            colors = listOf(Color(0xFF0E2A1D), Color(0xFF04100A)),
            center = c + Offset(-radius * 0.35f, -radius * 0.35f),
            radius = radius * 1.4f,
        ).also { brush = it }
    }
}

/** Tableau de nombres réutilisé d'une image à l'autre (aucune allocation pendant qu'on bouge le globe). */
private class FloatBuf(capacity: Int = 4096) {
    var data = FloatArray(capacity)
    var n = 0

    fun add2(a: Float, b: Float) {
        if (n + 2 > data.size) data = data.copyOf(data.size * 2)
        data[n] = a; data[n + 1] = b
        n += 2
    }

    fun add4(a: Float, b: Float, c: Float, d: Float) {
        if (n + 4 > data.size) data = data.copyOf(data.size * 2)
        data[n] = a; data[n + 1] = b; data[n + 2] = c; data[n + 3] = d
        n += 4
    }
}

/**
 * Côtes ou frontières (Natural Earth, domaine public), découpées en petits tronçons. Chaque
 * tronçon connaît son centre et son étendue : quand on est zoomé, on écarte d'un seul test tous
 * ceux qui sont hors de l'écran, au lieu de calculer des milliers de points invisibles.
 */
private class LineSet(
    val xyz: FloatArray,
    val start: IntArray,
    val end: IntArray,
    val cx: FloatArray,
    val cy: FloatArray,
    val cz: FloatArray,
    val radius: FloatArray,
) {
    val count get() = start.size

    /**
     * Remplit [out] avec les segments visibles (x0, y0, x1, y1…), coupés à l'horizon.
     * Les points à moins de [minStep] pixels du précédent sont sautés : le niveau de détail
     * s'adapte tout seul au zoom, et on n'envoie jamais plus de traits que l'écran ne peut en montrer.
     */
    fun visibleSegments(cam: Camera, viewAngle: Float, w: Float, h: Float, minStep: Float, out: FloatBuf) {
        out.n = 0
        val r = cam.r
        for (c in 0 until count) {
            val reach = viewAngle + radius[c]
            if (reach < PI.toFloat() && cx[c] * cam.fx + cy[c] * cam.fy + cz[c] * cam.fz < cos(reach)) continue
            var has = false
            var px = 0f
            var py = 0f
            var i = start[c] * 3
            val last = end[c] * 3
            while (i <= last) {
                val x = xyz[i]; val y = xyz[i + 1]; val z = xyz[i + 2]
                if (x * cam.fx + y * cam.fy + z * cam.fz > 0f) {
                    val sx = cam.cx + r * (x * cam.ex + y * cam.ey)
                    val sy = cam.cy - r * (x * cam.nx + y * cam.ny + z * cam.nz)
                    // Trop près du point précédent pour se voir : on passe (sauf au bout du tronçon,
                    // pour rester raccordé au tronçon voisin).
                    if (has && i < last && abs(sx - px) + abs(sy - py) < minStep) {
                        i += 3
                        continue
                    }
                    // On saute les segments entièrement hors de l'écran, du même côté.
                    if (has && !((px < 0f && sx < 0f) || (px > w && sx > w) || (py < 0f && sy < 0f) || (py > h && sy > h))) {
                        out.add4(px, py, sx, sy)
                    }
                    px = sx; py = sy; has = true
                } else {
                    has = false
                }
                i += 3
            }
        }
    }

    companion object {
        private const val CHUNK = 24
        val EMPTY = LineSet(FloatArray(0), IntArray(0), IntArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0))

        /**
         * Lit un fichier produit par tools/make_land.py à la compilation.
         * Renvoie un ensemble vide si le fichier est absent : le globe marche quand même.
         */
        fun load(context: Context, file: String): LineSet = runCatching {
            val bytes = context.assets.open(file).use { it.readBytes() }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val xyz = FloatArray((bytes.size / 8) * 3)
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            var count = 0
            repeat(buf.int) {
                val n = buf.int
                val first = count
                repeat(n) {
                    val lon = buf.float * DEG
                    val lat = buf.float * DEG
                    xyz[3 * count] = cos(lat) * cos(lon)
                    xyz[3 * count + 1] = cos(lat) * sin(lon)
                    xyz[3 * count + 2] = sin(lat)
                    count++
                }
                // Tronçons de CHUNK segments ; deux tronçons voisins partagent leur point de jonction.
                var s = first
                while (s < first + n - 1) {
                    val e = minOf(s + CHUNK, first + n - 1)
                    starts += s
                    ends += e
                    s = e
                }
            }
            val m = starts.size
            val cx = FloatArray(m); val cy = FloatArray(m); val cz = FloatArray(m); val radius = FloatArray(m)
            for (c in 0 until m) {
                var x = 0f; var y = 0f; var z = 0f
                for (i in starts[c]..ends[c]) { x += xyz[3 * i]; y += xyz[3 * i + 1]; z += xyz[3 * i + 2] }
                val len = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
                x /= len; y /= len; z /= len
                var maxAngle = 0f
                for (i in starts[c]..ends[c]) {
                    val dot = x * xyz[3 * i] + y * xyz[3 * i + 1] + z * xyz[3 * i + 2]
                    maxAngle = maxOf(maxAngle, acos(dot.coerceIn(-1f, 1f)))
                }
                cx[c] = x; cy[c] = y; cz[c] = z; radius[c] = maxAngle
            }
            LineSet(xyz, starts.toIntArray(), ends.toIntArray(), cx, cy, cz, radius)
        }.getOrDefault(EMPTY)
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
    val box = boxes[p.station.countryCode.uppercase()] ?: return true
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
    Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(ACCENTS, "").trim()

private val ACCENTS = Regex("\\p{Mn}+")

/**
 * Index des lieux, construit à partir des stations elles-mêmes (rien à télécharger) : chaque pays
 * et chaque région/ville est placé au centre de ses stations, avec un zoom adapté à leur étendue.
 */
private class PlaceIndex(private val points: List<GeoPoint>) {
    private class Place(val hit: GlobeHit, val key: String, val count: Int)

    private val countries: List<Place>
    private val regions: List<Place>
    private val stationNames: List<String> = points.map { normalizeText(it.station.displayName) }

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
        countries = points.groupBy { it.station.countryCode.uppercase() }
            .filterKeys { it.length == 2 }
            .map { (_, pts) -> place(pts.first().station.countryNameFr, "Pays · ${pts.size} stations", pts) }
            .filter { it.key.isNotBlank() }
        regions = points.filter { it.station.state.isNotBlank() }
            .groupBy { normalizeText(PlaceNames.fr(it.station.state)) + "|" + it.station.countryCode.uppercase() }
            .map { (_, pts) ->
                val st = pts.first().station
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
            val st = p.station
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
    var land by remember { mutableStateOf(LineSet.EMPTY) }
    var landDetailed by remember { mutableStateOf(LineSet.EMPTY) }
    var borders by remember { mutableStateOf(LineSet.EMPTY) }
    var bordersDetailed by remember { mutableStateOf(LineSet.EMPTY) }
    var boxes by remember { mutableStateOf<Map<String, FloatArray>>(emptyMap()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            land = LineSet.load(context, "land.bin")
            borders = LineSet.load(context, "borders.bin")
            boxes = loadCountryBoxes(context)
            landDetailed = LineSet.load(context, "land50.bin")
            bordersDetailed = LineSet.load(context, "borders50.bin")
        }
    }
    // Stations triées de la plus écoutée à la moins écoutée, hors positions douteuses.
    // Préparées en arrière-plan : la liste peut compter des dizaines de milliers de stations.
    var stationPoints by remember { mutableStateOf(StationPoints(emptyList())) }
    LaunchedEffect(globe.stations, boxes) {
        stationPoints = withContext(Dispatchers.Default) {
            StationPoints(globe.stations.map(::GeoPoint).filter { plausible(it, boxes) })
        }
    }
    val points = stationPoints.list
    // Tampons et pinceaux réutilisés à chaque image.
    val lineBuf = remember { FloatBuf(16384) }
    val dotBufs = remember { Array(3) { FloatBuf() } }
    // Traits à bouts droits : bien plus rapides à tracer par milliers que des bouts arrondis.
    val linePaint = remember {
        Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    }
    val dotPaint = remember {
        Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = android.graphics.Color.WHITE }
    }
    val minStepPx = with(LocalDensity.current) { 1.dp.toPx() }
    // Le dégradé de la sphère est recréé seulement quand le zoom ou la taille changent.
    val sphereBrush = remember { BrushCache() }
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

    val selectedPoint = remember(points, selected) { selected?.let { sel -> points.firstOrNull { it.station.uuid == sel.uuid } } }
    val playingUuid = player.station?.uuid
    val playingPoint = remember(points, playingUuid) { points.firstOrNull { it.station.uuid == playingUuid } }

    // Recherche d'un pays, d'une région, d'une ville ou d'une station.
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // L'index des lieux est construit en arrière-plan, à l'ouverture de la recherche.
    var placeIndex by remember { mutableStateOf<PlaceIndex?>(null) }
    LaunchedEffect(points, searchOpen) {
        placeIndex = if (searchOpen) withContext(Dispatchers.Default) { PlaceIndex(points) } else null
    }
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

    // Rotation lente quand on ne touche à rien, seulement vu de loin (de près, elle ferait perdre
    // l'endroit qu'on regarde). Le reste du temps, on ne demande aucune image : rien ne tourne à vide.
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            val canRotate = zoom < 1.5f && selected == null && System.currentTimeMillis() - lastTouch > 4_000
            if (!canRotate) {
                last = 0L
                delay(250)
                continue
            }
            withFrameNanos { now ->
                if (last != 0L) {
                    centerLon += (now - last) / 1e9f * 4f
                    if (centerLon > 180f) centerLon -= 360f
                }
                last = now
            }
        }
    }

    fun radius() = min(canvasSize.width, canvasSize.height) * 0.42f * zoom

    fun camera() = Camera(centerLat, centerLon, radius(), canvasSize.width / 2, canvasSize.height / 2)

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
                        val cam = camera()
                        for (index in 0 until visibleCount(points.size, zoom)) {
                            val p = points[index]
                            val o = cam.project(p) ?: continue
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
            val brush = sphereBrush.get(r, c)
            if (r > hypot(size.width, size.height) / 2) {
                // Le globe déborde de l'écran : un simple rectangle suffit.
                drawRect(brush)
            } else {
                drawCircle(brush = brush, radius = r, center = c)
                drawCircle(Onde.Accent.copy(alpha = 0.35f), radius = r, center = c, style = Stroke(width = 1.dp.toPx()))
            }

            val cam = Camera(centerLat, centerLon, r, c.x, c.y)
            // Angle entre le centre de l'écran et son coin, vu depuis le centre de la Terre.
            val halfDiag = hypot(size.width, size.height) / 2
            val viewAngle = if (halfDiag >= r) (PI / 2).toFloat() else asin(halfDiag / r)
            val detailed = zoom >= DETAIL_ZOOM
            val native = drawContext.canvas.nativeCanvas

            // Frontières (fines et discrètes) puis côtes : carte détaillée quand on zoome, si présente.
            // Chaque tracé part en un seul lot de segments, sans rien allouer.
            fun drawLines(set: LineSet, alpha: Float, width: Float) {
                if (set.count == 0) return
                set.visibleSegments(cam, viewAngle, size.width, size.height, minStepPx, lineBuf)
                if (lineBuf.n == 0) return
                linePaint.color = Onde.Accent.copy(alpha = alpha).toArgb()
                linePaint.strokeWidth = width
                native.drawLines(lineBuf.data, 0, lineBuf.n, linePaint)
            }
            drawLines(if (detailed && bordersDetailed.count > 0) bordersDetailed else borders, 0.30f, 0.8.dp.toPx())
            drawLines(if (detailed && landDetailed.count > 0) landDetailed else land, 0.55f, 1.2.dp.toPx())

            // Les stations : seulement les plus écoutées quand on est loin, de plus en plus quand on
            // zoome. Ce choix ne dépend que du zoom (pas de la position à l'écran) : rien ne clignote
            // quand la Terre tourne. Les plus écoutées sont plus grosses ; près du bord, plus discrètes.
            val xyz = stationPoints.xyz
            val count = visibleCount(points.size, zoom)
            dotBufs.forEach { it.n = 0 }
            val margin = bigDotPx
            for (index in 0 until count) {
                val px = xyz[3 * index]; val py = xyz[3 * index + 1]; val pz = xyz[3 * index + 2]
                val depth = px * cam.fx + py * cam.fy + pz * cam.fz
                if (depth <= 0f) continue
                val x = c.x + r * (px * cam.ex + py * cam.ey)
                val y = c.y - r * (px * cam.nx + py * cam.ny + pz * cam.nz)
                if (x < -margin || y < -margin || x > size.width + margin || y > size.height + margin) continue
                val sizeClass = if (index < 40) 2 else if (index < 250) 1 else 0
                if (depth >= 0.5f) {
                    // Cas courant : opaque, envoyé par lot avec les autres points de même taille.
                    dotBufs[sizeClass].add2(x, y)
                } else {
                    // Près du bord du globe : fondu continu (pas de palier qui saute).
                    val dot = if (sizeClass == 2) bigDotPx else if (sizeClass == 1) midDotPx else dotPx
                    drawCircle(Color.White.copy(alpha = 0.25f + 0.75f * (depth / 0.5f)), radius = dot / 2, center = Offset(x, y))
                }
            }
            for (k in 0..2) {
                if (dotBufs[k].n == 0) continue
                dotPaint.strokeWidth = if (k == 2) bigDotPx else if (k == 1) midDotPx else dotPx
                native.drawPoints(dotBufs[k].data, 0, dotBufs[k].n, dotPaint)
            }

            // Station touchée : un anneau et, en petit, la ville et le pays à côté du point.
            selected?.let { sel ->
                val sp = selectedPoint ?: return@let
                val o = cam.project(sp) ?: return@let
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
            playingPoint?.let { p ->
                cam.project(p)?.let { o ->
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
