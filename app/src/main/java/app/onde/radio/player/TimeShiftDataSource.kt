package app.onde.radio.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Source de données Media3 qui lit le [RingBuffer] d'un [StreamRecorder]
 * à partir d'une position absolue passée dans l'URI : `timeshift://<session>?from=<octet>`.
 */
@OptIn(UnstableApi::class)
class TimeShiftDataSource(
    private val recorder: StreamRecorder,
    private val minReadablePos: () -> Long,
) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var position = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val from = dataSpec.uri.getQueryParameter("from")?.toLongOrNull() ?: 0L
        position = maxOf(from + dataSpec.position, minReadablePos())
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!opened) throw IOException("Source fermée")
        while (true) {
            // Si on est resté en pause trop longtemps, le début a été écrasé : on saute en avant.
            position = maxOf(position, minReadablePos())
            val n = recorder.buffer.read(position, buffer, offset, length, 250)
            when {
                n > 0 -> {
                    position += n
                    recorder.readPos = position
                    bytesTransferred(n)
                    return n
                }
                n < 0 -> return C.RESULT_END_OF_INPUT
                Thread.currentThread().isInterrupted -> throw InterruptedIOException()
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        uri = null
    }
}
