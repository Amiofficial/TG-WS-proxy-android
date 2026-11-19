package app.tgwsproxy

import android.content.Context
import app.tgwsproxy.core.Mt
import app.tgwsproxy.core.ProxyConfig

class Settings(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("tgws", Context.MODE_PRIVATE)

    var port: Int
        get() = sp.getInt("port", 1443)
        set(v) = sp.edit().putInt("port", v).apply()

    var dcIpsText: String
        get() = sp.getString("dc", ProxyConfig.DEFAULT_DC_TEXT) ?: ProxyConfig.DEFAULT_DC_TEXT
        set(v) = sp.edit().putString("dc", v).apply()

    var cfDomainsText: String
        get() = sp.getString("cf", "") ?: ""
        set(v) = sp.edit().putString("cf", v).apply()

    val secret: String
        get() = sp.getString("secret", null)
            ?: Mt.generateSecretHex().also { sp.edit().putString("secret", it).apply() }

    fun regenerateSecret() = sp.edit().putString("secret", Mt.generateSecretHex()).apply()

    fun load() = ProxyConfig(
        port = port,
        secretHex = secret,
        dcIps = ProxyConfig.parseDcIps(dcIpsText),
        cfDomains = ProxyConfig.parseDomains(cfDomainsText),
    )

    fun link(): String {
        val c = load()
        return Mt.proxyLink(c.host, c.port, c.secretHex)
    }
}
