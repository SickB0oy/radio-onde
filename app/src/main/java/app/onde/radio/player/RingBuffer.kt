package app.onde.radio.player

import java.io.Closeable
import java.io.InterruptedIOException

/**
 * Tampon circulaire en mémoire vive qui garde au plus [maxCapacity] octets d'un flux.
 * Rien n'est écrit sur le stockage du téléphone.
 *
 * Il démarre petit (1 Mo, bien assez pour l'écoute en direct) et ne grandit que si l'on met en
 * pause : changer de station ne coûte donc presque rien en mémoire, même sur un téléphone modeste.
 *
 * Les positions sont « absolues » : la position 0 est le premier octet reçu depuis
 * le lancement de la station. Seuls les octets entre [oldest] et [written] sont lisibles.
 */
class RingBuffer(private val maxCapacity: Int, initialCapacity: Int = 1 shl 20) : Closeable {

    @Volatile private var capacity = minOf(initialCapacity, maxCapacity)
    private var memory: ByteArray? = ByteArray(capacity)
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
            var mem = memory ?: return
            if (finished) return
            // Pas assez de place pour tout garder (on est en pause) : on agrandit, jusqu'au maximum.
            val needed = written + len - floor
            if (needed > capacity && capacity < maxCapacity) {
                var newCap = capacity.toLong()
                while (newCap < needed && newCap < maxCapacity) newCap = minOf(newCap * 2, maxCapacity.toLong())
                val grown = try {
                    ByteArray(newCap.toInt())
                } catch (_: OutOfMemoryError) {
                    null // téléphone à court de mémoire : on garde la taille actuelle
                }
                if (grown != null) {
                    var pos = oldest
                    while (pos < written) {
                        val src = (pos % capacity).toInt()
                        val dst = (pos % grown.size).toInt()
                        val n = minOf(written - pos, (capacity - src).toLong(), (grown.size - dst).toLong()).toInt()
                        System.arraycopy(mem, src, grown, dst, n)
                        pos += n
                    }
                    memory = grown
                    mem = grown
                    capacity = grown.size
                }
            }
            var o = off
            var remaining = len
            while (remaining > 0) {
                val p = (written % capacity).toInt()
                val n = minOf(remaining, capacity - p)
                System.arraycopy(buf, o, mem, p, n)
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
            val mem = memory ?: return -1
            if (pos >= written) return if (finished) -1 else 0
            val start = maxOf(pos, oldest)
            val p = (start % capacity).toInt()
            val n = minOf(len.toLong(), written - start, (capacity - p).toLong()).toInt()
            System.arraycopy(mem, p, buf, off, n)
            return n
        }
    }

    /** Arrête le tampon et libère la mémoire. */
    override fun close() {
        synchronized(lock) {
            finished = true
            memory = null
            lock.notifyAll()
        }
    }
}
