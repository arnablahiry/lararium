package com.familydocs.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Unlocking with the computer's fingerprint reader, when it has one (the passcode always works too):
 *  - Mac: Touch ID, through a tiny helper the app carries (native/touchid.swift)
 *  - Windows: Windows Hello (fingerprint, face or its PIN), through PowerShell
 *  - Linux: fprintd (fprintd-verify), when a finger is enrolled
 * Nothing about your fingerprint ever reaches Lararium: the system checks it and only says yes or no.
 */
object Biometric {
    val label = when { Platform.isMac -> "Touch ID"; Platform.isWindows -> "Windows Hello"; else -> "Fingerprint" }

    @Volatile private var known: Boolean? = null

    /** The Mac's native helper (Touch ID, and reading text for [TextRead]). */
    internal val helper: File? get() = System.getProperty("compose.application.resources.dir")?.let { File(it, "Lararium") }
        ?.takeIf { it.isFile }?.also { if (!it.canExecute()) it.setExecutable(true) }

    private fun run(cmd: List<String>, seconds: Long): Pair<Int, String> = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(seconds, TimeUnit.SECONDS)) { p.destroyForcibly(); -1 to out } else p.exitValue() to out
    }.getOrDefault(-1 to "")

    private val hello by lazy {
        File.createTempFile("lararium-hello", ".ps1").apply {
            deleteOnExit()
            writeText("""
                |Add-Type -AssemblyName System.Runtime.WindowsRuntime
                |${'$'}asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.GetParameters().Count -eq 1 -and ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
                |function Await(${'$'}op, ${'$'}type) { ${'$'}t = ${'$'}asTask.MakeGenericMethod(${'$'}type).Invoke(${'$'}null, @(${'$'}op)); ${'$'}t.Wait(-1) | Out-Null; ${'$'}t.Result }
                |[Windows.Security.Credentials.UI.UserConsentVerifier,Windows.Security.Credentials.UI,ContentType=WindowsRuntime] | Out-Null
                |${'$'}v = [Windows.Security.Credentials.UI.UserConsentVerifier]
                |if (${'$'}args[0] -eq 'check') {
                |  ${'$'}r = Await (${'$'}v::CheckAvailabilityAsync()) ([Windows.Security.Credentials.UI.UserConsentVerifierAvailability])
                |  if ("${'$'}r" -eq 'Available') { exit 0 } else { exit 1 }
                |}
                |${'$'}r = Await (${'$'}v::RequestVerificationAsync('Unlock Lararium')) ([Windows.Security.Credentials.UI.UserConsentVerificationResult])
                |if ("${'$'}r" -eq 'Verified') { exit 0 } else { exit 1 }
                |""".trimMargin())
        }
    }
    private fun ps(arg: String, seconds: Long) = run(listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", hello.absolutePath, arg), seconds)

    /** Whether this computer can (has a reader with a finger set up). Asked once. */
    suspend fun available(): Boolean = known ?: withContext(Dispatchers.IO) {
        when {
            Platform.isMac -> helper?.let { run(listOf(it.absolutePath, "--check"), 10).first == 0 } ?: false
            Platform.isWindows -> ps("check", 20).first == 0
            else -> File("/usr/bin/fprintd-verify").exists() &&
                run(listOf("fprintd-list", System.getProperty("user.name")), 10).let { (code, out) -> code == 0 && out.contains("finger", ignoreCase = true) && !out.contains("no fingers", ignoreCase = true) }
        }.also { known = it }
    }

    /** Asks for your finger (or Windows Hello). True when it matched; false when cancelled or wrong. */
    suspend fun verify(): Boolean = withContext(Dispatchers.IO) {
        when {
            Platform.isMac -> helper?.let { run(listOf(it.absolutePath), 120).first == 0 } ?: false
            Platform.isWindows -> ps("verify", 120).first == 0
            else -> run(listOf("fprintd-verify"), 60).let { (code, out) -> code == 0 && out.contains("verify-match") }
        }
    }
}
