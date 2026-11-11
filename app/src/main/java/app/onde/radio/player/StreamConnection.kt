package app.onde.radio.player

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Une station qui renvoie vers un flux HLS : on ne sait pas le mettre en tampon, on le lit directement. */
class HlsStreamException(val url: String) : IOException("Flux HLS : $url")

/** Une connexion ouverte vers un flux audio : en-têtes (en minuscules) et données. */
class StreamConnection(
    val url: String,
    private val headers: Map<String, String>,
    val body: InputStream,
    private val onClose: () -> Unit,
) : Closeable {
    fun header(name: String): String? = headers[name.lowercase()]
    override fun close() = onClose()
}

/**
 * Ouvre un flux radio en gérant les cas qui font échouer les lecteurs classiques :
 * - les vieux serveurs SHOUTcast qui répondent « ICY 200 OK » au lieu de « HTTP/1.1 200 OK » ;
 * - les listes de lecture (.pls, .m3u, .asx, .xspf) qui pointent vers le vrai flux ;
 * - les redirections.
 */
class StreamOpener(private val client: OkHttpClient) {

    @Volatile private var call: Call? = null
    @Volatile private var socket: Socket? = null

    /** Interrompt une ouverture ou une lecture en cours (depuis un autre thread). */
    fun cancel() {
        call?.cancel()
        runCatching { socket?.close() }
    }

    fun open(startUrl: String): StreamConnection {
        var url = startUrl
        repeat(MAX_HOPS) {
            val conn = openOnce(url)
            val type = conn.header("content-type").orEmpty().lowercase()
            if (!isPlaylist(type, url)) {
                if (type.contains("text/html")) {
                    conn.close()
                    throw IOException("La station renvoie une page web au lieu d'un flux audio")
                }
                return conn
            }
            // Liste de lecture : on lit son contenu et on suit la première adresse.
            val text = conn.use { readLimited(it.body, 64 * 1024) }
            val next = parsePlaylist(text, url) ?: throw IOException("Liste de lecture vide")
            if (isHlsUrl(next) || text.trimStart().startsWith("#EXTM3U") && text.contains("#EXT-X-")) {
                throw HlsStreamException(if (text.contains("#EXT-X-")) url else next)
            }
            url = next
        }
        throw IOException("Trop de redirections")
    }

    private fun openOnce(url: String): StreamConnection {
        val request = Request.Builder()
            .url(url)
            .header("Icy-MetaData", "1")
            .header("User-Agent", StreamRecorder.USER_AGENT)
            .build()
        val c = client.newCall(request)
        call = c
        try {
            val response = c.execute()
            if (!response.isSuccessful) {
                response.close()
                throw IOException("HTTP ${response.code}")
            }
            val headers = response.headers.associate { (k, v) -> k.lowercase() to v }
            return StreamConnection(response.request.url.toString(), headers, response.body!!.byteStream()) {
                response.close()
            }
        } catch (e: ProtocolException) {
            // OkHttp refuse « ICY 200 OK » : on parle au serveur directement.
            if (e.message?.contains("ICY", ignoreCase = true) == true) return openRaw(url, 0)
            throw e
        }
    }

    /** Requête HTTP minimale sur une socket, compatible avec les serveurs SHOUTcast v1. */
    private fun openRaw(url: String, depth: Int): StreamConnection {
        if (depth > MAX_HOPS) throw IOException("Trop de redirections")
        val uri = URI(url)
        val https = uri.scheme.equals("https", ignoreCase = true)
        val host = uri.host ?: throw IOException("Adresse invalide")
        val port = if (uri.port > 0) uri.port else if (https) 443 else 80
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")

        val s: Socket = if (https) {
            val ssl = SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket
            ssl.soTimeout = 20_000
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                ssl.close()
                throw IOException("Certificat invalide pour $host")
            }
            ssl
        } else {
            Socket().apply {
                soTimeout = 20_000
                connect(InetSocketAddress(host, port), 10_000)
            }
        }
        socket = s
        try {
            val hostHeader = if (uri.port > 0) "$host:$port" else host
            val req = "GET $path HTTP/1.0\r\n" +
                "Host: $hostHeader\r\n" +
                "User-Agent: ${StreamRecorder.USER_AGENT}\r\n" +
                "Icy-MetaData: 1\r\n" +
                "Accept: */*\r\n" +
                "Connection: close\r\n\r\n"
            s.getOutputStream().apply {
                write(req.toByteArray(StandardCharsets.ISO_8859_1))
                flush()
            }
            val input = BufferedInputStream(s.getInputStream(), 16 * 1024)
            val lines = readHeaderLines(input)
            val status = lines.firstOrNull() ?: throw IOException("Réponse vide")
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Réponse invalide : $status")
            val headers = lines.drop(1).mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
            }.toMap()
            if (code in 300..399) {
                val location = headers["location"] ?: throw IOException("Redirection sans adresse")
                s.close()
                return openRaw(uri.resolve(location).toString(), depth + 1)
            }
            if (code !in 200..299) throw IOException("HTTP $code")
            return StreamConnection(url, headers, input) { runCatching { s.close() } }
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
    }

    companion object {
        private const val MAX_HOPS = 5

        private val PLAYLIST_TYPES = listOf(
            "audio/x-scpls", "application/pls", "audio/x-mpegurl", "audio/mpegurl",
            "application/x-mpegurl", "application/vnd.apple.mpegurl", "video/x-ms-asf",
            "audio/x-ms-asx", "application/xspf+xml",
        )
        private val PLAYLIST_EXTENSIONS = listOf(".pls", ".m3u", ".asx", ".xspf")

        fun isHlsUrl(url: String) = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

        private fun isPlaylist(type: String, url: String): Boolean {
            if (PLAYLIST_TYPES.any { type.contains(it) }) return true
            val path = url.substringBefore('?').lowercase()
            return PLAYLIST_EXTENSIONS.any { path.endsWith(it) } && !type.startsWith("audio/mpeg") && !type.startsWith("audio/aac")
        }

        /** Première adresse http(s) trouvée dans une liste .pls, .m3u, .asx ou .xspf. */
        fun parsePlaylist(text: String, base: String): String? {
            val candidates = mutableListOf<String>()
            text.lineSequence().map { it.trim() }.forEach { line ->
                when {
                    line.startsWith("File", ignoreCase = true) && line.contains('=') ->
                        candidates += line.substringAfter('=').trim()
                    line.startsWith("http://", true) || line.startsWith("https://", true) ->
                        candidates += line
                }
            }
            Regex("""(?:href|ref)\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(text)
                .forEach { candidates += it.groupValues[1] }
            Regex("""<location>\s*([^<\s]+)\s*</location>""", RegexOption.IGNORE_CASE).findAll(text)
                .forEach { candidates += it.groupValues[1] }
            val first = candidates.firstOrNull { it.isNotBlank() } ?: return null
            return runCatching { URI(base).resolve(first).toString() }.getOrDefault(first)
                .takeIf { it.startsWith("http", ignoreCase = true) }
        }

        private fun readLimited(input: InputStream, max: Int): String {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() < max) {
                val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        }

        private fun readHeaderLines(input: InputStream): List<String> {
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            var total = 0
            while (true) {
                val b = input.read()
                if (b < 0) throw IOException("Connexion fermée pendant les en-têtes")
                if (++total > 16 * 1024) throw IOException("En-têtes trop longs")
                when (b) {
                    '\r'.code -> Unit
                    '\n'.code -> {
                        if (line.isEmpty()) return lines
                        lines += line.toString()
                        line.setLength(0)
                    }
                    else -> line.append(b.toChar())
                }
            }
        }
    }
}
