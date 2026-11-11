package app.onde.radio.player

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.onde.radio.data.HistoryEntry
import app.onde.radio.data.LibraryStore
import app.onde.radio.data.RadioBrowserApi
import app.onde.radio.data.Station
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class PlayerUiState(
    val station: Station? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** Retard par rapport au direct, en millisecondes. */
    val delayMs: Long = 0,
    /** Durée disponible dans le tampon, en millisecondes. */
    val bufferMs: Long = 0,
    /** Reculer, réécouter et enregistrer : option « Retour en arrière » activée et flux compatible. */
    val canRewind: Boolean = false,
    /** Avancer vers le direct (après une pause, par exemple). */
    val canSeekForward: Boolean = false,
    val current: TrackMark? = null,
    val previous: TrackMark? = null,
    /** Durée de l'enregistrement en cours, ou -1. */
    val recordingMs: Long = -1,
    val message: String? = null,
) {
    val isLive: Boolean get() = delayMs < PlaybackEngine.LIVE_THRESHOLD_MS
    val isRecording: Boolean get() = recordingMs >= 0
}

/**
 * Le cœur de l'appli : un ExoPlayer qui lit le tampon local alimenté par [StreamRecorder].
 * Une seule instance, créée par [app.onde.radio.OndeApp] et partagée avec [PlaybackService].
 */
@OptIn(UnstableApi::class)
class PlaybackEngine(
    private val context: Context,
    http: OkHttpClient,
    private val api: RadioBrowserApi,
    private val library: LibraryStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val streamClient = http.newBuilder()
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(StreamRecorder.USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)

    // Lus depuis le thread de chargement d'ExoPlayer.
    @Volatile private var recorder: StreamRecorder? = null
    @Volatile private var session = 0
    private var startAbs = 0L
    private var direct = false
    /** Option « Retour en arrière » active pour la station en cours (tampon de 30 min sur disque). */
    private var rewindMode = false
    private val directMarks = mutableListOf<TrackMark>()
    private var lastNotifiedMark: TrackMark? = null
    private var recordStartAbs = -1L
    private var listenedMs = 0L
    private var lastHistoryId = 0L
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val dataSourceFactory = DataSource.Factory {
        RoutingDataSource(httpFactory.createDataSource()) { spec ->
            val rec = recorder
            if (rec != null && spec.uri.host == session.toString()) {
                TimeShiftDataSource(rec) { minReadable(rec) }
            } else null
        }
    }

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    init {
        // Un fichier tampon resté après un arrêt brutal de l'appli est effacé au démarrage.
        bufferFile().delete()
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = refresh()

            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(message = "Lecture impossible (${error.errorCodeName})") }
            }

            override fun onMetadata(metadata: Metadata) {
                if (!direct) return
                for (i in 0 until metadata.length()) {
                    val entry = metadata.get(i)
                    if (entry is IcyInfo) onDirectTitle(entry.title)
                }
            }
        })
        scope.launch {
            while (true) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    // ---------------------------------------------------------------- Commandes

    fun play(station: Station) {
        ensureServiceConnected()
        scope.launch {
            if (recordStartAbs >= 0) finishRecording()
            player.stop()
            player.clearMediaItems()
            releaseRecorder()
            session++
            val mySession = session
            direct = false
            directMarks.clear()
            lastNotifiedMark = null
            startAbs = 0L
            _state.value = PlayerUiState(station = station, isBuffering = true)
            library.onStationPlayed(station)
            scope.launch { api.countClick(station.uuid) }

            if (station.isHls) {
                startDirect(station)
                return@launch
            }

            // Par défaut : petit tampon en mémoire, vidé au fil de l'écoute (sert seulement à la pause).
            // Option « Retour en arrière » : 30 min dans un fichier temporaire, effacé à l'arrêt.
            rewindMode = library.data.value.timeShiftEnabled
            val buffer = if (rewindMode) RingBuffer.onDisk(bufferFile(), BUFFER_BYTES)
            else RingBuffer.inMemory(PAUSE_BUFFER_BYTES)
            val rec = StreamRecorder(
                client = streamClient,
                url = station.streamUrl,
                buffer = buffer,
                bitrateHintKbps = station.bitrate,
            ) { mark -> scope.launch { onTrackMark(station, mark) } }
            recorder = rec
            rec.start()

            val ok = withTimeoutOrNull(12_000) { runCatching { rec.awaitReady() }.isSuccess } == true
            if (mySession != session) return@launch
            if (!ok) {
                // Flux que l'on ne sait pas enregistrer : on le lit directement, sans retour en arrière.
                releaseRecorder()
                startDirect(station)
                return@launch
            }
            prepareAt(0L)
        }
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
            return
        }
        val station = _state.value.station ?: return
        if (player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 0) {
            play(station)
        } else {
            player.play()
        }
    }

    /** Recule (valeur négative) ou avance (valeur positive) dans le tampon. */
    fun seekBy(deltaMs: Long) {
        seekToDelay((_state.value.delayMs - deltaMs).coerceAtLeast(0))
    }

    fun goLive() {
        if (direct) {
            player.seekToDefaultPosition()
            player.play()
        } else {
            seekToDelay(0)
        }
    }

    fun seekToDelay(delayMs: Long) {
        val rec = recorder ?: return
        if (direct || !rec.isSeekable) return
        val bps = rec.bytesPerSecond
        val headroom = (LIVE_HEADROOM_MS / 1000.0 * bps).toLong()
        val min = minReadable(rec)
        val max = maxOf(min, rec.buffer.written - headroom)
        val target = (rec.buffer.written - delayMs / 1000.0 * bps).toLong().coerceIn(min, max)
        prepareAt(target)
    }

    /** Active ou désactive l'option « Retour en arrière » ; relance la station en cours pour l'appliquer. */
    fun setTimeShiftEnabled(enabled: Boolean) {
        if (library.data.value.timeShiftEnabled == enabled) return
        library.setTimeShiftEnabled(enabled)
        val station = _state.value.station
        if (station != null && !direct) play(station)
    }

    fun canReplay(historyId: Long): Boolean {
        val rec = recorder ?: return false
        if (direct || !rewindMode || !rec.isSeekable) return false
        val mark = rec.marks.firstOrNull { it.historyId == historyId } ?: return false
        return mark.absPos >= minReadable(rec)
    }

    fun replay(historyId: Long) {
        val rec = recorder ?: return
        if (!canReplay(historyId)) return
        val mark = rec.marks.firstOrNull { it.historyId == historyId } ?: return
        prepareAt(mark.absPos)
    }

    fun toggleRecording() {
        val rec = recorder
        if (!rewindMode && recordStartAbs < 0) {
            _state.update { it.copy(message = "Active « Retour en arrière » dans les réglages pour enregistrer") }
            return
        }
        if (rec == null || direct) {
            _state.update { it.copy(message = "L'enregistrement n'est pas disponible pour cette station") }
            return
        }
        if (recordStartAbs < 0) {
            recordStartAbs = playAbs(rec)
            refresh()
        } else {
            scope.launch { finishRecording() }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    // ---------------------------------------------------------------- Interne

    private fun ensureServiceConnected() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, token).buildAsync()
    }

    private fun prepareAt(abs: Long) {
        val station = _state.value.station ?: return
        startAbs = abs
        val mark = recorder?.marks?.lastOrNull { it.absPos <= abs }
        lastNotifiedMark = mark
        val item = MediaItem.Builder()
            .setMediaId(station.uuid)
            .setUri("${RoutingDataSource.SCHEME}://$session?from=$abs")
            .setMediaMetadata(metadataFor(station, mark))
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    private fun startDirect(station: Station) {
        direct = true
        val builder = MediaItem.Builder()
            .setMediaId(station.uuid)
            .setUri(station.streamUrl)
            .setMediaMetadata(metadataFor(station, null))
        if (station.isHls) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        player.setMediaItem(builder.build())
        player.prepare()
        player.play()
    }

    private fun minReadable(rec: StreamRecorder): Long {
        if (!rewindMode) return rec.buffer.oldest
        val capByTime = rec.buffer.written - (MAX_SHIFT_MS / 1000.0 * rec.bytesPerSecond).toLong()
        return maxOf(rec.buffer.oldest, capByTime, 0L)
    }

    private fun bufferFile() = File(context.cacheDir, "timeshift.buf")

    private fun playAbs(rec: StreamRecorder): Long =
        startAbs + (player.currentPosition.coerceAtLeast(0) * rec.bytesPerSecond / 1000.0).toLong()

    private fun releaseRecorder() {
        recorder?.release()
        recorder = null
    }

    private fun nextHistoryId(): Long {
        lastHistoryId = maxOf(System.currentTimeMillis(), lastHistoryId + 1)
        return lastHistoryId
    }

    private fun onTrackMark(station: Station, mark: TrackMark) {
        if (_state.value.station?.uuid != station.uuid) return
        val id = nextHistoryId()
        mark.historyId = id
        library.addHistory(
            HistoryEntry(id, mark.title, mark.artist, station.uuid, station.displayName, mark.timeMs)
        )
    }

    private fun onDirectTitle(raw: String?) {
        val station = _state.value.station ?: return
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == directMarks.lastOrNull()?.raw) return
        val (artist, title) = StreamRecorder.splitTitle(text)
        val mark = TrackMark(text, artist, title, -1, System.currentTimeMillis())
        directMarks += mark
        onTrackMark(station, mark)
    }

    private fun metadataFor(station: Station, mark: TrackMark?): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(mark?.title?.takeIf { it.isNotBlank() } ?: station.displayName)
            .setArtist(
                if (mark != null) listOf(mark.artist, station.displayName).filter { it.isNotBlank() }.joinToString(" · ")
                else station.subtitle
            )
            .setStation(station.displayName)
            .setArtworkUri(station.favicon.takeIf { it.startsWith("http") }?.let(Uri::parse))
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()

    private fun tick() {
        if (player.isPlaying) {
            listenedMs += TICK_MS
            if (listenedMs >= 60_000) {
                listenedMs = 0
                _state.value.station?.let { library.addListeningMinutes(it, 1.0) }
            }
            // Sans l'option « Retour en arrière », on oublie au fur et à mesure ce qui a été écouté :
            // le tampon ne grossit que pendant une pause.
            val rec = recorder
            if (rec != null && !rewindMode && !direct) {
                val margin = (DISCARD_MARGIN_MS / 1000.0 * rec.bytesPerSecond).toLong()
                rec.buffer.discardBefore(minOf(playAbs(rec) - margin, rec.readPos))
            }
        }
        refresh()
    }

    private fun refresh() {
        val station = _state.value.station
        val rec = recorder
        var delayMs = 0L
        var bufferMs = 0L
        var current: TrackMark? = null
        var previous: TrackMark? = null
        var recordingMs = -1L

        if (rec != null && !direct) {
            val bps = rec.bytesPerSecond
            val pa = playAbs(rec)
            delayMs = ((rec.buffer.written - pa) * 1000 / bps).toLong().coerceAtLeast(0)
            bufferMs = ((rec.buffer.written - minReadable(rec)) * 1000 / bps).toLong().coerceAtLeast(0)
            val marks = rec.marks
            val idx = marks.indexOfLast { it.absPos <= pa }
            current = marks.getOrNull(idx)
            previous = marks.getOrNull(idx - 1)
            if (recordStartAbs >= 0) recordingMs = ((pa - recordStartAbs) * 1000 / bps).toLong().coerceAtLeast(0)

            // Le titre affiché dans la notification suit la position d'écoute, même en différé.
            if (station != null && current != null && current !== lastNotifiedMark && player.mediaItemCount > 0) {
                lastNotifiedMark = current
                player.currentMediaItem?.let { item ->
                    player.replaceMediaItem(0, item.buildUpon().setMediaMetadata(metadataFor(station, current)).build())
                }
            }
        } else if (direct) {
            current = directMarks.lastOrNull()
            previous = directMarks.getOrNull(directMarks.size - 2)
        }

        _state.update {
            it.copy(
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING ||
                    (station != null && player.mediaItemCount == 0 && it.isBuffering),
                delayMs = delayMs,
                bufferMs = bufferMs,
                canRewind = rewindMode && rec != null && !direct && rec.isSeekable,
                canSeekForward = rec != null && !direct && rec.isSeekable &&
                    delayMs >= LIVE_THRESHOLD_MS,
                current = current,
                previous = previous,
                recordingMs = recordingMs,
            )
        }
    }

    private suspend fun finishRecording() {
        val rec = recorder ?: return
        val station = _state.value.station ?: return
        val from = recordStartAbs
        val to = playAbs(rec)
        recordStartAbs = -1
        if (to <= from) return
        val result = withContext(Dispatchers.IO) {
            runCatching { saveRecording(rec, station, from, to) }
        }
        _state.update {
            it.copy(
                message = if (result.isSuccess) "Enregistré dans Musique/Radio Onde"
                else "Échec de l'enregistrement : ${result.exceptionOrNull()?.message}",
            )
        }
    }

    private fun saveRecording(rec: StreamRecorder, station: Station, from: Long, to: Long) {
        val type = rec.contentType.orEmpty().lowercase()
        val (ext, mime) = when {
            "aac" in type -> "aac" to "audio/aac"
            "ogg" in type -> "ogg" to "audio/ogg"
            else -> "mp3" to "audio/mpeg"
        }
        val date = SimpleDateFormat("yyyy-MM-dd HH'h'mm", Locale.FRANCE).format(Date())
        val safeName = station.displayName.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim()
        val fileName = "$safeName $date.$ext"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Radio Onde")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Impossible de créer le fichier")
            resolver.openOutputStream(uri)?.use { rec.buffer.copyTo(it, from, to) }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Radio Onde").apply { mkdirs() }
            File(dir, fileName).outputStream().use { rec.buffer.copyTo(it, from, to) }
        }
    }

    companion object {
        const val TICK_MS = 500L
        /** Durée maximale de retour en arrière. */
        const val MAX_SHIFT_MS = 30 * 60 * 1000L
        /** Taille du fichier tampon (option activée) : 30 min à 320 kbit/s, avec de la marge. */
        const val BUFFER_BYTES = 80L * 1024 * 1024
        /** Tampon de pause en mémoire (option désactivée) : environ 25 min à 128 kbit/s. */
        const val PAUSE_BUFFER_BYTES = 24 * 1024 * 1024
        /** Ce qu'on garde derrière la position d'écoute quand l'option est désactivée. */
        const val DISCARD_MARGIN_MS = 5_000L
        /** En dessous de ce retard, on considère qu'on est en direct. */
        const val LIVE_THRESHOLD_MS = 15_000L
        /** Marge laissée au lecteur quand on revient au direct. */
        const val LIVE_HEADROOM_MS = 3_000L
    }
}
