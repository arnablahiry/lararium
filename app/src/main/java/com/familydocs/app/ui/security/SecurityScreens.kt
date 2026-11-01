package com.familydocs.app.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familydocs.app.security.BiometricHelper
import com.familydocs.app.security.PasscodeManager
import com.familydocs.app.ui.components.LocalContainer
import com.familydocs.app.ui.components.rememberGoogleConnect
import com.familydocs.app.ui.ds.BtnKind
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdAvatar
import com.familydocs.app.ui.ds.FdButton
import com.familydocs.app.ui.ds.FdChip
import com.familydocs.app.ui.ds.FdDots
import com.familydocs.app.ui.ds.FdIcon
import com.familydocs.app.ui.ds.FdKeypad
import com.familydocs.app.ui.ds.FdSheet
import com.familydocs.app.ui.ds.FdTextButton
import com.familydocs.app.ui.ds.FdTopBar
import com.familydocs.app.ui.ds.SheetTitle
import com.familydocs.app.ui.ds.fdText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** `.pc` set-up: lock tile, title, 4/6-digit chips, dots, keypad; type it twice. */
@Composable
fun PasscodeSetupScreen(title: String, onBack: (() -> Unit)?, onComplete: (String) -> Unit) {
    val c = Fd.c
    var length by remember { mutableIntStateOf(6) }
    var first by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var shake by remember { mutableIntStateOf(0) }
    var sub by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        FdTopBar(title, onBack)
        Column(Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(60.dp).clip(RoundedCornerShape(20.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) { FdIcon("lock", 30.dp, tint = c.accent) }
            Text(if (first == null) "Create a passcode" else "Type it once more", style = fdText(20f, FontWeight.SemiBold), modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            Text(sub ?: if (first == null) "You will use it every time you open Lararium" else "Confirm your new passcode",
                style = fdText(13f, color = c.muted), textAlign = TextAlign.Center, modifier = Modifier.heightIn(min = 20.dp))
            if (first == null) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4, 6).forEach { n -> FdChip("$n digits", length == n, { length = n; pin = "" }) }
            }
            Spacer(Modifier.size(24.dp))
            FdDots(length, pin.length, shake)
            Spacer(Modifier.weight(1f))
            FdKeypad(
                onDigit = { d ->
                    if (pin.length < length) {
                        pin += d
                        if (pin.length == length) scope.launch {
                            delay(140)
                            val f = first
                            when {
                                f == null -> { first = pin; pin = ""; sub = null }
                                f == pin -> onComplete(pin)
                                else -> { shake++; pin = ""; sub = "Those didn't match. Start again."; delay(900); first = null }
                            }
                        }
                    }
                },
                onDelete = { pin = pin.dropLast(1) },
            )
        }
    }
}

/** Lock screen: your picture, "Welcome back", dots, keypad (fingerprint key when enabled), Forgot passcode. */
@Composable
fun LockScreen(activity: FragmentActivity) {
    val c = Fd.c
    val app = LocalContainer.current
    val account by app.repo.account.collectAsStateWithLifecycle(null)
    val self by app.repo.self().collectAsStateWithLifecycle(null)
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var shake by remember { mutableIntStateOf(0) }
    var sub by remember { mutableStateOf("Enter your passcode") }
    var lockedFor by remember { mutableLongStateOf(app.passcode.lockoutLeft()) }
    var forgot by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var forgotError by remember { mutableStateOf<String?>(null) }
    val bio = settings.biometric && BiometricHelper.available(activity)
    val len = app.passcode.length
    // Unlock animation (demo unlockAnim): dots → check, a soft ring around your picture, keypad drops away,
    // then the whole lock screen lifts and fades while the family screen settles in underneath.
    var success by remember { mutableStateOf(false) }
    val fadeOut = remember { androidx.compose.animation.core.Animatable(0f) }
    val ring = remember { androidx.compose.animation.core.Animatable(0f) }
    val lift = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(success) {
        if (!success) return@LaunchedEffect
        launch { delay(100); ring.animateTo(1f, androidx.compose.animation.core.tween(750)) }
        launch { delay(120); fadeOut.animateTo(1f, androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.CubicBezierEasing(.5f, 0f, .75f, 0f))) }
        delay(620)
        app.appLock.beginUnlock()
        lift.animateTo(1f, androidx.compose.animation.core.tween(500, easing = androidx.compose.animation.core.CubicBezierEasing(.55f, 0f, .75f, .2f)))
        app.appLock.unlock()
    }
    fun unlocked() { if (!success) { pin = "1".repeat(len); success = true } }

    fun biometric() = BiometricHelper.authenticate(activity, "Unlock Lararium", onSuccess = { unlocked() })
    LaunchedEffect(Unit) { if (bio) biometric() }
    LaunchedEffect(lockedFor > 0) {
        while (lockedFor > 0) { sub = "Too many tries. Wait ${(lockedFor + 999) / 1000} s"; delay(500); lockedFor = app.passcode.lockoutLeft() }
        sub = "Enter your passcode"
    }

    if (resetting) {
        PasscodeSetupScreen("Change passcode", onBack = { resetting = false }) { code ->
            scope.launch { withContext(Dispatchers.Default) { app.passcode.set(code) }; app.appLock.unlock() }
        }
        return
    }

    Column(Modifier.fillMaxSize().graphicsLayer {
            val v = lift.value; translationY = -size.height * .08f * v; scaleX = 1f + .03f * v; scaleY = 1f + .03f * v; alpha = 1f - v
        }.background(c.bg).statusBarsPadding().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 34.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            if (success) Box(Modifier.size(90.dp).graphicsLayer { val v = ring.value; val k = .92f + .53f * v; scaleX = k; scaleY = k; alpha = (.9f * (1f - v)).coerceAtLeast(0f) }
                .border(2.dp, c.ok, CircleShape))
            FdAvatar(account?.name ?: "", 84.dp, photoFile = self?.photoFile ?: account?.photoFile, character = self?.avatar, lift = true)
        }
        Text("Welcome back${account?.name?.let { ", " + it.substringBefore(' ') } ?: ""}", style = fdText(20f, FontWeight.SemiBold), modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        Text(sub, style = fdText(13f, color = c.muted), modifier = Modifier.heightIn(min = 20.dp).graphicsLayer { alpha = 1f - fadeOut.value })
        // for someone helping in an emergency: the emergency cards only, no passcode needed
        EmergencyButton(Modifier.padding(top = 10.dp).graphicsLayer { alpha = 1f - fadeOut.value })
        Spacer(Modifier.size(24.dp))
        FdDots(len, pin.length, shake, success)
        Spacer(Modifier.weight(1f))
        Column(Modifier.graphicsLayer { val v = fadeOut.value; alpha = 1f - v; translationY = 16.dp.toPx() * v }, horizontalAlignment = Alignment.CenterHorizontally) {
        FdKeypad(
            onDigit = { d ->
                if (!success && lockedFor == 0L && pin.length < len) {
                    pin += d
                    if (pin.length == len) scope.launch {
                        delay(140)
                        when (val r = withContext(Dispatchers.Default) { app.passcode.check(pin) }) {
                            PasscodeManager.Check.Ok -> unlocked()
                            is PasscodeManager.Check.Wrong -> { shake++; pin = ""; sub = "Wrong passcode · ${r.triesLeft} tries left" }
                            is PasscodeManager.Check.LockedOut -> { shake++; pin = ""; lockedFor = r.millisLeft }
                        }
                    }
                }
            },
            onDelete = { pin = pin.dropLast(1) },
            onBiometric = if (bio) ({ biometric() }) else null,
        )
        Box(Modifier.padding(top = 14.dp)) { FdTextButton("Forgot passcode?", { forgot = true; forgotError = null }) }
        }
    }

    val verifyGoogle = rememberGoogleConnect { r ->
        val email = account?.backupEmail.orEmpty()
        r.onSuccess { g ->
            if (g.profile.email.equals(email, ignoreCase = true)) {
                // Second check: the phone's own screen lock, so holding an unlocked phone with Google signed in isn't enough.
                if (com.familydocs.app.security.BiometricHelper.deviceSecure(activity)) {
                    app.appLock.expectExternalActivity()
                    com.familydocs.app.security.BiometricHelper.confirmDevice(activity, onSuccess = { forgot = false; resetting = true },
                        onFail = { forgotError = "The phone's screen lock wasn't confirmed ($it), so the passcode stays as it is." })
                } else { forgot = false; resetting = true }
            }
            else forgotError = "That Google account (${g.profile.email}) isn't the one this vault backs up to."
        }.onFailure { forgotError = com.familydocs.app.auth.GoogleAccountHelper.explain(it) }
    }

    if (forgot) {
        val email = account?.backupEmail.orEmpty()
        FdSheet({ forgot = false }) {
            SheetTitle("Forgot your passcode?",
                forgotError ?: if (email.isNotBlank() && account?.googleLinked == true) "Two quick checks: sign in with Google as ${mask(email)}, then unlock with this phone's own screen lock. Then you choose a new passcode. Your documents stay as they are."
                else "Passcode recovery needs your Google account linked (Settings → Your account). Without it the vault can't be unlocked, by design.")
            if (account?.googleLinked == true) FdButton("Verify with Google", verifyGoogle)
            Spacer(Modifier.size(10.dp))
            FdButton("Cancel", { forgot = false }, kind = BtnKind.Ghost)
        }
    }
}

private fun mask(email: String): String {
    val at = email.indexOf('@'); if (at < 2) return email
    return email.first() + "•".repeat(minOf(at - 1, 6)) + email.substring(at)
}
