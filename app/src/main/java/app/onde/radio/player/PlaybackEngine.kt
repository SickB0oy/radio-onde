package app.onde.radio.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.onde.radio.data.HistoryEntry
import app.onde.radio.data.LibraryStore
import app.onde.radio.data.RadioBrowserApi
import app.onde.radio.data.Station
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

data class PlayerUiState(
    val station: Station? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** Retard par rapport au direct, en millisecondes. */
    val delayMs: Long = 0,
    /** Durée disponible dans le tampon, en millisecondes. */
    val bufferMs: Long = 0,
    val current: TrackMark? = null,
    val previous: TrackMark? = null,
    val message: String? = null,
    /** Heure d'arrêt du minuteur de veille (0 = pas de minuteur). */
    val sleepEndMs: Long = 0,
) {
    val isLive: Boolean get() = delayMs < PlaybackEngine.LIVE_THRESHOLD_MS
}

/**
 * Le cœur de l'appli : un ExoPlayer qui lit le tampon en mémoire alimenté par [StreamRecorder].
 * Pendant l'écoute, ce qui a été joué est oublié aussitôt ; le tampon ne grossit que pendant une pause.
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
    /**
     * Retard « normal » de la station : au branchement, le serveur envoie d'un coup quelques
     * secondes déjà diffusées. Ce retard est le même dans toutes les applis radio ; on l'appelle
     * « direct » et on n'affiche que le retard en plus (pause, etc.).
     */
    private var liveFloorMs = Long.MAX_VALUE
    private var playingSinceStartMs = 0L
    private var direct = false
    private val directMarks = mutableListOf<TrackMark>()
    private val directCandidates = ArrayDeque<Pair<String, Boolean>>()
    private var lastNotifiedMark: TrackMark? = null
    private var listenedMs = 0L
    private var lastHistoryId = 0L
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val dataSourceFactory = DataSource.Factory {
        RoutingDataSource(httpFactory.createDataSource()) { spec ->
            val rec = recorder
            if (rec != null && spec.uri.host == session.toString()) {
                TimeShiftDataSource(rec) { rec.buffer.oldest }
            } else null
        }
    }

    /** Volume égalisé entre les stations. */
    private val leveler = LevelingAudioProcessor().apply { enabled = library.data.value.volumeLeveling }

    private val renderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(leveler))
            .build()
    }

    private var sleepJob: Job? = null
    private var fadeInJob: Job? = null

    val player: ExoPlayer = ExoPlayer.Builder(context, renderersFactory)
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
        // Fichier laissé par les anciennes versions (option « Retour en arrière », retirée).
        File(context.cacheDir, "timeshift.buf").delete()
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = refresh()

            override fun onPlayerError(error: PlaybackException) {
                val station = _state.value.station ?: return
                // Si la lecture via le tampon échoue, on essaie de lire le flux directement,
                // puis l'adresse d'origine de la station, avant d'abandonner.
                if (!direct) releaseRecorder()
                if (!startNextDirect(station)) {
                    _state.update {
                        it.copy(isBuffering = false, message = "Cette station ne répond pas (${error.errorCodeName})")
                    }
                }
            }

            override fun onMetadata(metadata: Metadata) {
                if (!direct) return
                // Flux classiques : métadonnées ICY. Flux HLS : balises ID3 (TIT2 = titre, TPE1 = artiste).
                var id3Title: String? = null
                var id3Artist: String? = null
                for (i in 0 until metadata.length()) {
                    when (val entry = metadata.get(i)) {
                        is IcyInfo -> onDirectTitle(entry.title)
                        is TextInformationFrame -> when (entry.id) {
                            "TIT2" -> id3Title = entry.values.firstOrNull()
                            "TPE1" -> id3Artist = entry.values.firstOrNull()
                        }
                    }
                }
                if (!id3Title.isNullOrBlank()) {
                    onDirectTitle(listOfNotNull(id3Artist?.takeIf { it.isNotBlank() }, id3Title).joinToString(" - "))
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
            player.stop()
            player.clearMediaItems()
            releaseRecorder()
            session++
            val mySession = session
            direct = false
            directMarks.clear()
            lastNotifiedMark = null
            startAbs = 0L
            liveFloorMs = Long.MAX_VALUE
            playingSinceStartMs = 0L
            _state.value = PlayerUiState(station = station, isBuffering = true, sleepEndMs = _state.value.sleepEndMs)
            library.onStationPlayed(station)
            scope.launch { api.countClick(station.uuid) }

            // Adresses de secours pour une lecture directe, dans l'ordre.
            directCandidates.clear()
            directCandidates += station.streamUrl to station.isHls
            if (station.url.isNotBlank() && station.url != station.streamUrl) {
                directCandidates += station.url to StreamOpener.isHlsUrl(station.url)
            }

            if (station.isHls) {
                startNextDirect(station)
                return@launch
            }

            // Tampon en mémoire, vidé au fil de l'écoute : il ne sert qu'à la pause du direct.
            val buffer = RingBuffer(PAUSE_BUFFER_BYTES)
            val rec = StreamRecorder(
                client = streamClient,
                url = station.streamUrl,
                buffer = buffer,
                bitrateHintKbps = station.bitrate,
                parseTitle = { TitleFilter.parse(it, station.displayName) },
            ) { mark -> scope.launch { onTrackMark(station, mark) } }
            recorder = rec
            rec.start()

            val result = withTimeoutOrNull(15_000) { runCatching { rec.awaitReady() } }
            if (mySession != session) return@launch
            if (result == null || result.isFailure) {
                // Flux que l'on ne sait pas mettre en tampon : on le lit directement, sans pause du direct.
                val error = result?.exceptionOrNull()
                if (error is HlsStreamException) directCandidates.addFirst(error.url to true)
                releaseRecorder()
                if (!startNextDirect(station)) {
                    _state.update { it.copy(isBuffering = false, message = "Cette station ne répond pas") }
                }
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

    fun goLive() {
        if (direct) {
            player.seekToDefaultPosition()
            player.play()
        } else {
            seekToDelay(0)
        }
    }

    private fun seekToDelay(delayMs: Long) {
        val rec = recorder ?: return
        if (direct || !rec.isSeekable) return
        val bps = rec.bytesPerSecond
        val headroom = (LIVE_HEADROOM_MS / 1000.0 * bps).toLong()
        val min = rec.buffer.oldest
        val max = maxOf(min, rec.buffer.written - headroom)
        val floor = if (liveFloorMs == Long.MAX_VALUE) 0L else liveFloorMs
        val target = (rec.buffer.written - (delayMs + floor) / 1000.0 * bps).toLong().coerceIn(min, max)
        prepareAt(target)
    }

    fun setVolumeLeveling(enabled: Boolean) {
        leveler.enabled = enabled
        library.setVolumeLeveling(enabled)
    }

    /** Minuteur de veille : baisse le son pendant 30 s puis met en pause. null = annuler. */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        player.volume = 1f
        if (minutes == null) {
            _state.update { it.copy(sleepEndMs = 0) }
            return
        }
        val end = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleepEndMs = end) }
        sleepJob = scope.launch {
            delay((end - System.currentTimeMillis() - SLEEP_FADE_MS).coerceAtLeast(0))
            val steps = 30
            for (i in 1..steps) {
                player.volume = 1f - i.toFloat() / steps
                delay(SLEEP_FADE_MS / steps)
            }
            player.pause()
            player.volume = 1f
            _state.update { it.copy(sleepEndMs = 0, message = "Minuteur terminé, bonne nuit !") }
        }
    }

    /** Réveil : lance la station avec un son qui monte doucement pendant une minute. */
    fun playForAlarm(station: Station) {
        fadeInJob?.cancel()
        player.volume = 0.1f
        play(station)
        fadeInJob = scope.launch {
            val steps = 30
            for (i in 1..steps) {
                delay(2_000)
                player.volume = (0.1f + 0.9f * i / steps).coerceAtMost(1f)
            }
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
        val shown = mark?.takeIf { it.isTrack }
        val item = MediaItem.Builder()
            .setMediaId(station.uuid)
            .setUri("${RoutingDataSource.SCHEME}://$session?from=$abs")
            .setMediaMetadata(metadataFor(station, shown))
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    /** Lance la prochaine adresse de secours ; renvoie false s'il n'y en a plus. */
    private fun startNextDirect(station: Station): Boolean {
        val (url, hls) = directCandidates.removeFirstOrNull() ?: return false
        direct = true
        val builder = MediaItem.Builder()
            .setMediaId(station.uuid)
            .setUri(url)
            .setMediaMetadata(metadataFor(station, null))
        if (hls) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        player.setMediaItem(builder.build())
        player.prepare()
        player.play()
        return true
    }

    /** Ce qu'ExoPlayer a déjà chargé mais pas encore joué, en millisecondes. */
    private fun bufferedAheadMs(): Long =
        (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)

    /**
     * Position d'écoute dans le flux (en octets) : ce qui a été lu, moins ce qui attend dans le
     * lecteur. Plus fiable que de multiplier la durée écoutée par un débit estimé.
     */
    private fun playAbs(rec: StreamRecorder): Long =
        maxOf(startAbs, rec.readPos - (bufferedAheadMs() * rec.bytesPerSecond / 1000.0).toLong())

    /** Retard total sur ce que le serveur envoie, en millisecondes. */
    private fun rawDelayMs(rec: StreamRecorder): Long {
        val unreadMs = ((rec.buffer.written - rec.readPos) * 1000 / rec.bytesPerSecond).toLong().coerceAtLeast(0)
        return unreadMs + bufferedAheadMs()
    }

    private fun releaseRecorder() {
        recorder?.release()
        recorder = null
    }

    private fun nextHistoryId(): Long {
        lastHistoryId = maxOf(System.currentTimeMillis(), lastHistoryId + 1)
        return lastHistoryId
    }

    private fun onTrackMark(station: Station, mark: TrackMark) {
        if (!mark.isTrack || _state.value.station?.uuid != station.uuid) return
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
        val parsed = TitleFilter.parse(text, station.displayName)
        val mark = TrackMark(
            raw = text,
            artist = parsed?.first.orEmpty(),
            title = parsed?.second.orEmpty(),
            absPos = -1,
            timeMs = System.currentTimeMillis(),
            isTrack = parsed != null,
        )
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
            playingSinceStartMs += TICK_MS
            if (listenedMs >= 60_000) {
                listenedMs = 0
                _state.value.station?.let { library.addListeningMinutes(it, 1.0) }
            }
            // On oublie au fur et à mesure ce qui a été écouté : le tampon ne grossit que pendant une pause.
            val rec = recorder
            if (rec != null && !direct) {
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

        if (rec != null && !direct) {
            val bps = rec.bytesPerSecond
            val pa = playAbs(rec)
            val raw = rawDelayMs(rec)
            // Le plus petit retard constaté en écoute normale = le « direct » de cette station.
            if (player.isPlaying && playingSinceStartMs >= 3_000) liveFloorMs = minOf(liveFloorMs, raw)
            delayMs = if (liveFloorMs == Long.MAX_VALUE) 0 else (raw - liveFloorMs).coerceAtLeast(0)
            bufferMs = ((rec.buffer.written - rec.buffer.oldest) * 1000 / bps).toLong().coerceAtLeast(0)
            val marks = rec.marks
            val idx = marks.indexOfLast { it.absPos <= pa }
            current = marks.getOrNull(idx)?.takeIf { it.isTrack }
            previous = (idx - 1 downTo 0).asSequence().map { marks[it] }.firstOrNull { it.isTrack }

            // Le titre affiché dans la notification suit la position d'écoute, même en différé.
            val shownMark = marks.getOrNull(idx)
            if (station != null && shownMark != null && shownMark !== lastNotifiedMark && player.mediaItemCount > 0) {
                lastNotifiedMark = shownMark
                player.currentMediaItem?.let { item ->
                    player.replaceMediaItem(0, item.buildUpon().setMediaMetadata(metadataFor(station, current)).build())
                }
            }
        } else if (direct) {
            current = directMarks.lastOrNull()?.takeIf { it.isTrack }
            previous = directMarks.dropLast(1).lastOrNull { it.isTrack }
        }

        _state.update {
            it.copy(
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING ||
                    (station != null && player.mediaItemCount == 0 && it.isBuffering),
                delayMs = delayMs,
                bufferMs = bufferMs,
                current = current,
                previous = previous,
            )
        }
    }

    companion object {
        const val TICK_MS = 500L
        const val SLEEP_FADE_MS = 30_000L
        /** Tampon de pause en mémoire : environ 25 min à 128 kbit/s. */
        const val PAUSE_BUFFER_BYTES = 24 * 1024 * 1024
        /** Ce qu'on garde derrière la position d'écoute quand l'option est désactivée. */
        const val DISCARD_MARGIN_MS = 5_000L
        /** En dessous de ce retard, on considère qu'on est en direct. */
        const val LIVE_THRESHOLD_MS = 15_000L
        /** Marge laissée au lecteur quand on revient au direct. */
        const val LIVE_HEADROOM_MS = 3_000L
    }
}
