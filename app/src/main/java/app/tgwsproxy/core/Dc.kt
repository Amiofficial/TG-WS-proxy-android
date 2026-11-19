package app.tgwsproxy.core

/** Таблицы DC и WS-домены, порт из utils.py. */
object Dc {
    const val WS_PATH = "/apiws"
    const val WS_PATH_TEST = "/apiws_test"

    val DEFAULT_IPS = mapOf(
        1 to "149.154.175.50", 2 to "149.154.167.51", 3 to "149.154.175.100",
        4 to "149.154.167.91", 5 to "149.154.171.5", 203 to "91.105.192.100",
    )
    val TEST_IPS = mapOf(1 to "149.154.175.10", 2 to "149.154.167.40", 3 to "149.154.175.117")

    fun wsDomains(dcIn: Int, isMedia: Boolean): List<String> {
        val dc = if (dcIn == 203) 2 else dcIn
        return if (!isMedia) listOf("kws$dc.web.telegram.org")
        else listOf("kws$dc-1.web.telegram.org", "kws$dc.web.telegram.org")
    }
}
