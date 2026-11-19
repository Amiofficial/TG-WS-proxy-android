package app.tgwsproxy.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.ThreadLocalRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class WsHandshakeError(val status: Int, val statusLine: String, val location: String?) :
    IOException("HTTP $status: $statusLine") {
    val isRedirect: Boolean get() = status in intArrayOf(301, 302, 303, 307, 308)
}

/**
 * Блокирующий WebSocket-клиент, порт raw_websocket.py.
 * TCP идёт на [host] (IP дата-центра), а TLS/SNI/Host - на [domain] (kwsN.web.telegram.org).
 * send/recv можно вызывать из разных потоков.
 */
class WsClient private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
) : Closeable {

    private val lock = Any()
    @Volatile private var closed = false
    private val frag = ByteArrayOutputStream()

    fun send(data: ByteArray) = write(buildFrame(OP_BINARY, data))

    fun sendBatch(parts: List<ByteArray>) {
        synchronized(lock) {
            if (closed) throw IOException("WebSocket closed")
            for (p in parts) output.write(buildFrame(OP_BINARY, p))
            output.flush()
        }
    }

    /** Следующее сообщение или null, если соединение закрыто. */
    fun recv(): ByteArray? {
        while (!closed) {
            val f = readFrame()
            when (f.opcode) {
                OP_CLOSE -> {
                    closed = true
                    try { write(buildFrame(OP_CLOSE, f.payload.copyOfRange(0, minOf(2, f.payload.size))), force = true) } catch (_: Exception) {}
                    return null
                }
                OP_PING -> {
                    try { write(buildFrame(OP_PONG, f.payload)) } catch (_: Exception) {}
                }
                OP_PONG -> {}
                OP_CONT, OP_TEXT, OP_BINARY -> {
                    if (f.fin && frag.size() == 0) return f.payload
                    frag.write(f.payload)
                    if (frag.size() > MAX_MESSAGE_LEN) throw IOException("WS message too large: ${frag.size()}")
                    if (f.fin) {
                        val m = frag.toByteArray()
                        frag.reset()
                        return m
                    }
                }
            }
        }
        return null
    }

    override fun close() {
        closed = true
        try { socket.close() } catch (_: Exception) {}
    }

    private fun write(frame: ByteArray, force: Boolean = false) {
        synchronized(lock) {
            if (closed && !force) throw IOException("WebSocket closed")
            output.write(frame)
            output.flush()
        }
    }

    private class Frame(val opcode: Int, val payload: ByteArray, val fin: Boolean)

    private fun readFrame(): Frame {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        val fin = b0 and 0x80 != 0
        val opcode = b0 and 0x0f
        var len = (b1 and 0x7f).toLong()
        if (len == 126L) len = input.readUnsignedShort().toLong()
        else if (len == 127L) len = input.readLong()
        if (len !in 0..MAX_MESSAGE_LEN) throw IOException("WS frame too large: $len")
        val masked = b1 and 0x80 != 0
        val mask = if (masked) ByteArray(4).also { input.readFully(it) } else null
        val payload = ByteArray(len.toInt())
        input.readFully(payload)
        mask?.let {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor it[i and 3].toInt()).toByte()
        }
        return Frame(opcode, payload, fin)
    }

    companion object {
        const val OP_CONT = 0x0
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA
        const val MAX_MESSAGE_LEN = 16 * 1024 * 1024

        fun connect(
            host: String, domain: String, path: String, timeoutMs: Int,
            secure: Boolean = true, bufferSize: Int = 256 * 1024,
        ): WsClient {
            val port = if (secure) 443 else 80
            val raw = Socket()
            var sock: Socket = raw
            try {
                raw.tcpNoDelay = true
                try { raw.receiveBufferSize = bufferSize; raw.sendBufferSize = bufferSize } catch (_: Exception) {}
                raw.connect(InetSocketAddress(host, port), minOf(timeoutMs, 10_000))
                raw.soTimeout = timeoutMs
                if (secure) {
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(raw, domain, port, true) as SSLSocket
                    sock = ssl
                    val p = ssl.sslParameters
                    p.endpointIdentificationAlgorithm = "HTTPS" // проверка сертификата по domain
                    ssl.sslParameters = p
                    ssl.startHandshake()
                }
                val out = sock.getOutputStream()
                val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 65536))

                val keyBytes = ByteArray(16).also { ThreadLocalRandom.current().nextBytes(it) }
                val req = "GET $path HTTP/1.1\r\n" +
                        "Host: $domain\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: ${Base64.getEncoder().encodeToString(keyBytes)}\r\n" +
                        "Sec-WebSocket-Version: 13\r\n" +
                        "Sec-WebSocket-Protocol: binary\r\n\r\n"
                out.write(req.toByteArray(Charsets.US_ASCII))
                out.flush()

                val lines = ArrayList<String>()
                while (true) {
                    val line = readLine(inp) ?: break
                    if (line.isEmpty()) break
                    lines.add(line)
                }
                if (lines.isEmpty()) throw WsHandshakeError(0, "empty response", null)
                val first = lines[0]
                val status = first.split(' ', limit = 3).getOrNull(1)?.toIntOrNull() ?: 0
                if (status == 101) {
                    sock.soTimeout = 0
                    return WsClient(sock, inp, out)
                }
                var location: String? = null
                for (l in lines.drop(1)) {
                    val i = l.indexOf(':')
                    if (i > 0 && l.substring(0, i).trim().equals("location", ignoreCase = true)) location = l.substring(i + 1).trim()
                }
                throw WsHandshakeError(status, first, location)
            } catch (e: Throwable) {
                try { sock.close() } catch (_: Exception) {}
                try { raw.close() } catch (_: Exception) {}
                throw e
            }
        }

        private fun readLine(inp: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = inp.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString().trim()
                if (c == '\n'.code) return sb.toString().trim()
                sb.append(c.toChar())
            }
        }

        private fun buildFrame(opcode: Int, data: ByteArray): ByteArray {
            val len = data.size
            val ext = if (len < 126) 0 else if (len < 65536) 2 else 8
            val out = ByteArray(2 + ext + 4 + len)
            val mask = ByteArray(4).also { ThreadLocalRandom.current().nextBytes(it) }
            out[0] = (0x80 or opcode).toByte()
            var p = 1
            when (ext) {
                0 -> out[p++] = (0x80 or len).toByte()
                2 -> { out[p++] = (0x80 or 126).toByte(); out[p++] = (len shr 8).toByte(); out[p++] = len.toByte() }
                else -> {
                    out[p++] = (0x80 or 127).toByte()
                    for (i in 7 downTo 0) out[p++] = ((len.toLong() shr (8 * i)) and 0xff).toByte()
                }
            }
            System.arraycopy(mask, 0, out, p, 4); p += 4
            for (i in 0 until len) out[p + i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
            return out
        }
    }
}
