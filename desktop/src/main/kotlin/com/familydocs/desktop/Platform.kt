package com.familydocs.desktop

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed

/** The few things that differ between the Mac and Windows apps (everything else is the same code and the same look). */
object Platform {
    private val os = System.getProperty("os.name").orEmpty().lowercase()
    val isMac = "mac" in os
    val isWindows = "win" in os
    val isLinux = !isMac && !isWindows

    /** "⌘" on a Mac, "Ctrl+" on Windows, for shortcut hints. */
    val mod = if (isMac) "⌘" else "Ctrl+"
    /** The computer, in sentences ("on your Mac"). */
    val device = if (isMac) "Mac" else if (isWindows) "PC" else "computer"
    /** What opening a file does here. */
    val openLabel = if (isMac) "Open in Preview" else "Open"
    /** Room at the top of the sidebar: on a Mac the window's buttons sit there (the content runs under the title bar). */
    val topInset = if (isMac) 40 else 16

    /** The shortcut key: ⌘ on a Mac, Ctrl on Windows. */
    fun shortcut(e: KeyEvent) = if (isMac) e.isMetaPressed else e.isCtrlPressed
}

/**
 * Where the Google sign-in is kept, never in a plain file: the macOS Keychain on a Mac; on Windows a file only your
 * Windows account can open (Windows' own data protection, DPAPI); on Linux the desktop's keyring (GNOME Keyring or
 * KWallet, through secret-tool), or, without one, a file only you can read.
 */
object Keychain {
    private const val SERVICE = "Lararium Google sign-in"
    private const val ACCOUNT = "lararium"

    private fun run(cmd: List<String>, env: Map<String, String> = emptyMap()): Pair<Int, String> = runCatching {
        val pb = ProcessBuilder(cmd).redirectErrorStream(false)
        pb.environment().putAll(env)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor() to out
    }.getOrDefault(1 to "")

    private val winFile: java.io.File by lazy {
        java.io.File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Lararium").apply { mkdirs() }.resolve("signin.dat")
    }
    private val otherFile: java.io.File by lazy { java.io.File(System.getProperty("user.home"), ".lararium").apply { mkdirs() }.resolve("signin") }
    private const val PS = "powershell.exe"

    fun read(): String? = when {
        Platform.isMac -> run(listOf("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", ACCOUNT, "-w")).takeIf { it.first == 0 }?.second
        Platform.isWindows -> if (!winFile.exists()) null else run(listOf(PS, "-NoProfile", "-NonInteractive", "-Command",
            "\$s = Get-Content -Raw -LiteralPath \$env:LARARIUM_FILE | ConvertTo-SecureString; " +
                "[Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR(\$s))"),
            mapOf("LARARIUM_FILE" to winFile.path)).takeIf { it.first == 0 }?.second
        else -> run(listOf("secret-tool", "lookup", "service", "lararium", "account", ACCOUNT)).takeIf { it.first == 0 && it.second.isNotEmpty() }?.second
            ?: otherFile.takeIf { it.exists() }?.readText()?.trim()
    }?.takeIf { it.isNotEmpty() }

    /** Linux: into the keyring (the token goes in on secret-tool's input, never on a command line). */
    private fun keyring(token: String): Boolean = runCatching {
        val p = ProcessBuilder("secret-tool", "store", "--label=$SERVICE", "service", "lararium", "account", ACCOUNT).start()
        p.outputStream.use { it.write(token.toByteArray()) }
        p.waitFor() == 0
    }.getOrDefault(false)

    fun write(token: String) {
        when {
            Platform.isMac -> run(listOf("/usr/bin/security", "add-generic-password", "-U", "-s", SERVICE, "-a", ACCOUNT, "-w", token))
            // the token goes in through the environment, never on a command line
            Platform.isWindows -> run(listOf(PS, "-NoProfile", "-NonInteractive", "-Command",
                "ConvertTo-SecureString -String \$env:LARARIUM_SECRET -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath \$env:LARARIUM_FILE"),
                mapOf("LARARIUM_SECRET" to token, "LARARIUM_FILE" to winFile.path))
            keyring(token) -> otherFile.delete()
            else -> otherFile.apply { writeText(token); setReadable(false, false); setReadable(true, true); setWritable(false, false); setWritable(true, true) }
        }
    }

    fun delete() {
        when {
            Platform.isMac -> run(listOf("/usr/bin/security", "delete-generic-password", "-s", SERVICE, "-a", ACCOUNT))
            Platform.isWindows -> winFile.delete()
            else -> { run(listOf("secret-tool", "clear", "service", "lararium", "account", ACCOUNT)); otherFile.delete() }
        }
    }
}
