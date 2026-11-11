package app.onde.radio.player

import kotlinx.coroutines.CompletableDeferred
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/** Un changement de titre repéré dans le flux (métadonnées ICY). */
data class TrackMark(
    val raw: String,
    val artist: String,
    val title: String,
    val absPos: Long,
    val timeMs: Long,
    var historyId: Long = 0L,
)

/**
 * Reçoit le flux d'une station dans un [RingBuffer] et repère les titres.
 *
 * Le lecteur lit ce tampon plutôt que le réseau : c'est ce qui permet de mettre le direct
 * en pause. Par défaut le tampon est en mémoire et vidé au fur et à mesure de l'écoute ;
 * il ne garde 30 min (sur disque) que si l'option « Retour en arrière » est activée.
 */
class StreamRecorder(
    private val client: OkHttpClient,
    private val url: String,
    val buffer: RingBuffer,
    private val bitrateHintKbps: Int,
    private val onTrack: (TrackMark) -> Unit,
) {
    val marks = CopyOnWriteArrayList<TrackMark>()

    /** Dernière position lue par le lecteur (rien avant ne peut être effacé sans risque). */
    @Volatile var readPos: Long = 0L

    @Volatile var contentType: String? = null
        private set

    private val ready = CompletableDeferred<Unit>()
    @Volatile private var stopped = false
    @Volatile private var call: Call? = null
    private var thread: Thread? = null

    // Estimation du débit, pour convertir octets <-> millisecondes.
    @Volatile private var headerBitrateKbps = 0
    private var measureStartMs = 0L
    private var measureStartBytes = 0L
    @Volatile private var measuredBytesPerSec = 0.0

    val bytesPerSecond: Double
        get() = when {
            measuredBytesPerSec > 0 -> measuredBytesPerSec
            headerBitrateKbps > 0 -> headerBitrateKbps * 125.0
            bitrateHintKbps > 0 -> bitrateHintKbps * 125.0
            else -> 16_000.0 // 128 kbit/s par défaut
        }

    /** Les flux Ogg (Vorbis/Opus) ne peuvent pas être relus depuis le milieu. */
    val isSeekable: Boolean
        get() = contentType?.contains("ogg", ignoreCase = true) != true

    fun start() {
        thread = Thread({ runLoop() }, "onde-recorder").apply {
            isDaemon = true
            start()
        }
    }

    suspend fun awaitReady() = ready.await()

    fun stop() {
        stopped = true
        call?.cancel()
        thread?.interrupt()
        buffer.finish()
    }

    fun release() {
        stop()
        buffer.close()
    }

    private fun runLoop() {
        var failures = 0
        while (!stopped) {
            try {
                connectAndPump()
                failures = 0
            } catch (e: IOException) {
                if (stopped) break
                failures++
                if (!ready.isCompleted && failures >= 3) {
                    ready.completeExceptionally(e)
                    break
                }
                if (failures > 20) break
                try {
                    Thread.sleep(minOf(1000L * failures, 5000L))
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        buffer.finish()
    }

    private fun connectAndPump() {
        val request = Request.Builder()
            .url(url)
            .header("Icy-MetaData", "1")
            .header("User-Agent", USER_AGENT)
            .build()
        val c = client.newCall(request)
        call = c
        c.execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val type = response.header("Content-Type").orEmpty()
            if (type.contains("text/html", true) || type.contains("mpegurl", true)) {
                throw IOException("Ce n'est pas un flux audio ($type)")
            }
            contentType = type
            headerBitrateKbps = response.header("icy-br")
                ?.substringBefore(',')?.trim()?.toIntOrNull() ?: 0
            val metaInt = response.header("icy-metaint")?.trim()?.toIntOrNull() ?: 0
            ready.complete(Unit)
            pump(response.body!!.byteStream(), metaInt)
        }
    }

    private fun pump(input: InputStream, metaInt: Int) {
        val buf = ByteArray(16 * 1024)
        var untilMeta = metaInt
        while (!stopped) {
            val want = if (metaInt > 0) minOf(buf.size, untilMeta) else buf.size
            val n = input.read(buf, 0, want)
            if (n < 0) throw EOFException()
            buffer.write(buf, 0, n)
            updateRate()
            if (metaInt > 0) {
                untilMeta -= n
                if (untilMeta == 0) {
                    readMetadata(input)
                    untilMeta = metaInt
                }
            }
        }
    }

    private fun updateRate() {
        val now = System.currentTimeMillis()
        if (measureStartMs == 0L) {
            // On ignore les 10 premières secondes : le serveur envoie d'abord une rafale.
            measureStartMs = now + 10_000
            return
        }
        if (now < measureStartMs) {
            measureStartBytes = buffer.written
            return
        }
        val elapsed = now - measureStartMs
        if (elapsed >= 20_000) {
            measuredBytesPerSec = (buffer.written - measureStartBytes) * 1000.0 / elapsed
        }
    }

    private fun readMetadata(input: InputStream) {
        val lenByte = input.read()
        if (lenByte < 0) throw EOFException()
        val len = lenByte * 16
        if (len == 0) return
        val meta = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(meta, read, len - read)
            if (n < 0) throw EOFException()
            read += n
        }
        val text = decode(meta)
        val streamTitle = parseStreamTitle(text) ?: return
        if (streamTitle.isBlank() || streamTitle == marks.lastOrNull()?.raw) return
        val (artist, title) = splitTitle(streamTitle)
        val mark = TrackMark(
            raw = streamTitle,
            artist = artist,
            title = title,
            absPos = buffer.written,
            timeMs = System.currentTimeMillis(),
        )
        marks.add(mark)
        onTrack(mark)
    }

    companion object {
        const val USER_AGENT = "RadioOnde/0.1 (Android)"

        private fun decode(bytes: ByteArray): String {
            val end = bytes.indexOfFirst { it == 0.toByte() }.let { if (it < 0) bytes.size else it }
            val utf8 = String(bytes, 0, end, StandardCharsets.UTF_8)
            return if (utf8.contains('�')) String(bytes, 0, end, StandardCharsets.ISO_8859_1) else utf8
        }

        fun parseStreamTitle(meta: String): String? {
            val key = "StreamTitle='"
            val start = meta.indexOf(key)
            if (start < 0) return null
            val from = start + key.length
            val end = meta.indexOf("';", from).let { if (it < 0) meta.lastIndexOf('\'') else it }
            if (end < from) return null
            return meta.substring(from, end).trim()
        }

        fun splitTitle(raw: String): Pair<String, String> {
            val i = raw.indexOf(" - ")
            return if (i > 0) raw.substring(0, i).trim() to raw.substring(i + 3).trim()
            else "" to raw.trim()
        }
    }
}
