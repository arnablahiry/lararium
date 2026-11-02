package com.familydocs.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familydocs.app.domain.Changelog
import com.familydocs.app.ui.ds.BtnKind
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdAvatar
import com.familydocs.app.ui.ds.FdButton
import com.familydocs.app.ui.ds.FdChip
import com.familydocs.app.ui.ds.FdChips
import com.familydocs.app.ui.ds.FdConfirmSheet
import com.familydocs.app.ui.ds.FdIcon
import com.familydocs.app.ui.ds.FdIconButton
import com.familydocs.app.ui.ds.FdListItem
import com.familydocs.app.ui.ds.FdSectionTitle
import com.familydocs.app.ui.ds.FdSegmented
import com.familydocs.app.ui.ds.fdText
import com.familydocs.desktop.BuildInfo
import com.familydocs.desktop.Platform
import com.familydocs.desktop.Theme
import com.familydocs.desktop.rememberApp
import kotlinx.coroutines.launch

/** The phone's release notes, shipped with this app. */
private val changelog: List<Changelog.Entry> by lazy {
    runCatching { Changelog.parse(SettingsRes::class.java.getResourceAsStream("/changelog.txt")!!.bufferedReader().readText()) }.getOrDefault(emptyList())
}
private object SettingsRes

/** A card of rows, like the phone's lists. */
@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    val c = Fd.c
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp)), content = content)
}

@Composable
private fun Section(title: String) = FdSectionTitle(title, Modifier.padding(start = 0.dp, top = 8.dp))

/**
 * Settings, as on the phone: your profile, the group, how this computer looks and locks, where everything comes from
 * and goes to, signing out and what's new. Things only the phone can change (passcode, backup choices) say so.
 */
@Composable
fun SettingsPage(view: GroupView) {
    val app = rememberApp()
    val c = Fd.c
    val data = view.data
    val self = data.members.firstOrNull { it.isSelf }
    var profile by remember { mutableStateOf(false) }
    var family by remember { mutableStateOf<String?>(null) }
    var signingOut by remember { mutableStateOf(false) }
    var allNotes by remember { mutableStateOf(false) }

    PageColumn(maxWidth = 760.dp) {
        PageBar("Settings")
        // ---- you ----
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            FdAvatar(data.account?.name ?: self?.name ?: "", 64.dp, photoFile = self?.photoFile ?: data.account?.photoFile, character = self?.avatar)
            Column(Modifier.weight(1f)) {
                Text(data.account?.name ?: self?.name ?: "You", style = fdText(16f, FontWeight.Bold))
                Text(listOf(app.email.ifBlank { data.account?.backupEmail.orEmpty() }, "Google").filter { it.isNotBlank() }.joinToString(" · "), style = fdText(12f, color = c.muted))
            }
            if (self != null) FdIconButton("edit", { profile = true }, iconSize = 19.dp, description = "Edit your profile")
        }

        // ---- groups ----
        Section("Group")
        Card {
            data.groups.forEachIndexed { i, g ->
                val n = data.members.count { it.groupId == g.id && !it.shared } + 1
                FdListItem(g.name, "$n ${if (n == 1) "person" else "people"} and Common · people, picture, invites", first = i == 0,
                    leading = { GroupPic(g, 38) }, onClick = { app.selectGroup(g.id, stay = true); family = g.id })
            }
        }

        // ---- looks ----
        Section("Appearance")
        Card {
            FdListItem("Theme", "Follows ${if (Platform.isMac) "macOS" else "the system"} unless you pick one", first = true, icon = if (c.dark) "moon" else "sun", trailing = {
                FdSegmented(listOf(Triple(Theme.SYSTEM, "sliders", "System"), Triple(Theme.LIGHT, "sun", "Light"), Triple(Theme.DARK, "moon", "Dark")), app.theme) { app.chooseTheme(it) }
            })
        }

        // ---- security ----
        Section("Security")
        Card {
            FdListItem("Passcode", if (app.passcode != null) "${app.passcode!!.length} digits, the same as on your phone · change it on the phone" else "Set one on the phone and it works here too",
                first = true, icon = "lock")
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                Text("Lock after", style = fdText(14f, FontWeight.SemiBold), modifier = Modifier.padding(top = 12.dp))
                Text("When Lararium has been in the background this long", style = fdText(12f, color = c.muted))
                FdChips(Modifier.padding(top = 10.dp)) {
                    listOf(1 to "1 minute", 5 to "5 minutes", 15 to "15 minutes", 60 to "1 hour", 0 to "Only when I lock it").forEach { (m, label) ->
                        FdChip(label, app.lockAfterMin == m, { app.chooseLockAfter(m) })
                    }
                }
            }
            FdListItem("Lock now", "${Platform.mod}L", icon = "lock", onClick = if (app.passcode != null) ({ app.lock() }) else null)
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                SwitchRow("Emergency info on the lock screen", "Emergency cards (blood group, allergies, who to call) readable without the passcode",
                    app.emergencyOnLock) { app.chooseEmergencyOnLock(it) }
            }
        }

        // ---- sync ----
        Section("Google Drive")
        Card {
            val ago = ((System.currentTimeMillis() - data.loadedAt) / 60_000).coerceAtLeast(0)
            FdListItem("Read from your Drive", if (app.refreshing) "Updating…" else "Your encrypted backup and the group's shares · ${if (ago < 1) "just now" else "$ago min ago"}",
                first = true, icon = "cloud", trailing = { FdButton("Update now", { app.load() }, Modifier.width(140.dp), kind = BtnKind.Ghost, icon = "refresh", enabled = !app.refreshing) })
            FdListItem("Changes made here", when (app.waiting) {
                0 -> "All taken over by your phone"
                1 -> "1 change on its way to your phone · it takes them over within seconds while Lararium is open there"
                else -> "${app.waiting} changes on their way to your phone · it takes them over within seconds while Lararium is open there"
            }, icon = if (app.waiting == 0) "check" else "refresh")
            FdListItem("Backup", "Made by your phone (encrypted, daily and right after each change). Its choices, restoring and the logins' backup password are on the phone.",
                icon = "shield")
            if (data.problems.isNotEmpty()) FdListItem("Couldn't read", data.problems.joinToString("\n"), icon = "info")
        }

        // ---- account ----
        Section("Account")
        Card {
            FdListItem("Sign out of this ${Platform.device}", "Removes your Google sign-in from this ${Platform.device}. Nothing changes on your phone or in Drive.",
                first = true, icon = "logout", onClick = { signingOut = true })
        }

        // ---- about ----
        Section("About")
        Card {
            FdListItem("Lararium for ${Platform.device} ${BuildInfo.VERSION}", "${view.members.count { !it.shared }} people · ${view.docs.size} documents in ${view.group?.name ?: "this group"}",
                first = true, icon = "info")
            UpdateRow()
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text("Keyboard", style = fdText(14f, FontWeight.SemiBold))
                Text("${Platform.mod}F search · ${Platform.mod}R update · ${Platform.mod}L lock · ${Platform.mod}, settings · Esc back", style = fdText(12f, color = c.muted))
            }
        }
        if (changelog.isNotEmpty()) {
            Section("What's new")
            Card {
                (if (allNotes) changelog.take(8) else changelog.take(1)).forEachIndexed { i, e ->
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        if (i > 0) Spacer(Modifier.height(4.dp))
                        Text("Version ${e.version}", style = fdText(13.5f, FontWeight.SemiBold))
                        e.notes.forEach { n ->
                            Row(Modifier.padding(top = 4.dp)) {
                                Text("•", style = fdText(13f, color = c.accent), modifier = Modifier.width(14.dp))
                                Text(n, style = fdText(12.5f, color = c.muted))
                            }
                        }
                    }
                }
                Column(Modifier.padding(start = 6.dp, bottom = 6.dp)) {
                    com.familydocs.app.ui.ds.FdTextButton(if (allNotes) "Show less" else "Earlier versions", { allNotes = !allNotes })
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }

    if (profile && self != null) PersonEditSheet(view, self, self.groupId) { profile = false }
    family?.let { id -> if (view.group?.id == id) FamilySheet(view) { family = null } }
    if (signingOut) FdConfirmSheet("Sign out of this ${Platform.device}?",
        "Lararium forgets your Google sign-in here. Your phone, your backup and your group stay exactly as they are; sign in again any time." +
            if (app.waiting > 0) " Changes made here that your phone hasn't taken over yet stay in Drive and still reach it." else "",
        "Sign out", onConfirm = { signingOut = false; app.signOut() }, onDismiss = { signingOut = false })
}

/** Settings › About: this app's updates, downloaded and installed from here. */
@Composable
private fun UpdateRow() {
    val c = Fd.c
    val toast = com.familydocs.app.ui.ds.LocalToast.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val u = com.familydocs.desktop.Updater
    val r = u.latest
    val mb = r?.size?.takeIf { it > 0 }?.let { " (${(it + 524_288) / 1_048_576} MB)" }.orEmpty()
    Column {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            com.familydocs.app.ui.ds.FdLic(if (u.state == com.familydocs.desktop.Updater.State.AVAILABLE || u.state == com.familydocs.desktop.Updater.State.READY) "download" else "refresh",
                if (u.state == com.familydocs.desktop.Updater.State.AVAILABLE) c.accent else c.primary)
            Column(Modifier.weight(1f)) {
                Text(when (u.state) {
                    com.familydocs.desktop.Updater.State.AVAILABLE -> "Version ${r?.version} is available"
                    com.familydocs.desktop.Updater.State.DOWNLOADING -> "Downloading ${r?.version}…"
                    com.familydocs.desktop.Updater.State.READY -> "Version ${r?.version} is ready"
                    com.familydocs.desktop.Updater.State.CHECKING -> "Checking for updates…"
                    com.familydocs.desktop.Updater.State.UP_TO_DATE -> "Up to date"
                    else -> "Updates"
                }, style = fdText(14f, FontWeight.SemiBold))
                Text(when (u.state) {
                    com.familydocs.desktop.Updater.State.AVAILABLE -> "Downloads here and installs over this one$mb"
                    com.familydocs.desktop.Updater.State.DOWNLOADING -> "${(u.progress * 100).toInt()}%"
                    com.familydocs.desktop.Updater.State.READY -> if (Platform.isLinux) "Opens in your system's installer" else "Lararium closes, updates and opens again in a few seconds"
                    com.familydocs.desktop.Updater.State.FAILED -> u.error ?: "Couldn't check"
                    else -> "This is the newest version for your ${Platform.device}"
                }, style = fdText(12f, color = if (u.state == com.familydocs.desktop.Updater.State.FAILED) c.danger else c.muted))
                if (u.state == com.familydocs.desktop.Updater.State.DOWNLOADING) androidx.compose.material3.LinearProgressIndicator(
                    progress = { u.progress }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth(), color = c.ok, trackColor = c.surface2)
            }
            when (u.state) {
                com.familydocs.desktop.Updater.State.AVAILABLE ->
                    FdButton("Download", { scope.launch { u.download() } }, Modifier.width(140.dp), icon = "download")
                com.familydocs.desktop.Updater.State.READY ->
                    FdButton(if (Platform.isLinux) "Install" else "Update now", { u.install { kotlin.system.exitProcess(0) }?.let { toast.show(it) } }, Modifier.width(150.dp))
                com.familydocs.desktop.Updater.State.DOWNLOADING, com.familydocs.desktop.Updater.State.CHECKING -> {}
                else -> FdButton("Check", { scope.launch { u.check() } }, Modifier.width(110.dp), kind = BtnKind.Ghost)
            }
        }
    }
}
