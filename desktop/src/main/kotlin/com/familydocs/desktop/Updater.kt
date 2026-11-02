package com.familydocs.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Updates for the desktop app, from the same GitHub releases the website links to (mac-v…, windows-v…, linux-v…).
 * Checked quietly at start and every few hours; Settings › About shows it, downloads it and installs it:
 *  - Mac: the new Lararium.app replaces this one (from the .dmg, no dragging) and opens again
 *  - Windows: the .msi installs over this one and Lararium opens again
 *  - Linux: the .deb / .rpm opens in the system's installer (installing needs your password there)
 */
object Updater {
    enum class State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED }
    data class Release(val version: String, val asset: String, val url: String, val size: Long)

    var state by mutableStateOf(State.IDLE); private set
    var latest by mutableStateOf<Release?>(null); private set
    var progress by mutableFloatStateOf(0f); private set
    var error by mutableStateOf<String?>(null); private set
    var checkedAt by mutableStateOf(0L); private set
    private var file: File? = null

    private val http = OkHttpClient.Builder().readTimeout(2, TimeUnit.MINUTES).build()
    private const val RELEASES = "https://api.github.com/repos/arnablahiry/lararium/releases?per_page=60"

    private val prefix = when { Platform.isMac -> "mac-v"; Platform.isWindows -> "windows-v"; else -> "linux-v" }
    /** The newest version's number, next to the newest files (a plain download: no GitHub API, so no rate limit). */
    private val versionFile = "https://github.com/arnablahiry/lararium/releases/download/${prefix.removeSuffix("-v")}/${prefix.removeSuffix("-v")}-version.txt"
    private fun assetUrl(v: String) = "https://github.com/arnablahiry/lararium/releases/download/$prefix$v/${assetName(v)}"

    /** The file for this computer in release [v]. */
    private fun assetName(v: String) = when {
        Platform.isMac -> "Lararium-mac-$v.dmg"
        Platform.isWindows -> "Lararium-windows-$v.msi"
        File("/usr/bin/dpkg").exists() -> "Lararium-linux-$v.deb"
        File("/usr/bin/rpm").exists() -> "Lararium-linux-$v.rpm"
        else -> "Lararium-linux-$v.tar.gz"
    }

    private fun parts(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 }
    fun newer(a: String, b: String): Boolean {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    /** Looks for a newer release. Quiet: a failure only shows in Settings. */
    suspend fun check() = withContext(Dispatchers.IO) {
        if (state == State.DOWNLOADING || state == State.READY) return@withContext
        state = State.CHECKING; error = null
        runCatching {
            // the version file first; the GitHub API (limited to 60 looks an hour per network) only if it isn't there
            http.newCall(Request.Builder().url(versionFile).build()).execute().use { r ->
                if (r.isSuccessful) r.body!!.string().trim().takeIf { it.matches(Regex("\\d+(\\.\\d+)+")) } else null
            }?.let { v -> return@runCatching Release(v, assetName(v), assetUrl(v), 0L) }
            val text = http.newCall(Request.Builder().url(RELEASES).header("Accept", "application/vnd.github+json").build()).execute().use { r ->
                check(r.isSuccessful) { if (r.code == 403 || r.code == 429) "GitHub is busy right now, try again in a while" else "GitHub ${r.code}" }; r.body!!.string()
            }
            val a = JSONArray(text)
            val best = (0 until a.length()).map { a.getJSONObject(it) }
                .filter { it.optString("tag_name").startsWith(prefix) && !it.optBoolean("draft") }
                .maxWithOrNull { p, q -> val pv = p.getString("tag_name").removePrefix(prefix); val qv = q.getString("tag_name").removePrefix(prefix)
                    if (newer(pv, qv)) 1 else if (newer(qv, pv)) -1 else 0 }
            best?.let { r ->
                val v = r.getString("tag_name").removePrefix(prefix)
                val assets = r.getJSONArray("assets")
                (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name") == assetName(v) }
                    ?.let { Release(v, it.getString("name"), it.getString("browser_download_url"), it.optLong("size")) }
            }
        }.onSuccess { r ->
            checkedAt = System.currentTimeMillis()
            latest = r
            state = if (r != null && newer(r.version, BuildInfo.VERSION)) State.AVAILABLE else State.UP_TO_DATE
        }.onFailure { error = "Couldn't check: ${it.message ?: "no connection"}"; state = State.FAILED }
    }

    /** Downloads the new version (to Downloads), ready to install. */
    suspend fun download() = withContext(Dispatchers.IO) {
        val r = latest ?: return@withContext
        state = State.DOWNLOADING; progress = 0f; error = null
        runCatching {
            val dir = File(System.getProperty("user.home"), "Downloads").takeIf { it.isDirectory } ?: File(System.getProperty("java.io.tmpdir"))
            val out = File(dir, r.asset)
            val part = File(dir, r.asset + ".part")
            http.newCall(Request.Builder().url(r.url).build()).execute().use { resp ->
                check(resp.isSuccessful) { "Download ${resp.code}" }
                val body = resp.body!!
                val total = body.contentLength().takeIf { it > 0 } ?: r.size
                body.byteStream().use { input ->
                    part.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024); var done = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            o.write(buf, 0, n); done += n
                            if (total > 0) progress = (done.toFloat() / total).coerceIn(0f, 1f)
                        }
                    }
                }
            }
            out.delete(); check(part.renameTo(out)) { "Couldn't save the download" }
            out
        }.onSuccess { file = it; state = State.READY }
            .onFailure { error = "Download didn't finish: ${it.message ?: "no connection"}"; state = State.FAILED }
    }

    /** This app's own bundle / program, when it was installed (not when run from a build). */
    private fun installed(): File? = ProcessHandle.current().info().command().orElse(null)?.let { File(it) }?.let { exe ->
        if (Platform.isMac) generateSequence(exe) { it.parentFile }.firstOrNull { it.name.endsWith(".app") } else exe.takeIf { it.name.startsWith("Lararium") }
    }

    /**
     * Installs the download and quits; the new version opens by itself (Mac, Windows). Returns what to tell you when it
     * can't be done from here (then the file just opens).
     */
    fun install(quit: () -> Unit): String? {
        val f = file ?: return "Download it first"
        val pid = ProcessHandle.current().pid()
        val app = installed()
        return runCatching {
            when {
                Platform.isMac && app != null -> {
                    val script = File.createTempFile("lararium-update", ".sh")
                    script.writeText("""
                        |#!/bin/bash
                        |while kill -0 $pid 2>/dev/null; do sleep 0.3; done
                        |dmg='${f.absolutePath}'; dest='${app.absolutePath}'
                        |mnt=${'$'}(mktemp -d)
                        |if ! hdiutil attach -nobrowse -quiet -mountpoint "${'$'}mnt" "${'$'}dmg"; then open "${'$'}dmg"; exit 0; fi
                        |src=${'$'}(ls -d "${'$'}mnt"/*.app | head -1)
                        |rm -rf "${'$'}dest.old"
                        |if mv "${'$'}dest" "${'$'}dest.old" && ditto "${'$'}src" "${'$'}dest"; then rm -rf "${'$'}dest.old"; else rm -rf "${'$'}dest"; mv "${'$'}dest.old" "${'$'}dest"; fi
                        |hdiutil detach -quiet "${'$'}mnt"
                        |xattr -dr com.apple.quarantine "${'$'}dest" 2>/dev/null
                        |open "${'$'}dest"
                        |rm -f "${'$'}dmg" "${'$'}0"
                        |""".trimMargin())
                    script.setExecutable(true)
                    ProcessBuilder("/bin/bash", script.absolutePath).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    quit(); null
                }
                Platform.isWindows && app != null -> {
                    val script = File.createTempFile("lararium-update", ".cmd")
                    script.writeText("""
                        |@echo off
                        |:wait
                        |tasklist /FI "PID eq $pid" 2>NUL | find "$pid" >NUL && (timeout /t 1 /nobreak >NUL & goto wait)
                        |msiexec /i "${f.absolutePath}" /passive /norestart
                        |start "" "${app.absolutePath}"
                        |del "${f.absolutePath}"
                        |""".trimMargin().replace("\n", "\r\n"))
                    ProcessBuilder("cmd.exe", "/c", script.absolutePath).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    quit(); null
                }
                else -> {
                    java.awt.Desktop.getDesktop().open(f)
                    if (Platform.isLinux) "Install it in the window that opened (it asks for your password), then open Lararium again. The file is in Downloads."
                    else "Opened the new version: install it, then open Lararium again"
                }
            }
        }.getOrElse { "Couldn't start the update (${it.message}). The new version is in Downloads: ${f.name}" }
    }
}
