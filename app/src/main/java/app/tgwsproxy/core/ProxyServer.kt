package app.tgwsproxy.core

import app.tgwsproxy.core.Mt.crypt
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Порт tg_ws_proxy.py (_handle_client) + bridge.py на блокирующих сокетах.
 * Не портировано в v1: Fake TLS, PROXY protocol, пул WS-соединений, CF worker, HTTP/2 для CF.
 */
class ProxyServer(private val cfg: ProxyConfig, private val stats: Stats) {

    private val secret = Mt.hexToBytes(cfg.secretHex)
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "tgws-conn").apply { isDaemon = true }
    }
    @Volatile private var server: ServerSocket? = null
    @Volatile private var stopped = false
    private val open: MutableSet<Closeable> = ConcurrentHashMap.newKeySet()

    private val wsBlacklist: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val dcFailUntil = ConcurrentHashMap<String, Long>()
    private val ipFailUntil = ConcurrentHashMap<String, Long>()
    private val tcpFailures = ConcurrentHashMap<String, Int>()
    private val tcpRetryAfter = ConcurrentHashMap<String, Long>()
    private val tcpConnecting: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun now() = System.nanoTime() / 1_000_000

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getByName(cfg.host), cfg.port))
        server = s
        AppLog.i("Прокси слушает ${cfg.host}:${cfg.port}")
        for ((dc, ip) in cfg.dcIps.toSortedMap()) AppLog.i("  DC$dc -> $ip")
        if (cfg.cfDomains.isNotEmpty()) AppLog.i("  CF-домены: ${cfg.cfDomains.joinToString()}")
        executor.execute { acceptLoop(s) }
    }

    fun stop() {
        stopped = true
        try { server?.close() } catch (_: Exception) {}
        open.toList().forEach { closeQuietly(it) }
        executor.shutdownNow()
        AppLog.i("Прокси остановлен")
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!stopped) {
            try {
                val c = s.accept()
                executor.execute { handleClient(c) }
            } catch (e: IOException) {
                if (stopped || s.isClosed) break
                AppLog.w("accept: $e")
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                break
            }
        }
    }

    // ---------------------------------------------------------------- клиент

    private fun handleClient(sock: Socket) {
        open.add(sock)
        stats.total.incrementAndGet()
        stats.active.incrementAndGet()
        val label = "${sock.inetAddress?.hostAddress}:${sock.port}"
        try {
            sock.tcpNoDelay = true
            try { sock.receiveBufferSize = cfg.bufferSize; sock.sendBufferSize = cfg.bufferSize } catch (_: Exception) {}
            sock.soTimeout = 10_000
            val input = sock.getInputStream()
            val handshake = ByteArray(Mt.HANDSHAKE_LEN)
            readFully(input, handshake)
            sock.soTimeout = 0

            val hs = Mt.tryHandshake(handshake, secret)
            if (hs == null) {
                stats.bad.incrementAndGet()
                AppLog.w("[$label] неверный handshake (другой секрет или протокол)")
                try { sock.soTimeout = 5_000; while (input.read() >= 0) { /* drain */ } } catch (_: Exception) {}
                return
            }

            var dc = hs.dc
            val isTest = dc >= 10000
            if (isTest) dc -= 10000
            val isMedia = hs.isMedia
            val protoInt = when {
                hs.protoTag.contentEquals(Mt.TAG_ABRIDGED) -> MsgSplitter.PROTO_ABRIDGED
                hs.protoTag.contentEquals(Mt.TAG_INTERMEDIATE) -> MsgSplitter.PROTO_INTERMEDIATE
                else -> MsgSplitter.PROTO_PADDED
            }
            val dcIdx = if (isMedia) -dc else dc
            val mediaTag = if (isMedia) " media" else ""
            AppLog.d("[$label] handshake ok: DC$dc$mediaTag")

            val relayInit = Mt.generateRelayInit(hs.protoTag, dcIdx)
            val ctx = Mt.buildCryptoCtx(hs.prekeyIv, secret, relayInit)

            val dcKey = "$dc${if (isTest) "t" else ""}${if (isMedia) "m" else ""}"
            val t = now()
            val target = cfg.dcIps[dc]
            val anyCf = cfg.cfDomains.isNotEmpty()

            val skipWs = target == null || dcKey in wsBlacklist ||
                    (ipFailUntil.containsKey(target) && t < (ipFailUntil[target] ?: Long.MIN_VALUE) && anyCf)
            if (skipWs) {
                when {
                    target == null -> AppLog.i("[$label] DC$dc не в настройках -> запасной путь")
                    dcKey in wsBlacklist -> AppLog.i("[$label] DC$dc$mediaTag в чёрном списке WS -> запасной путь")
                    else -> AppLog.i("[$label] DC$dc$mediaTag недавно не отвечал -> запасной путь")
                }
                if (!doFallback(sock, relayInit, label, dc, isTest, isMedia, ctx, protoInt)) {
                    AppLog.w("[$label] DC$dc$mediaTag: запасных путей нет")
                }
                return
            }

            val wsTimeout = if (t < (dcFailUntil[dcKey] ?: Long.MIN_VALUE)) WS_FAIL_TIMEOUT_MS else WS_TIMEOUT_MS
            val path = if (isTest) Dc.WS_PATH_TEST else Dc.WS_PATH
            var ws: WsClient? = null
            var sawRedirect = false
            var timedOut = false
            var allRedirects = true

            for (domain in Dc.wsDomains(dc, isMedia)) {
                AppLog.d("[$label] DC$dc$mediaTag -> wss://$domain$path via $target")
                try {
                    ws = WsClient.connect(target, domain, path, wsTimeout, true, cfg.bufferSize)
                    allRedirects = false
                    break
                } catch (e: WsHandshakeError) {
                    stats.wsErrors.incrementAndGet()
                    if (e.isRedirect) {
                        sawRedirect = true
                        AppLog.w("[$label] DC$dc$mediaTag: ${e.status} от $domain")
                    } else {
                        allRedirects = false
                        AppLog.w("[$label] DC$dc$mediaTag WS handshake: ${e.statusLine}")
                    }
                } catch (_: SocketTimeoutException) {
                    stats.wsErrors.incrementAndGet()
                    timedOut = true
                    AppLog.w("[$label] DC$dc$mediaTag: таймаут WS через $domain")
                    break
                } catch (e: Exception) {
                    stats.wsErrors.incrementAndGet()
                    allRedirects = false
                    AppLog.w("[$label] DC$dc$mediaTag: WS не удалось: $e")
                }
            }

            if (ws == null) {
                if (timedOut) {
                    ipFailUntil[target] = t + IP_FAIL_COOLDOWN_MS
                    AppLog.i("[$label] $target не отвечает, пауза ${IP_FAIL_COOLDOWN_MS / 1000} с")
                }
                if (sawRedirect && allRedirects) {
                    wsBlacklist.add(dcKey)
                    AppLog.w("[$label] DC$dc$mediaTag: все ответы 302, WS отключён для него")
                } else {
                    dcFailUntil[dcKey] = t + DC_FAIL_COOLDOWN_MS
                }
                if (!doFallback(sock, relayInit, label, dc, isTest, isMedia, ctx, protoInt)) {
                    AppLog.w("[$label] DC$dc$mediaTag: запасных путей нет")
                }
                return
            }

            ipFailUntil.remove(target)
            stats.ws.incrementAndGet()
            ws.send(relayInit)
            bridgeWs(sock, ws, ctx, MsgSplitter(protoInt), label, "DC$dc${if (isMedia) "m" else ""}")
        } catch (_: SocketTimeoutException) {
            AppLog.w("[$label] таймаут handshake")
        } catch (_: EOFException) {
            AppLog.d("[$label] клиент отключился")
        } catch (e: IOException) {
            AppLog.d("[$label] ${e.javaClass.simpleName}: ${e.message}")
        } catch (e: Exception) {
            AppLog.e("[$label] неожиданная ошибка: $e")
        } finally {
            stats.active.decrementAndGet()
            open.remove(sock)
            closeQuietly(sock)
        }
    }

    // ------------------------------------------------------------ запасные пути

    private fun doFallback(
        client: Socket, relayInit: ByteArray, label: String, dc: Int,
        isTest: Boolean, isMedia: Boolean, ctx: Mt.CryptoCtx, protoInt: Int,
    ): Boolean {
        val dst = (if (isTest) Dc.TEST_IPS else Dc.DEFAULT_IPS)[dc]
        if (cfg.cfDomains.isNotEmpty() && !isTest) {
            if (cfFallback(client, relayInit, label, dc, isMedia, ctx, protoInt)) return true
        }
        return dst != null && tcpFallback(client, dst, 443, relayInit, label, ctx)
    }

    private fun cfFallback(
        client: Socket, relayInit: ByteArray, label: String, dc: Int,
        isMedia: Boolean, ctx: Mt.CryptoCtx, protoInt: Int,
    ): Boolean {
        AppLog.i("[$label] DC$dc -> пробую CF-прокси")
        var ws: WsClient? = null
        for (base in cfg.cfDomains) {
            val domain = "kws$dc.$base"
            try {
                ws = WsClient.connect(domain, domain, Dc.WS_PATH, 10_000, true, cfg.bufferSize)
                break
            } catch (e: Exception) {
                AppLog.w("[$label] DC$dc CF-прокси не ответил: $e")
            }
        }
        if (ws == null) return false
        stats.cf.incrementAndGet()
        ws.send(relayInit)
        bridgeWs(client, ws, ctx, MsgSplitter(protoInt), label, "DC$dc${if (isMedia) "m" else ""}")
        return true
    }

    private fun tcpFallback(
        client: Socket, dst: String, port: Int, relayInit: ByteArray,
        label: String, ctx: Mt.CryptoCtx,
    ): Boolean {
        val key = "$dst:$port"
        if (key in tcpConnecting || now() < (tcpRetryAfter[key] ?: Long.MIN_VALUE)) {
            AppLog.d("[$label] TCP-запас $key пропущен (пауза)")
            return false
        }
        tcpConnecting.add(key)
        val remote = Socket()
        try {
            try {
                AppLog.i("[$label] TCP-запас: $key")
                remote.tcpNoDelay = true
                remote.connect(InetSocketAddress(dst, port), 10_000)
                remote.getOutputStream().apply { write(relayInit); flush() }
            } catch (e: Exception) {
                val failures = (tcpFailures[key] ?: 0) + 1
                tcpFailures[key] = failures
                val delay = minOf(TCP_BACKOFF_INITIAL_MS * (1L shl minOf(failures - 1, 7)), TCP_BACKOFF_MAX_MS)
                tcpRetryAfter[key] = now() + delay
                AppLog.w("[$label] TCP-запас $key не удался: $e; повтор через ${delay / 1000} с")
                return false
            } finally {
                tcpConnecting.remove(key)
            }
            tcpFailures.remove(key)
            tcpRetryAfter.remove(key)
            stats.tcp.incrementAndGet()
            open.add(remote)
            try { bridgeTcp(client, remote, ctx) } finally { open.remove(remote) }
            return true
        } finally {
            closeQuietly(remote)
        }
    }

    // ---------------------------------------------------------------- мосты

    /** client TCP <-> WS(Telegram) с перешифровкой. */
    private fun bridgeWs(client: Socket, ws: WsClient, ctx: Mt.CryptoCtx, splitter: MsgSplitter, label: String, tag: String) {
        open.add(ws)
        val started = now()
        val reason = AtomicReference("normal")
        val done = CountDownLatch(1)
        val cin = client.getInputStream()
        val cout = client.getOutputStream()
        var up = 0L
        var down = 0L

        val upTask = executor.submit {
            try {
                val buf = ByteArray(65536)
                while (true) {
                    val n = cin.read(buf)
                    if (n < 0) {
                        val tail = splitter.flush()
                        if (tail.isNotEmpty()) ws.send(tail[0])
                        break
                    }
                    stats.bytesUp.addAndGet(n.toLong()); up += n
                    val plain = ctx.cltDec.crypt(buf, 0, n)
                    val enc = ctx.tgEnc.crypt(plain)
                    val parts = splitter.split(enc, plain)
                    if (parts.isEmpty()) continue
                    if (parts.size > 1) ws.sendBatch(parts) else ws.send(parts[0])
                }
            } catch (e: Exception) {
                reason.compareAndSet("normal", "клиент: ${e.javaClass.simpleName}")
            } finally { done.countDown() }
        }
        val downTask = executor.submit {
            try {
                while (true) {
                    val data = ws.recv()
                    if (data == null) {
                        reason.compareAndSet("normal", "Telegram закрыл WS")
                        break
                    }
                    stats.bytesDown.addAndGet(data.size.toLong()); down += data.size
                    val plain = ctx.tgDec.crypt(data)
                    cout.write(ctx.cltEnc.crypt(plain))
                    cout.flush()
                }
            } catch (e: Exception) {
                reason.compareAndSet("normal", "Telegram: ${e.javaClass.simpleName}")
            } finally { done.countDown() }
        }
        try { done.await() } catch (_: InterruptedException) {}
        ws.close(); closeQuietly(client)
        upTask.cancel(true); downTask.cancel(true)
        open.remove(ws)
        AppLog.i("[$label] $tag WS закрыт (${reason.get()}): ↑${human(up)} ↓${human(down)} за ${(now() - started) / 1000.0} с")
    }

    /** client TCP <-> TCP(Telegram) с перешифровкой. */
    private fun bridgeTcp(client: Socket, remote: Socket, ctx: Mt.CryptoCtx) {
        val done = CountDownLatch(1)
        fun pump(src: InputStream, dst: java.io.OutputStream, upstream: Boolean) = executor.submit {
            try {
                val buf = ByteArray(65536)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    val out = if (upstream) {
                        stats.bytesUp.addAndGet(n.toLong())
                        ctx.tgEnc.crypt(ctx.cltDec.crypt(buf, 0, n))
                    } else {
                        stats.bytesDown.addAndGet(n.toLong())
                        ctx.cltEnc.crypt(ctx.tgDec.crypt(buf, 0, n))
                    }
                    dst.write(out); dst.flush()
                }
            } catch (_: Exception) {
            } finally { done.countDown() }
        }
        val a = pump(client.getInputStream(), remote.getOutputStream(), true)
        val b = pump(remote.getInputStream(), client.getOutputStream(), false)
        try { done.await() } catch (_: InterruptedException) {}
        closeQuietly(client); closeQuietly(remote)
        a.cancel(true); b.cancel(true)
    }

    // --------------------------------------------------------------- утилиты

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    private fun closeQuietly(c: Closeable) { try { c.close() } catch (_: Exception) {} }

    private fun human(n: Long): String {
        var v = n.toDouble()
        for (u in arrayOf("Б", "КБ", "МБ", "ГБ")) { if (v < 1024) return "%.1f%s".format(v, u); v /= 1024 }
        return "%.1fТБ".format(v)
    }

    companion object {
        const val IP_FAIL_COOLDOWN_MS = 3_600_000L
        const val DC_FAIL_COOLDOWN_MS = 60_000L
        const val WS_FAIL_TIMEOUT_MS = 2_000
        const val WS_TIMEOUT_MS = 5_000
        const val TCP_BACKOFF_INITIAL_MS = 30_000L
        const val TCP_BACKOFF_MAX_MS = 3_600_000L
    }
}
