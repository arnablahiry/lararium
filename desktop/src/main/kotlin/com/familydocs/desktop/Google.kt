package com.familydocs.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Signing in with the same Google account as the phone, the way Google wants desktop apps to: the browser opens Google's
 * own page, and the answer comes back to a one-off address on this computer (127.0.0.1). The app only gets the same access
 * the phone has (its own Lararium files and hidden app folder in Drive), nothing else in your Drive. The long-lived
 * sign-in is kept by [Keychain] (the macOS Keychain, or Windows data protection).
 */
object Google {
    private val SCOPES = listOf(
        "https://www.googleapis.com/auth/drive.file", "https://www.googleapis.com/auth/drive.appdata",
        "https://www.googleapis.com/auth/userinfo.email", "https://www.googleapis.com/auth/userinfo.profile", "openid",
    )
    private val http = OkHttpClient()
    private var access: Pair<String, Long>? = null

    val configured: Boolean get() = BuildInfo.CLIENT_ID.isNotBlank()

    data class Profile(val name: String, val email: String)

    /** Signed in before on this Mac (a refresh token is in the Keychain). */
    fun hasSession(): Boolean = Keychain.read() != null

    /** Opens the browser for Google's sign-in and waits for the answer (up to 5 minutes). */
    suspend fun signIn(): Unit = withContext(Dispatchers.IO) {
        val verifier = b64(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = b64(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val code = CompletableDeferred<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val q = ex.requestURI.rawQuery.orEmpty().split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to java.net.URLDecoder.decode(it[1], "UTF-8") } }.toMap()
            val ok = q["state"] == state && q["code"] != null
            val page = """<!doctype html><meta charset="utf-8"><title>Lararium</title><body style="font-family:-apple-system,sans-serif;background:#121213;color:#eee;display:grid;place-items:center;height:90vh">
                <div style="text-align:center"><h2>${if (ok) "Signed in" else "Sign-in didn't finish"}</h2><p style="color:#9d9da3">${if (ok) "You can close this tab and go back to Lararium." else (q["error"] ?: "Please try again from Lararium.")}</p></div>""".trimIndent()
            val body = page.toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            if (ok) code.complete(q["code"]!!) else code.completeExceptionally(IllegalStateException(q["error"] ?: "Sign-in was cancelled"))
        }
        server.start()
        try {
            val redirect = "http://127.0.0.1:${server.address.port}"
            val url = "https://accounts.google.com/o/oauth2/v2/auth?" + listOf(
                "client_id" to BuildInfo.CLIENT_ID, "redirect_uri" to redirect, "response_type" to "code",
                "scope" to SCOPES.joinToString(" "), "code_challenge" to challenge, "code_challenge_method" to "S256",
                "access_type" to "offline", "prompt" to "consent", "state" to state,
            ).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
            val got = withTimeout(5 * 60_000L) { code.await() }
            val j = token(FormBody.Builder().add("code", got).add("client_id", BuildInfo.CLIENT_ID).add("client_secret", BuildInfo.CLIENT_SECRET)
                .add("redirect_uri", redirect).add("grant_type", "authorization_code").add("code_verifier", verifier).build())
            j.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { Keychain.write(it) } ?: error("Google didn't keep the sign-in. Please try again.")
            access = j.getString("access_token") to System.currentTimeMillis() + (j.optLong("expires_in", 3600) - 120) * 1000
        } finally { server.stop(0) }
    }

    /** A current access token (refreshed when needed). */
    suspend fun token(): String = withContext(Dispatchers.IO) {
        access?.takeIf { System.currentTimeMillis() < it.second }?.let { return@withContext it.first }
        val refresh = Keychain.read() ?: error("Not signed in")
        val j = token(FormBody.Builder().add("refresh_token", refresh).add("client_id", BuildInfo.CLIENT_ID).add("client_secret", BuildInfo.CLIENT_SECRET)
            .add("grant_type", "refresh_token").build())
        j.getString("access_token").also { access = it to System.currentTimeMillis() + (j.optLong("expires_in", 3600) - 120) * 1000 }
    }

    suspend fun profile(): Profile = withContext(Dispatchers.IO) {
        val t = token()
        http.newCall(Request.Builder().url("https://www.googleapis.com/oauth2/v3/userinfo").header("Authorization", "Bearer $t").build()).execute().use { r ->
            val j = JSONObject(r.body!!.string())
            Profile(j.optString("name"), j.optString("email"))
        }
    }

    fun signOut() { access = null; Keychain.delete() }

    private fun token(form: FormBody): JSONObject =
        http.newCall(Request.Builder().url("https://oauth2.googleapis.com/token").post(form).build()).execute().use { r ->
            val j = JSONObject(r.body?.string().orEmpty().ifEmpty { "{}" })
            if (!r.isSuccessful) {
                if (j.optString("error") == "invalid_grant") Keychain.delete() // revoked or expired: sign in again
                error(j.optString("error_description").ifEmpty { "Google sign-in failed (${r.code})" })
            }
            j
        }

    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}
