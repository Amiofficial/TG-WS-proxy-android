package app.tgwsproxy.core

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ProxyConfig(
    val host: String = "127.0.0.1",
    val port: Int = 1443,
    val secretHex: String,
    /** DC -> IP, куда стучимся по TLS с SNI kwsN.web.telegram.org */
    val dcIps: Map<Int, String> = DEFAULT_DC_IPS,
    /** Свои домены за Cloudflare для запасного канала (kwsN.<домен>). Пусто = выключено. */
    val cfDomains: List<String> = emptyList(),
    val bufferSize: Int = 256 * 1024,
) {
    companion object {
        val DEFAULT_DC_IPS = mapOf(2 to "149.154.167.220", 4 to "149.154.167.220")
        const val DEFAULT_DC_TEXT = "2:149.154.167.220\n4:149.154.167.220"

        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

        /** Разбор "2:149.154.167.220" через пробел, запятую или новую строку. */
        fun parseDcIps(text: String): Map<Int, String> {
            val result = LinkedHashMap<Int, String>()
            for (tok in text.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }) {
                val parts = tok.split(':')
                val dc = parts.getOrNull(0)?.toIntOrNull()
                val ip = parts.getOrNull(1)
                val ok = parts.size == 2 && dc != null && ip != null &&
                        IPV4.matches(ip) && ip.split('.').all { it.toInt() in 0..255 }
                require(ok) { "Неверная запись «$tok». Нужен формат номер:IP, например 4:149.154.167.220" }
                result[dc!!] = ip!!
            }
            return result
        }

        fun parseDomains(text: String): List<String> =
            text.split(Regex("[\\s,;]+")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }
}

class Stats {
    val total = AtomicLong()
    val active = AtomicLong()
    val ws = AtomicLong()
    val cf = AtomicLong()
    val tcp = AtomicLong()
    val bad = AtomicLong()
    val wsErrors = AtomicLong()
    val bytesUp = AtomicLong()
    val bytesDown = AtomicLong()

    fun reset() = listOf(total, active, ws, cf, tcp, bad, wsErrors, bytesUp, bytesDown).forEach { it.set(0) }
}

/** Лог в памяти для экрана «Журнал». */
object AppLog {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    @Volatile var verbose = false
    private val fmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    private fun add(level: String, msg: String) {
        val line = "${fmt.get()!!.format(Date())} $level $msg"
        _lines.update { (it + line).takeLast(400) }
    }
    fun i(msg: String) = add("I", msg)
    fun w(msg: String) = add("W", msg)
    fun e(msg: String) = add("E", msg)
    fun d(msg: String) { if (verbose) add("D", msg) }
    fun clear() { _lines.value = emptyList() }
}

/** Общее состояние между сервисом и экранами (один процесс). */
object ProxyState {
    val running = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val stats = Stats()
}
