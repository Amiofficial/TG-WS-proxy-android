package app.tgwsproxy.core

/**
 * Порт MsgSplitter из bridge.py: режет поток на отдельные MTProto-пакеты,
 * чтобы каждый уходил в своём WS-кадре. [split] получает шифртекст (для отправки)
 * и открытый текст (для разбора длин).
 */
class MsgSplitter(private val proto: Int) {
    private class Buf {
        var a = ByteArray(4096)
        var n = 0
        fun add(src: ByteArray) {
            if (n + src.size > a.size) a = a.copyOf(maxOf(a.size * 2, n + src.size))
            System.arraycopy(src, 0, a, n, src.size)
            n += src.size
        }
        fun drop(k: Int) { System.arraycopy(a, k, a, 0, n - k); n -= k }
        fun copy(from: Int, len: Int): ByteArray = a.copyOfRange(from, from + len)
    }

    private val cipher = Buf()
    private val plain = Buf()
    private var disabled = false

    fun split(chunk: ByteArray, plainChunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)
        cipher.add(chunk); plain.add(plainChunk)

        val parts = ArrayList<ByteArray>()
        var offset = 0
        val bufLen = cipher.n
        while (offset < bufLen) {
            val len = nextPacketLen(offset, bufLen - offset) ?: break
            if (len <= 0) {
                parts.add(cipher.copy(offset, bufLen - offset))
                offset = bufLen
                disabled = true
                break
            }
            parts.add(cipher.copy(offset, len))
            offset += len
        }
        if (offset > 0) { cipher.drop(offset); plain.drop(offset) }
        return parts
    }

    fun flush(): List<ByteArray> {
        if (cipher.n == 0) return emptyList()
        val tail = cipher.copy(0, cipher.n)
        cipher.n = 0; plain.n = 0
        return listOf(tail)
    }

    private fun nextPacketLen(offset: Int, avail: Int): Int? {
        if (avail <= 0) return null
        return when (proto) {
            PROTO_ABRIDGED -> abridged(offset, avail)
            PROTO_INTERMEDIATE, PROTO_PADDED -> intermediate(offset, avail)
            else -> 0
        }
    }

    private fun abridged(offset: Int, avail: Int): Int? {
        val first = plain.a[offset].toInt() and 0xff
        val payload: Long
        val header: Int
        if (first == 0x7f || first == 0xff) {
            if (avail < 4) return null
            payload = (((plain.a[offset + 1].toInt() and 0xff) or
                    ((plain.a[offset + 2].toInt() and 0xff) shl 8) or
                    ((plain.a[offset + 3].toInt() and 0xff) shl 16)).toLong()) * 4
            header = 4
        } else {
            payload = (first and 0x7f).toLong() * 4
            header = 1
        }
        if (payload <= 0) return 0
        val pkt = header + payload
        if (avail < pkt) return null
        return pkt.toInt()
    }

    private fun intermediate(offset: Int, avail: Int): Int? {
        if (avail < 4) return null
        val v = (plain.a[offset].toInt() and 0xff) or
                ((plain.a[offset + 1].toInt() and 0xff) shl 8) or
                ((plain.a[offset + 2].toInt() and 0xff) shl 16) or
                ((plain.a[offset + 3].toInt() and 0xff) shl 24)
        val payload = (v and 0x7fffffff).toLong()
        if (payload <= 0) return 0
        val pkt = 4 + payload
        if (avail < pkt) return null
        return pkt.toInt()
    }

    companion object {
        const val PROTO_ABRIDGED = 0xEFEFEFEF.toInt()
        const val PROTO_INTERMEDIATE = 0xEEEEEEEE.toInt()
        const val PROTO_PADDED = 0xDDDDDDDD.toInt()
    }
}
