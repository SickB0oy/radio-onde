package app.onde.radio.player

import java.io.Closeable
import java.io.File
import java.io.InterruptedIOException
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Tampon circulaire qui garde au plus [capacity] octets d'un flux, en mémoire vive ([inMemory])
 * ou dans un fichier temporaire ([onDisk], seulement si l'option « Retour en arrière » est activée).
 *
 * Les positions sont « absolues » : la position 0 est le premier octet reçu depuis
 * le lancement de la station. Seuls les octets entre [oldest] et [written] sont lisibles.
 */
class RingBuffer private constructor(
    private val raf: RandomAccessFile?,
    private val memory: ByteArray?,
    private val file: File?,
    val capacity: Long,
) : Closeable {

    companion object {
        fun inMemory(capacity: Int) = RingBuffer(null, ByteArray(capacity), null, capacity.toLong())

        fun onDisk(file: File, capacity: Long) =
            RingBuffer(RandomAccessFile(file, "rw").apply { setLength(capacity) }, null, file, capacity)
    }

    private val lock = Object()
    @Volatile private var floor = 0L

    /** Nombre total d'octets reçus depuis le début (position absolue du direct). */
    @Volatile
    var written: Long = 0L
        private set

    /** Plus d'écriture à venir (station arrêtée ou flux perdu). */
    @Volatile
    var finished: Boolean = false
        private set

    /** Plus ancienne position encore disponible. */
    val oldest: Long get() = maxOf(0L, written - capacity, floor)

    /** Oublie tout ce qui précède [pos] : ces octets ne seront plus jamais relus. */
    fun discardBefore(pos: Long) {
        synchronized(lock) {
            val p = minOf(pos, written)
            if (p > floor) floor = p
        }
    }

    fun write(buf: ByteArray, off: Int, len: Int) {
        synchronized(lock) {
            if (finished) return
            var o = off
            var remaining = len
            while (remaining > 0) {
                val p = written % capacity
                val n = minOf(remaining.toLong(), capacity - p).toInt()
                store(p, buf, o, n)
                written += n
                o += n
                remaining -= n
            }
            lock.notifyAll()
        }
    }

    fun finish() {
        synchronized(lock) {
            finished = true
            lock.notifyAll()
        }
    }

    /**
     * Lit à partir de [pos]. Bloque au plus [timeoutMs] s'il n'y a pas encore de données.
     * @return le nombre d'octets lus, 0 si le délai est écoulé, -1 si le flux est terminé.
     */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int, timeoutMs: Long): Int {
        synchronized(lock) {
            if (pos >= written && !finished) {
                try {
                    lock.wait(timeoutMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException()
                }
            }
            if (pos >= written) return if (finished) -1 else 0
            val start = maxOf(pos, oldest)
            val p = start % capacity
            val n = minOf(len.toLong(), written - start, capacity - p).toInt()
            load(p, buf, off, n)
            return n
        }
    }

    /** Copie les octets [from, to) dans [out] (utilisé pour l'enregistrement). */
    fun copyTo(out: OutputStream, from: Long, to: Long) {
        val chunk = ByteArray(64 * 1024)
        var pos = from
        while (pos < to) {
            val n = synchronized(lock) {
                val start = maxOf(pos, oldest)
                if (start >= to) return
                pos = start
                val p = start % capacity
                val len = minOf(chunk.size.toLong(), to - start, capacity - p).toInt()
                load(p, chunk, 0, len)
                len
            }
            out.write(chunk, 0, n)
            pos += n
        }
    }

    private fun store(p: Long, buf: ByteArray, off: Int, n: Int) {
        if (raf != null) {
            raf.seek(p)
            raf.write(buf, off, n)
        } else {
            System.arraycopy(buf, off, memory!!, p.toInt(), n)
        }
    }

    private fun load(p: Long, buf: ByteArray, off: Int, n: Int) {
        if (raf != null) {
            raf.seek(p)
            raf.readFully(buf, off, n)
        } else {
            System.arraycopy(memory!!, p.toInt(), buf, off, n)
        }
    }

    /** Ferme le tampon et efface le fichier temporaire s'il y en a un. */
    override fun close() {
        finish()
        synchronized(lock) {
            raf?.close()
            file?.delete()
        }
    }
}
