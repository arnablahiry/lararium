package com.familydocs.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.familydocs.app.ui.ds.FdTheme
import com.familydocs.app.ui.ds.FdToastHost
import com.familydocs.app.ui.ds.FdToastState
import com.familydocs.app.ui.ds.LocalToast
import com.familydocs.desktop.ui.LockPage
import com.familydocs.desktop.ui.Shell
import com.familydocs.desktop.ui.SignInPage
import com.familydocs.desktop.ui.StatusPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.prefs.Preferences

/** Where you are in the app. */
sealed interface Page {
    data object Home : Page
    data class Person(val id: String) : Page
    data class Doc(val id: String) : Page
    data object Events : Page
    data class Event(val id: String) : Page
    data class Search(val query: String) : Page
    data object Settings : Page
}

enum class Theme { SYSTEM, LIGHT, DARK }

/** The Mac app's state: signed in or not, the vault as last read from Drive, locked or not, and where you are. */
class AppModel(private val scope: CoroutineScope) {
    private val prefs = Preferences.userRoot().node("lararium")

    enum class Stage { SETUP, SIGN_IN, LOADING, LOCKED, READY, ERROR }
    var stage by mutableStateOf(if (!Google.configured) Stage.SETUP else if (Google.hasSession()) Stage.LOADING else Stage.SIGN_IN)
    var progress by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    /** The vault as read from Drive, and as shown: with your changes here that the phone hasn't taken over yet. */
    var base by mutableStateOf<VaultData?>(null); private set
    var data by mutableStateOf<VaultData?>(null); private set
    /** How many of your changes here are still on their way to the phone. */
    var waiting by mutableStateOf(0); private set
    var saving by mutableStateOf(false); private set
    var refreshing by mutableStateOf(false)
    var email by mutableStateOf(prefs.get("email", ""))
    var passcode: Passcode? = null
    var theme by mutableStateOf(runCatching { Theme.valueOf(prefs.get("theme", "SYSTEM")) }.getOrDefault(Theme.SYSTEM))
        private set
    var group by mutableStateOf<String?>(prefs.get("group", null))
        private set
    /** Lock again after this many minutes away (0 = only when you lock it). */
    var lockAfterMin by mutableStateOf(prefs.getInt("lock_after", 5)); private set
    fun chooseLockAfter(m: Int) { lockAfterMin = m; prefs.putInt("lock_after", m) }
    /** Emergency cards readable from the lock screen, as on the phone. */
    var emergencyOnLock by mutableStateOf(prefs.getBoolean("emergency_lock", true)); private set
    fun chooseEmergencyOnLock(on: Boolean) { emergencyOnLock = on; prefs.putBoolean("emergency_lock", on) }
    /** Unlock with the computer's fingerprint reader (Touch ID, Windows Hello…) when it has one. */
    var biometricOn by mutableStateOf(prefs.getBoolean("biometric", true)); private set
    fun chooseBiometric(on: Boolean) { biometricOn = on; prefs.putBoolean("biometric", on) }
    /** Whether this computer has one (checked once, at start). */
    var biometricHere by mutableStateOf(false); private set
    fun checkBiometric() = scope.launch { biometricHere = runCatching { Biometric.available() }.getOrDefault(false) }
    val stack = mutableStateListOf<Page>(Page.Home)
    val page: Page get() = stack.last()
    /** When the window was last left (for locking after a while away). */
    var awaySince = 0L

    fun chooseTheme(t: Theme) { theme = t; prefs.put("theme", t.name) }
    /** Shows group [id]; back to its Home unless [stay] (Settings opening a group's preferences). */
    fun selectGroup(id: String, stay: Boolean = false) { group = id; prefs.put("group", id); if (!stay) { stack.clear(); stack += Page.Home } }
    fun go(p: Page) { if (stack.lastOrNull() != p) stack += p }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun home() { stack.clear(); stack += Page.Home }

    /** The group shown: the one you picked, else the first. */
    val currentGroup get() = data?.groups?.let { gs -> gs.firstOrNull { it.id == group } ?: gs.firstOrNull() }

    fun signIn() = scope.launch {
        error = null
        runCatching { Google.signIn() }.onFailure { error = it.message ?: "Sign-in didn't finish" }.onSuccess {
            runCatching { Google.profile() }.onSuccess { email = it.email; prefs.put("email", it.email) }
            stage = Stage.LOADING; load(first = true)
        }
    }

    fun load(first: Boolean = false) = scope.launch {
        if (refreshing) return@launch
        refreshing = true
        runCatching { Vault.load { progress = it } }
            .onSuccess { d ->
                base = d; shown()
                if (group == null || d.groups.none { it.id == group }) d.groups.firstOrNull()?.let { group = it.id }
                if (first) { passcode = Passcode.parse(d.passcode); stage = if (passcode != null) Stage.LOCKED else Stage.READY }
                error = null
            }
            .onFailure { e ->
                if (first || data == null) { error = e.message ?: "Couldn't read your vault"; stage = if (Google.hasSession()) Stage.ERROR else Stage.SIGN_IN }
                else error = "Couldn't refresh: ${e.message}"
            }
        refreshing = false
    }

    private fun shown() { val b = base ?: return; data = Desk.overlay(b); waiting = Desk.pending.size }

    /**
     * A change made here: shown at once, then sent to the phone (which takes it over within seconds while Lararium is
     * open there, or the next time it is). [done] runs after it's on its way, with a problem to show if it isn't.
     */
    fun edit(build: suspend () -> org.json.JSONObject, done: (String?) -> Unit = {}) = scope.launch {
        saving = true
        val r = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Desk.push(build()) } }
        saving = false
        shown()
        done(r.exceptionOrNull()?.let { "Couldn't save: ${it.message ?: "no connection"}" })
    }

    fun lock() { if (passcode != null && stage == Stage.READY) stage = Stage.LOCKED }
    fun unlocked() { stage = Stage.READY; awaySince = 0L }

    fun signOut() {
        Google.signOut(); Files.clear(); Desk.forget(); base = null; data = null; waiting = 0; passcode = null; email = ""; prefs.remove("email")
        stack.clear(); stack += Page.Home; stage = Stage.SIGN_IN
    }
}

val LocalApp = staticCompositionLocalOf<AppModel> { error("no app") }

fun main() {
    // macOS: light or dark title bar with the system, and the app's name in the menu bar
    System.setProperty("apple.awt.application.appearance", "system")
    System.setProperty("apple.awt.application.name", "Lararium")
    val app = AppModel(kotlinx.coroutines.MainScope())
    application {
    // opens filling the screen (not full screen): every page is laid out for any size you then make the window
    val state = rememberWindowState(placement = androidx.compose.ui.window.WindowPlacement.Maximized, width = 1440.dp, height = 900.dp,
        position = WindowPosition.PlatformDefault)
    Window(onCloseRequest = ::exitApplication, title = "Lararium", state = state,
        icon = remember { com.familydocs.app.ui.ds.Res.get("app_icon")?.let { androidx.compose.ui.graphics.painter.BitmapPainter(it) } },
        onPreviewKeyEvent = { e -> shortcut(app, e) }) {
        LaunchedEffect(Unit) {
            MainWindow.frame = window
            window.minimumSize = java.awt.Dimension(960, 640)
            window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
            window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        }
        // Away for 5 minutes (another app, minimised): locked again when you come back. Refreshes every few minutes too.
        LaunchedEffect(Unit) {
            window.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
                override fun windowLostFocus(e: java.awt.event.WindowEvent?) { app.awaySince = System.currentTimeMillis() }
                override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {
                    val away = app.awaySince
                    if (away > 0 && app.lockAfterMin > 0 && System.currentTimeMillis() - away > app.lockAfterMin * 60_000L) app.lock()
                    if (away > 0 && System.currentTimeMillis() - away > 60_000L && app.stage == AppModel.Stage.READY) app.load()
                    app.awaySince = 0L
                }
            })
            app.checkBiometric()
            if (app.stage == AppModel.Stage.LOADING) app.load(first = true)
            // a newer version of this app: Settings › About shows it (checked again every 6 hours)
            launch { while (true) { Updater.check(); delay(6 * 3600_000L) } }
            // every few minutes; every half minute while changes made here are on their way to the phone
            var n = 0
            while (true) {
                delay(20_000L); n++
                if (app.stage != AppModel.Stage.READY) continue
                if (app.waiting > 0 && runCatching { Desk.anyDone() }.getOrDefault(false)) app.load()
                else if (n % 9 == 0 && window.isFocused) app.load()
            }
        }
        val dark = when (app.theme) { Theme.SYSTEM -> isSystemInDarkTheme(); Theme.LIGHT -> false; Theme.DARK -> true }
        val toast = remember { FdToastState() }
        FdTheme(dark) {
            CompositionLocalProvider(LocalApp provides app, LocalToast provides toast) {
                Box(Modifier.fillMaxSize()) {
                    when (app.stage) {
                        AppModel.Stage.SETUP -> StatusPage("This copy of Lararium isn't set up for Google sign-in",
                            "It was built without the Mac app's Google client. Download the Mac app from the Lararium releases page instead.")
                        AppModel.Stage.SIGN_IN -> SignInPage()
                        AppModel.Stage.LOADING -> StatusPage("Opening your vault…", app.progress, busy = true)
                        AppModel.Stage.ERROR -> StatusPage("Couldn't open your vault", app.error.orEmpty(), action = "Try again" to { app.stage = AppModel.Stage.LOADING; app.load(first = true) },
                            secondary = "Sign out" to { app.signOut() })
                        AppModel.Stage.LOCKED -> LockPage()
                        AppModel.Stage.READY -> Shell()
                    }
                    // "Made with love" at the bottom of the window on every page (the main window has its own)
                    if (app.stage != AppModel.Stage.READY) com.familydocs.app.ui.ds.FdMadeWith(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 14.dp))
                    FdToastHost(toast)
                }
            }
        }
    }
    }
}

/** ⌘R / Ctrl+R refresh, ⌘L lock, ⌘F search, Esc back. */
private fun shortcut(app: AppModel, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown || app.stage != AppModel.Stage.READY) return false
    return when {
        Platform.shortcut(e) && e.key == Key.R -> { app.load(); true }
        Platform.shortcut(e) && e.key == Key.L -> { app.lock(); true }
        Platform.shortcut(e) && e.key == Key.F -> { app.focusSearch?.invoke(); true }
        Platform.shortcut(e) && e.key == Key.Comma -> { app.go(Page.Settings); true }
        e.key == Key.Escape && app.stack.size > 1 -> { app.back(); true }
        else -> false
    }
}

/** Set by the sidebar's search box: ⌘F puts the cursor there. */
var AppModel.focusSearch: (() -> Unit)?
    get() = focusSearchHolder[this]
    set(v) { if (v == null) focusSearchHolder.remove(this) else focusSearchHolder[this] = v }
private val focusSearchHolder = java.util.WeakHashMap<AppModel, () -> Unit>()

@Composable
fun rememberApp(): AppModel = LocalApp.current
