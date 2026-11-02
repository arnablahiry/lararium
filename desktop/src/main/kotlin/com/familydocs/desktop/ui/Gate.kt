package com.familydocs.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.familydocs.app.ui.ds.Bitcount
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdAvatar
import com.familydocs.app.ui.ds.FdButton
import com.familydocs.app.ui.ds.FdDots
import com.familydocs.app.ui.ds.FdKeypad
import com.familydocs.app.ui.ds.FdMadeWith
import com.familydocs.app.ui.ds.FdProgress
import com.familydocs.app.ui.ds.FdTextButton
import com.familydocs.app.ui.ds.fdText
import com.familydocs.desktop.Passcode
import com.familydocs.desktop.rememberApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app's name as on the phone's Home header (Bitcount Grid Double). */
@Composable
fun Wordmark(size: Float = 34f) {
    Text("Lararium", style = fdText(size, color = Fd.c.fg, spacing = .02f).copy(fontFamily = Bitcount))
}

/** A centred message: loading, a problem, or this build not being set up. */
@Composable
fun StatusPage(title: String, text: String, busy: Boolean = false, action: Pair<String, () -> Unit>? = null, secondary: Pair<String, () -> Unit>? = null) {
    val c = Fd.c
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Wordmark()
            Text(title, style = fdText(18f, FontWeight.SemiBold), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 28.dp))
            if (text.isNotBlank()) Text(text, style = fdText(13f, color = c.muted), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            if (busy) FdProgress(Modifier.padding(top = 22.dp).widthIn(max = 260.dp))
            action?.let { (label, go) -> FdButton(label, go, Modifier.padding(top = 22.dp)) }
            secondary?.let { (label, go) -> Box(Modifier.padding(top = 8.dp)) { FdTextButton(label, go) } }
        }
    }
}

/** First time on this Mac: sign in with the Google account the phone backs up to. */
@Composable
fun SignInPage() {
    val app = rememberApp()
    val c = Fd.c
    var waiting by remember { mutableStateOf(false) }
    LaunchedEffect(app.error) { if (app.error != null) waiting = false }
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 440.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Wordmark(40f)
            Text("Your family's documents, on your ${com.familydocs.desktop.Platform.device}", style = fdText(15f, color = c.muted), modifier = Modifier.padding(top = 10.dp))
            Column(Modifier.padding(top = 30.dp).fillMaxWidth().background(c.surface, androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                .border(1.dp, c.line, androidx.compose.foundation.shape.RoundedCornerShape(22.dp)).padding(22.dp)) {
                Text("Sign in with the Google account your phone backs up to.", style = fdText(14f, FontWeight.SemiBold))
                Text("Lararium reads your encrypted backup and your group's shares from Google Drive, and only its own files. " +
                    "Then unlock with your usual Lararium passcode.", style = fdText(12.5f, color = c.muted), modifier = Modifier.padding(top = 6.dp))
                FdButton(if (waiting) "Finish signing in in your browser…" else "Continue with Google", {
                    waiting = true; app.signIn()
                }, Modifier.padding(top = 18.dp), icon = "user", enabled = !waiting)
                app.error?.let { Text(it, style = fdText(12.5f, color = c.danger), modifier = Modifier.padding(top = 10.dp)) }
                if (waiting) Box(Modifier.padding(top = 6.dp).align(Alignment.CenterHorizontally)) { FdTextButton("Cancel", { waiting = false }) }
            }
            Text("This first version shows everything; to add or change things, use the phone.", style = fdText(12f, color = c.muted),
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
        }
    }
}

/** Emergency cards, readable without the passcode (Settings › Security). */
@Composable
private fun EmergencySheet(cards: List<com.familydocs.app.data.DocumentEntity>, people: List<com.familydocs.app.data.MemberEntity>, onDismiss: () -> Unit) {
    val c = Fd.c
    com.familydocs.app.ui.ds.FdSheet(onDismiss) {
        com.familydocs.app.ui.ds.SheetTitle("Emergency info", "What's on the emergency cards in this vault.")
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            cards.forEach { d ->
                Column(Modifier.fillMaxWidth().background(c.surface2, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(14.dp)) {
                    Text(people.firstOrNull { it.id == d.memberId }?.name ?: d.name, style = fdText(15f, FontWeight.Bold))
                    d.fields.filter { it.value.isNotBlank() }.forEach { f ->
                        Text(f.label, style = fdText(11f, color = c.muted), modifier = Modifier.padding(top = 8.dp))
                        Text(f.value, style = fdText(14.5f, FontWeight.SemiBold))
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        FdButton("Close", onDismiss)
    }
}

/** The phone's lock screen: your picture, "Welcome back", the dots and the keypad; the keyboard's digits work too. */
@Composable
fun LockPage() {
    val app = rememberApp()
    val c = Fd.c
    val pass = app.passcode ?: run { app.unlocked(); return }
    val data = app.data
    val self = data?.members?.firstOrNull { it.isSelf }
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var shake by remember { mutableIntStateOf(0) }
    var sub by remember { mutableStateOf("Enter your passcode") }
    var success by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var wrong by remember { mutableIntStateOf(0) }
    var emergency by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf<com.familydocs.app.ui.ds.KeyFlash?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // the fingerprint reader, when there is one: asked for straight away, and with the key next to 0
    val bio = app.biometricOn && app.biometricHere
    var asking by remember { mutableStateOf(false) }
    fun fingerprint() {
        if (asking || success) return
        asking = true
        scope.launch {
            val ok = com.familydocs.desktop.Biometric.verify()
            asking = false
            if (ok) { success = true; pin = "•".repeat(pass.length); delay(600); app.unlocked() } else focus.requestFocus()
        }
    }
    LaunchedEffect(bio) { if (bio) { delay(350); fingerprint() } }
    fun lightUp(k: String) { flash = com.familydocs.app.ui.ds.KeyFlash(k, (flash?.serial ?: 0) + 1) }
    val cards = data?.docs.orEmpty().filter { com.familydocs.app.domain.DocTypes.forName(it.name).key == "emergency" && !it.archived && it.eventId == null }

    fun digit(d: Char) {
        if (success || checking || pin.length >= pass.length || pass.lockedFor() > 0) return
        pin += d
        if (pin.length == pass.length) scope.launch {
            checking = true
            delay(120)
            when (val r = withContext(Dispatchers.Default) { pass.check(pin) }) {
                Passcode.Check.Ok -> { success = true; delay(700); app.unlocked() }
                is Passcode.Check.Wrong -> { shake++; wrong++; pin = ""; sub = "Wrong passcode · ${r.left} tries left" }
                is Passcode.Check.Wait -> { shake++; pin = ""; sub = "Too many tries. Wait ${(r.ms + 999) / 1000} s" }
            }
            checking = false
        }
    }

    Box(Modifier.fillMaxSize().background(c.bg).focusRequester(focus).focusable().onKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
        val ch = e.utf16CodePoint.toChar()
        when {
            ch in '0'..'9' -> { lightUp(ch.toString()); digit(ch); true }
            e.key == Key.Backspace -> { lightUp("Delete"); if (!success) pin = pin.dropLast(1); true }
            else -> false
        }
    }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                if (success) Box(Modifier.size(90.dp).border(2.dp, c.ok, CircleShape))
                FdAvatar(data?.account?.name ?: self?.name ?: "", 84.dp, photoFile = self?.photoFile ?: data?.account?.photoFile, character = self?.avatar, lift = true)
            }
            Text("Welcome back${(data?.account?.name ?: self?.name)?.let { ", " + it.substringBefore(' ') } ?: ""}", style = fdText(20f, FontWeight.SemiBold),
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            Text(sub, style = fdText(13f, color = c.muted))
            Spacer(Modifier.height(24.dp))
            FdDots(pass.length, pin.length, shake, success)
            Spacer(Modifier.height(34.dp))
            FdKeypad(onDigit = ::digit, onDelete = { if (!success) pin = pin.dropLast(1) }, onBiometric = if (bio) ({ fingerprint() }) else null, flash = flash)
            if (emergency) EmergencySheet(cards, data?.members.orEmpty()) { emergency = false; focus.requestFocus() }
            Text(if (bio) "Type it, click the keys, or use ${com.familydocs.desktop.Biometric.label}" else "Type it, or click the keys", style = fdText(12f, color = c.muted),
                modifier = Modifier.padding(top = 18.dp))
            if (app.emergencyOnLock && cards.isNotEmpty()) Box(Modifier.padding(top = 14.dp)) {
                com.familydocs.app.ui.ds.FdChip("Emergency info", false, { emergency = true },
                    leading = { com.familydocs.app.ui.ds.FdIcon("medical", 15.dp, tint = c.danger) })
            }
            // forgot it: signing out (and in again) is the way back; offered only after a few wrong tries
            if (wrong >= 3) Box(Modifier.padding(top = 6.dp)) { FdTextButton("Forgot it? Sign out", { app.signOut() }, color = c.muted) }
        }
    }
}

