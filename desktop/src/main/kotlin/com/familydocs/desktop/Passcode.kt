package com.familydocs.desktop

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Your Lararium passcode, the same one as on the phone: the phone keeps its check (salt + PBKDF2 hash, never the
 * passcode) sealed in your backup, so the Mac can tell whether what you type is right without ever knowing it.
 */
class Passcode(json: String) {
    private val salt: ByteArray
    private val hash: ByteArray
    val length: Int
    private var fails = 0
    private var until = 0L

    init {
        val j = JSONObject(json)
        salt = Base64.getDecoder().decode(j.getString("salt"))
        hash = Base64.getDecoder().decode(j.getString("hash"))
        length = j.optInt("len", 6)
    }

    sealed interface Check { data object Ok : Check; data class Wrong(val left: Int) : Check; data class Wait(val ms: Long) : Check }

    fun lockedFor(): Long = (until - System.currentTimeMillis()).coerceAtLeast(0)

    /** Slow on purpose (like the phone): call off the main thread. */
    fun check(code: String): Check {
        lockedFor().takeIf { it > 0 }?.let { return Check.Wait(it) }
        val spec = PBEKeySpec(code.toCharArray(), salt, 120_000, 256)
        val got = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.also { spec.clearPassword() }
        if (MessageDigest.isEqual(got, hash)) { fails = 0; return Check.Ok }
        fails++
        if (fails % 5 == 0) { until = System.currentTimeMillis() + 30_000L * (fails / 5); return Check.Wait(lockedFor()) }
        return Check.Wrong(5 - fails % 5)
    }

    companion object {
        fun parse(json: String?): Passcode? = json?.let { runCatching { Passcode(it) }.getOrNull() }
    }
}
