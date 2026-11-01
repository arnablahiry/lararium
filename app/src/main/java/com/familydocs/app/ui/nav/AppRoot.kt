package com.familydocs.app.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.familydocs.app.ui.components.LocalContainer
import com.familydocs.app.ui.components.appViewModel
import com.familydocs.app.ui.document.AddDocumentScreen
import com.familydocs.app.ui.document.DocumentScreen
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdEase
import com.familydocs.app.ui.ds.FdToastHost
import com.familydocs.app.ui.ds.LocalToast
import com.familydocs.app.ui.home.FamilyScreen
import com.familydocs.app.ui.events.EditEventScreen
import com.familydocs.app.ui.events.EventScreen
import com.familydocs.app.ui.member.EditMemberScreen
import com.familydocs.app.ui.member.MemberScreen
import com.familydocs.app.ui.onboarding.OnboardingFlow
import com.familydocs.app.ui.onboarding.OnboardingViewModel
import com.familydocs.app.ui.onboarding.RegisterScreen
import com.familydocs.app.ui.passwords.PasswordsScreen
import com.familydocs.app.ui.security.LockScreen
import com.familydocs.app.ui.security.PasscodeSetupScreen
import com.familydocs.app.ui.settings.SettingsScreen
import com.familydocs.app.ui.ds.cropBoxPx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Screen changes glide on one axis: forward, the new screen comes in from the right while the old one drifts a little
// left and fades; back, the reverse (and with the back gesture, it follows your finger before you let go).
private const val NAV_MS = 380
private fun <T> glide() = tween<T>(NAV_MS, easing = FdEase)
fun AnimatedContentTransitionScope<NavBackStackEntry>.fdEnter(): EnterTransition =
    slideInHorizontally(glide()) { it / 4 } + fadeIn(tween(240, delayMillis = 70))
fun AnimatedContentTransitionScope<NavBackStackEntry>.fdExit(): ExitTransition =
    slideOutHorizontally(glide()) { -it / 10 } + fadeOut(tween(200))
fun AnimatedContentTransitionScope<NavBackStackEntry>.fdPopEnter(): EnterTransition =
    slideInHorizontally(glide()) { -it / 10 } + fadeIn(tween(240, delayMillis = 70))
fun AnimatedContentTransitionScope<NavBackStackEntry>.fdPopExit(): ExitTransition =
    slideOutHorizontally(glide()) { it / 4 } + fadeOut(tween(200))

/** Onboarding until an account + passcode exist; afterwards the vault, with the lock screen drawn on top while locked. */
@Composable
fun AppRoot(activity: FragmentActivity) {
    val c = LocalContainer.current
    val registeredFlow = remember { combine(c.repo.account, c.passcode.isSetFlow) { a, pc -> a != null && pc } }
    val registered by registeredFlow.collectAsStateWithLifecycle(initialValue = null)
    val locked by c.appLock.locked.collectAsStateWithLifecycle()
    val toast = LocalToast.current

    Box(Modifier.fillMaxSize().background(Fd.c.bg)) {
        when (registered) {
            null -> Unit
            false -> OnboardingFlow(onDone = { c.appLock.unlock() })
            true -> {
                val unlocking by c.appLock.unlocking.collectAsStateWithLifecycle()
                val settle by androidx.compose.animation.core.animateFloatAsState(if (locked && !unlocking) 0f else 1f,
                    if (locked && !unlocking) androidx.compose.animation.core.snap<Float>() else tween<Float>(660, easing = FdEase), label = "settle")
                Box(Modifier.fillMaxSize().graphicsLayer { val k = .94f + .06f * settle; scaleX = k; scaleY = k; alpha = settle }) { VaultNav() }
                if (locked) LockScreen(activity)
            }
        }
        FdToastHost(toast)
    }
}

@Composable
private fun VaultNav() {
    val c = LocalContainer.current
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val idArg = listOf(navArgument("id") { type = NavType.StringType })
    val box = cropBoxPx()
    // a renewed document was just added: put the old one away?
    val offer by c.archiveOffer.collectAsStateWithLifecycle()
    offer?.let { (name, ids) ->
        val what = com.familydocs.app.domain.DocTypes.forName(name).let { if (it.matched) it.label.lowercase() else name }
        com.familydocs.app.ui.ds.FdConfirmSheet(if (ids.size == 1) "Archive the old $what?" else "Archive the ${ids.size} older ones?",
            "It moves to Archived at the bottom of the vault: still there and searchable, but out of the wallet and expiry reminders.", "Archive",
            onConfirm = { scope.launch { ids.forEach { runCatching { c.repo.setArchived(it, true) } }; c.archiveOffer.value = null; toast.show("Archived") } },
            onDismiss = { c.archiveOffer.value = null }, danger = false)
    }
    // from an expiry reminder
    val openDoc by c.openDocument.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(openDoc) { openDoc?.let { c.openDocument.value = null; nav.navigate("doc/$it") } }

    NavHost(nav, startDestination = "home", enterTransition = { fdEnter() }, exitTransition = { fdExit() },
        popEnterTransition = { fdPopEnter() }, popExitTransition = { fdPopExit() }) {
        composable("home") {
            FamilyScreen(
                onMember = { nav.navigate("member/$it") },
                onSettings = { nav.navigate("settings") },
                onUpdates = { nav.navigate("settings?to=updates") },
                onDocument = { nav.navigate("doc/$it") },
                onEvent = { nav.navigate("event/$it") },
                onNewEvent = { nav.navigate("event-edit") },
            )
        }
        composable("member/{id}", idArg) { e ->
            val id = e.arguments!!.getString("id")!!
            MemberScreen(
                id, onBack = { nav.popBackStack() }, onEdit = { nav.navigate("member-edit?id=$id") },
                onPasswords = { nav.navigate("passwords/$id") }, onDocument = { nav.navigate("doc/$it") },
                onAddDocument = { nav.navigate("doc-edit/$id") },
            )
        }
        composable("member-edit?id={id}", listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
            EditMemberScreen(e.arguments?.getString("id"), onBack = { nav.popBackStack() }, onDeleted = { nav.popBackStack("home", inclusive = false) })
        }
        composable("doc/{id}", idArg) { e ->
            val id = e.arguments!!.getString("id")!!
            DocumentScreen(id, onBack = { nav.popBackStack() }, onEdit = { memberId -> nav.navigate("doc-edit/$memberId?docId=$id") })
        }
        composable(
            "doc-edit/{memberId}?docId={docId}&eventId={eventId}",
            listOf(
                navArgument("memberId") { type = NavType.StringType },
                navArgument("docId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("eventId") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { e ->
            AddDocumentScreen(e.arguments!!.getString("memberId")!!, e.arguments?.getString("docId"), onBack = { nav.popBackStack() },
                onSaved = { nav.popBackStack(); toast.show("Saved") }, eventId = e.arguments?.getString("eventId"))
        }
        composable("event/{id}", idArg) { e ->
            val id = e.arguments!!.getString("id")!!
            EventScreen(id, onBack = { nav.popBackStack() }, onEdit = { nav.navigate("event-edit?id=$id") }, onDocument = { nav.navigate("doc/$it") },
                onAddTicket = { selfId -> nav.navigate("doc-edit/$selfId?eventId=$id") })
        }
        composable("event-edit?id={id}", listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
            EditEventScreen(e.arguments?.getString("id"), onBack = { nav.popBackStack() }, onSaved = { id, isNew ->
                nav.popBackStack()
                if (isNew) nav.navigate("event/$id")
            })
        }
        composable("passwords/{id}", idArg) { e -> PasswordsScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
        composable("settings?to={to}", listOf(navArgument("to") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
            SettingsScreen(scrollTo = e.arguments?.getString("to"), onBack = { nav.popBackStack() }, onEditAccount = { nav.navigate("account") }, onChangePasscode = { nav.navigate("passcode") },
                onMember = { nav.navigate("member/$it") }, onAddMember = { nav.navigate("member-edit") })
        }
        composable("account") {
            val vm = appViewModel(key = "account-edit") { OnboardingViewModel(it) }
            RegisterScreen(vm, onBack = { nav.popBackStack() }, title = "Your account", showStep = false) {
                vm.finish(null, box) { nav.popBackStack(); toast.show("Account updated") }
            }
        }
        composable("passcode") {
            PasscodeSetupScreen("Change passcode", onBack = { nav.popBackStack() }) { code ->
                scope.launch { withContext(Dispatchers.Default) { c.passcode.set(code) }; nav.popBackStack(); toast.show("Passcode updated") }
            }
        }
    }
}
