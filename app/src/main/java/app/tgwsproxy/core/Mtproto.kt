package app.tgwsproxy.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Порт криптографической части proxy/tg_ws_proxy.py (Flowseal/tg-ws-proxy):
 *   _try_handshake, _generate_relay_init, _build_crypto_ctx.
 *
 * Константы сверены с proxy/utils.py оригинала.
 */
object Mt {
    const val HANDSHAKE_LEN = 64
    const val SKIP_LEN = 8
    const val PREKEY_LEN = 32
    const val IV_LEN = 16
    const val KEY_LEN = 32
    const val PROTO_TAG_POS = 56
    const val DC_IDX_POS = 60

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    val TAG_ABRIDGED = b(0xef, 0xef, 0xef, 0xef)
    val TAG_INTERMEDIATE = b(0xee, 0xee, 0xee, 0xee)
    val TAG_PADDED = b(0xdd, 0xdd, 0xdd, 0xdd)
    private val KNOWN_TAGS = listOf(TAG_ABRIDGED, TAG_INTERMEDIATE, TAG_PADDED)

    // Нельзя начинать init-пакет с этих значений (иначе его примут за HTTP/TLS)
    private const val RESERVED_FIRST_BYTE = 0xef
    private val RESERVED_STARTS = listOf(
        "HEAD".toByteArray(Charsets.US_ASCII),
        "POST".toByteArray(Charsets.US_ASCII),
        "GET ".toByteArray(Charsets.US_ASCII),
        "OPTI".toByteArray(Charsets.US_ASCII),
        b(0xdd, 0xdd, 0xdd, 0xdd),
        b(0xee, 0xee, 0xee, 0xee),
        b(0x16, 0x03, 0x01, 0x02),
    )
    private val RESERVED_CONTINUE = b(0, 0, 0, 0)

    private val rng = SecureRandom()

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** AES-256-CTR, счётчик 128 бит big-endian: так же, как modes.CTR в Python. */
    fun aesCtr(key: ByteArray, iv: ByteArray): Cipher {
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c
    }

    fun Cipher.crypt(data: ByteArray, off: Int = 0, len: Int = data.size): ByteArray =
        update(data, off, len) ?: ByteArray(0)

    fun generateSecretHex(): String = ByteArray(16).also { rng.nextBytes(it) }.toHex()

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Secret must be valid hex" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    class Handshake(
        val dc: Int,
        val isMedia: Boolean,
        val protoTag: ByteArray,
        /** handshake[8 .. 56): prekey(32) + iv(16) клиента */
        val prekeyIv: ByteArray,
    )

    /** Аналог _try_handshake. Возвращает null при неверном секрете или протоколе. */
    fun tryHandshake(handshake: ByteArray, secret: ByteArray): Handshake? {
        require(handshake.size == HANDSHAKE_LEN)
        val prekeyIv = handshake.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
        val decKey = sha256(prekeyIv.copyOfRange(0, PREKEY_LEN) + secret)
        val decIv = prekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)
        val decrypted = aesCtr(decKey, decIv).crypt(handshake)

        val tag = decrypted.copyOfRange(PROTO_TAG_POS, PROTO_TAG_POS + 4)
        if (KNOWN_TAGS.none { it.contentEquals(tag) }) return null

        val dcIdx = (((decrypted[DC_IDX_POS + 1].toInt() and 0xff) shl 8) or
                (decrypted[DC_IDX_POS].toInt() and 0xff)).toShort().toInt()
        return Handshake(
            dc = kotlin.math.abs(dcIdx),
            isMedia = dcIdx < 0,
            protoTag = tag,
            prekeyIv = prekeyIv,
        )
    }

    /** Аналог _generate_relay_init: свежий obfuscation-init для соединения с Telegram DC. */
    fun generateRelayInit(protoTag: ByteArray, dcIdx: Int): ByteArray {
        var rnd: ByteArray
        while (true) {
            rnd = ByteArray(HANDSHAKE_LEN).also { rng.nextBytes(it) }
            if ((rnd[0].toInt() and 0xff) == RESERVED_FIRST_BYTE) continue
            val start = rnd.copyOfRange(0, 4)
            if (RESERVED_STARTS.any { it.contentEquals(start) }) continue
            if (rnd.copyOfRange(4, 8).contentEquals(RESERVED_CONTINUE)) continue
            break
        }
        val encKey = rnd.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN)
        val encIv = rnd.copyOfRange(SKIP_LEN + PREKEY_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
        val encryptedFull = aesCtr(encKey, encIv).crypt(rnd)

        val dcLe = byteArrayOf((dcIdx and 0xff).toByte(), ((dcIdx shr 8) and 0xff).toByte())
        val tailRandom = ByteArray(2).also { rng.nextBytes(it) }
        val tailPlain = protoTag + dcLe + tailRandom // 4 + 2 + 2 = 8 байт

        val result = rnd.copyOf()
        for (i in 0 until 8) {
            val keystream = (encryptedFull[56 + i].toInt() xor rnd[56 + i].toInt())
            result[PROTO_TAG_POS + i] = (tailPlain[i].toInt() xor keystream).toByte()
        }
        return result
    }

    /**
     * Четыре потока шифрования для одного соединения:
     *  cltDec - расшифровать данные от клиента
     *  cltEnc - зашифровать данные клиенту
     *  tgEnc  - зашифровать данные в сторону Telegram
     *  tgDec  - расшифровать данные от Telegram
     */
    class CryptoCtx(
        val cltDec: Cipher,
        val cltEnc: Cipher,
        val tgEnc: Cipher,
        val tgDec: Cipher,
    )

    /** Аналог _build_crypto_ctx. */
    fun buildCryptoCtx(clientPrekeyIv: ByteArray, secret: ByteArray, relayInit: ByteArray): CryptoCtx {
        val zero64 = ByteArray(HANDSHAKE_LEN)

        // Клиентская сторона: key = SHA256(prekey + secret)
        val cltDecKey = sha256(clientPrekeyIv.copyOfRange(0, PREKEY_LEN) + secret)
        val cltDecIv = clientPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)
        val cltEncPrekeyIv = clientPrekeyIv.reversedArray()
        val cltEncKey = sha256(cltEncPrekeyIv.copyOfRange(0, PREKEY_LEN) + secret)
        val cltEncIv = cltEncPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)

        val cltDec = aesCtr(cltDecKey, cltDecIv)
        val cltEnc = aesCtr(cltEncKey, cltEncIv)
        cltDec.crypt(zero64) // пропустить 64 байта init, уже прочитанные

        // Сторона Telegram: обычная obfuscation, ключ без хеша с секретом
        val relayEncKey = relayInit.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN)
        val relayEncIv = relayInit.copyOfRange(SKIP_LEN + PREKEY_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
        val relayDecPrekeyIv = relayInit.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN).reversedArray()
        val relayDecKey = relayDecPrekeyIv.copyOfRange(0, KEY_LEN)
        val relayDecIv = relayDecPrekeyIv.copyOfRange(KEY_LEN, KEY_LEN + IV_LEN)

        val tgEnc = aesCtr(relayEncKey, relayEncIv)
        val tgDec = aesCtr(relayDecKey, relayDecIv)
        tgEnc.crypt(zero64)

        return CryptoCtx(cltDec, cltEnc, tgEnc, tgDec)
    }

    /** Ссылка для подключения Telegram (режим dd, как в оригинале). */
    fun proxyLink(host: String, port: Int, secretHex: String): String =
        "tg://proxy?server=$host&port=$port&secret=dd$secretHex"
}
