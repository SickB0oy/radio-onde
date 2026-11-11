package app.onde.radio.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Envoie les URI `timeshift://` vers le tampon local et tout le reste (HLS, flux directs) vers HTTP.
 */
@OptIn(UnstableApi::class)
class RoutingDataSource(
    private val http: DataSource,
    private val timeShift: (DataSpec) -> DataSource?,
) : DataSource {

    private val listeners = mutableListOf<TransferListener>()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        http.addTransferListener(transferListener)
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == SCHEME) {
            timeShift(dataSpec)?.also { ds -> listeners.forEach(ds::addTransferListener) }
                ?: throw IOException("Tampon de la station indisponible")
        } else {
            http
        }
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: throw IOException("Source non ouverte")

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    companion object {
        const val SCHEME = "timeshift"
    }
}
