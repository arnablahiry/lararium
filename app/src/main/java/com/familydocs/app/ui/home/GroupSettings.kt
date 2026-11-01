package com.familydocs.app.ui.home

import android.content.Intent
import androidx.compose.foundation.background
import com.familydocs.app.ui.ds.dashedBorder
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familydocs.app.auth.GoogleAccountHelper
import com.familydocs.app.domain.GroupInvite
import com.familydocs.app.ui.components.LocalContainer
import com.familydocs.app.ui.components.rememberGoogleConnect
import com.familydocs.app.ui.ds.BtnKind
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdBanner
import com.familydocs.app.ui.ds.FdButton
import com.familydocs.app.ui.ds.FdIcon
import com.familydocs.app.ui.ds.FdIconButton
import com.familydocs.app.ui.ds.FdInput
import com.familydocs.app.ui.ds.FdSegmented
import com.familydocs.app.ui.ds.FdSheet
import com.familydocs.app.ui.ds.LocalToast
import com.familydocs.app.ui.ds.SheetTitle
import com.familydocs.app.ui.ds.fdText
import kotlinx.coroutines.launch

/**
 * Returns a function that sends the invite for a group: connects Google if needed, puts your encrypted share in Drive,
 * then opens the share sheet (WhatsApp, Messages…) with the invite message.
 */
@Composable
fun rememberInviteSender(onBusy: (Boolean) -> Unit = {}): (String) -> Unit {
    val app = LocalContainer.current
    val context = LocalContext.current
    return rememberInvite(onBusy) { inv ->
        app.appLock.expectExternalActivity()
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, inv.message()), "Send the invite"))
    }
}

/** Prepares your invite for a group (Drive access the first time), then hands it to [onReady] (send it, show it as a QR code…). */
@Composable
fun rememberInvite(onBusy: (Boolean) -> Unit = {}, onReady: (GroupInvite) -> Unit): (String) -> Unit {
    val app = LocalContainer.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val ready by androidx.compose.runtime.rememberUpdatedState(onReady)
    val busy by androidx.compose.runtime.rememberUpdatedState(onBusy)
    var target by remember { mutableStateOf<String?>(null) }
    val connect = rememberGoogleConnect { r ->
        val g = target ?: return@rememberGoogleConnect
        r.onSuccess { s ->
            scope.launch {
                busy(true)
                runCatching { app.share.invite(s.token, g) }
                    .onSuccess { inv -> ready(inv) }
                    .onFailure { toast.show("Couldn't prepare the invite: ${it.message}") }
                busy(false)
            }
        }.onFailure { if (it !is GoogleAccountHelper.Cancelled) toast.show(GoogleAccountHelper.explain(it)) }
    }
    return { g -> target = g; connect() }
}

/**
 * Group settings: name and code, what this phone backs up to Drive for the group, and sharing with the other members'
 * accounts (invite, add the invite they send back, who you read, sync).
 * [intro]: "created" or "joined" adds a short explanation at the top (shown right after making or joining a group).
 */
@Composable
fun GroupSettingsSheet(groupId: String, intro: String? = null, joinedFrom: String? = null, onMember: ((String) -> Unit)? = null, onDismiss: () -> Unit) {
    val app = LocalContainer.current
    val c = Fd.c
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val group by app.repo.group(groupId).collectAsStateWithLifecycle(null)
    val g = group ?: return
    var busy by remember { mutableStateOf<String?>(null) }
    var forgetting by remember { mutableStateOf<String?>(null) }
    // admins: change someone's role, or remove them from the group
    val self by app.repo.self().collectAsStateWithLifecycle(null)
    val iAdmin = g.isAdmin(self?.id)
    var managing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var croppingFor by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var cropStart by remember { mutableStateOf<com.familydocs.app.data.PhotoSources.Source?>(null) }
    val sendInvite = rememberInviteSender { busy = if (it) "Preparing the invite…" else null }
    var qr by remember { mutableStateOf<GroupInvite?>(null) }
    val showQr = rememberInvite({ busy = if (it) "Preparing the invite…" else null }) { qr = it }
    // Just joined: your own invite goes back to whoever invited you (the share sheet opens by itself, once).
    if (intro == "joined") androidx.compose.runtime.LaunchedEffect(g.id) { kotlinx.coroutines.delay(700); sendInvite(g.id) }
    val sync = rememberGoogleConnect { r ->
        r.onSuccess { s ->
            scope.launch {
                busy = "Syncing…"
                runCatching { app.share.sync(s.token, g.id) { msg -> busy = msg } }.onSuccess { toast.show(it) }.onFailure { toast.show("Couldn't sync: ${it.message}") }
                busy = null
            }
        }.onFailure { if (it !is GoogleAccountHelper.Cancelled) toast.show(GoogleAccountHelper.explain(it)) }
    }

    FdSheet(onDismiss) {
        Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                GroupPic(g, 64.dp)
                Column {
                    Text(g.name, style = fdText(18f, FontWeight.Bold))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Code ${g.code}", style = fdText(13f, color = c.fg.copy(alpha = .55f), spacing = .06f))
                        FdIconButton("copy", { clipboard.setText(androidx.compose.ui.text.AnnotatedString(g.code)); toast.show("Code copied") }, size = 30.dp, iconSize = 15.dp,
                            tint = c.muted, description = "Copy code")
                    }
                }
            }
            // ---- name and picture ----
            Label("Name and picture")
            var newName by remember(g.id) { mutableStateOf(g.name) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FdInput(newName, { newName = it }, Modifier.weight(1f), placeholder = "Group name")
                FdButton("Save", { scope.launch { app.repo.renameGroup(g.id, newName); toast.show("Saved") } }, Modifier.width(92.dp), kind = BtnKind.Ghost,
                    enabled = newName.isNotBlank() && newName.trim() != g.name)
            }
            Spacer(Modifier.height(10.dp))
            val context = LocalContext.current
            val ownPhoto = com.familydocs.app.ui.components.rememberVaultImage(g.photoFile, 300)
            GroupPicPicker(if (g.photoFile != null) "photo" else "default", ownPhoto,
                onTemplate = { k -> scope.launch { app.repo.setGroupPicture(g.id, k, null) } },
                onPhoto = { uri -> scope.launch { cropStart = null; croppingFor = readPhoto(context, uri) } })
            if (g.photoFile != null) Box(Modifier.padding(top = 8.dp)) {
                com.familydocs.app.ui.ds.FdChip("Edit photo", false, { scope.launch { loadPhotoSource(app, g.photoFile)?.let { s -> cropStart = s; croppingFor = s.bitmap } ?: toast.show("Couldn't open the picture") } },
                    leading = { FdIcon("edit", 16.dp, tint = c.fg) })
            }
            Spacer(Modifier.height(18.dp))
            when (intro) {
                "created" -> FdBanner("info", "Backups: by default your Drive keeps you and this group's Common vault; you can switch to the whole group below, any time. " +
                    "To share the group with someone, tap Invite: your phone puts an encrypted copy in your Google Drive (allow Drive access when Google asks) and each person who joins does the same.",
                    Modifier.padding(bottom = 14.dp))
                "joined" -> FdBanner("people", "You joined ${g.name}. Last step: send your own invite back to ${joinedFrom ?: "the person who invited you"}, so your documents " +
                    "show up on their phone (and, through theirs, everyone else's). Google asks you to allow Drive access the first time.", Modifier.padding(bottom = 14.dp)) {
                    com.familydocs.app.ui.ds.FdTextButton("Send", { sendInvite(g.id) }, enabled = busy == null)
                }
            }

            // ---- backup ----
            Label("Back up to your Google Drive")
            FdSegmented(listOf(Triple(false, "user", "Me + Common"), Triple(true, "people", "Whole group")), g.backupAll) { all ->
                scope.launch { app.repo.setGroupBackup(g.id, all) }
            }
            Text(if (g.backupAll) "Everyone in ${g.name} that's on this phone, with their documents. Logins: only yours and the Common ones, never other people's."
                 else "Your documents and the Common vault (with its logins). The others back up their own from their phones.",
                style = fdText(12.5f, color = c.muted), modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))

            // ---- people: Common, you, and everyone who shares with you (tap one to open their vault) ----
            Label("People")
            val groupMembers by remember(g.id) { app.repo.membersOf(g.id) }.collectAsStateWithLifecycle(emptyList())
            val counts by app.repo.documentCounts.collectAsStateWithLifecycle(emptyList())
            fun docs(id: String?) = (counts.firstOrNull { it.memberId == id }?.n ?: 0).let { "$it document${if (it == 1) "" else "s"}" }
            fun role(key: String?) = when { key == null -> null; key == g.creator -> "Admin · made the group"; key in g.adminKeys -> "Admin"; else -> null }
            fun open(id: String): (() -> Unit)? = onMember?.let { f -> { onDismiss(); f(id) } }
            val peers = g.peerList
            val unseen = if (peers.isNotEmpty() && peers.all { it.sees == false }) peers else emptyList()
            if (peers.isNotEmpty()) {
                val notYet = peers.filter { !it.synced }
                val names = { l: List<com.familydocs.app.data.Peer> -> com.familydocs.app.data.Peer.names(l.map { it.name.ifBlank { "Someone" } }) }
                Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FdIcon(if (notYet.isEmpty()) "check" else "refresh", 16.dp, tint = if (notYet.isEmpty()) c.accent else c.muted)
                    Text(when {
                        notYet.isEmpty() -> "You and ${names(peers)} are synced."
                        notYet.size == peers.size -> "${names(notYet)} ${if (notYet.size == 1) "isn't" else "aren't"} synced yet."
                        else -> "You and ${names(peers - notYet.toSet())} are synced; ${names(notYet)} ${if (notYet.size == 1) "isn't" else "aren't"} yet."
                    }, style = fdText(13f, FontWeight.Medium))
                }
            }
            if (peers.isNotEmpty()) {
                val adminNames = g.adminKeys.mapNotNull { k -> if (k == self?.id) "you" else peers.firstOrNull { it.key == k }?.name }
                Text(when {
                    iAdmin -> "You're an admin: you can change anyone's documents and profile, make others admins and remove people. They see a note when you do."
                    adminNames.isEmpty() -> "Everyone keeps their own documents and profile up to date. Admins (whoever made the group, and anyone they choose) can change anyone's."
                    else -> "Everyone keeps their own documents and profile up to date. Admins (${adminNames.joinToString(", ")}) can change anyone's."
                }, style = fdText(12.5f, color = c.muted), modifier = Modifier.padding(bottom = 10.dp))
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp))) {
                var first = true
                groupMembers.firstOrNull { it.shared }?.let { m ->
                    PersonRow(m.name, m, "Shared by everyone · ${docs(m.id)}", first = first, onClick = open(m.id)); first = false
                }
                groupMembers.firstOrNull { it.isSelf }?.let { m ->
                    PersonRow(m.name, m, listOfNotNull("You", role(m.id), docs(m.id)).joinToString(" · "), first = first, onClick = open(m.id)); first = false
                }
                peers.forEach { p ->
                    val m = groupMembers.firstOrNull { it.origin == p.key && !it.shared }
                    PersonRow(p.name.ifBlank { m?.name ?: "Group member" }, m, listOfNotNull(role(p.key), docs(m?.id)).joinToString(" · "),
                        status = when {
                            p.at == 0L -> "Not synced yet · not read yet"
                            p.sees == false -> "Not synced yet · hasn't added you yet"
                            else -> "Synced · updated " + android.text.format.DateUtils.getRelativeTimeSpanString(p.at)
                        },
                        error = p.err?.let { "Last sync: $it" }, first = first, onClick = m?.let { open(it.id) }) {
                        when {
                            iAdmin && p.key != g.creator -> FdIconButton("sliders", { managing = p.key }, size = 36.dp, iconSize = 17.dp, tint = c.muted, description = "Manage ${p.name}")
                            !iAdmin -> FdIconButton("close", { forgetting = p.key }, size = 36.dp, iconSize = 16.dp, tint = c.muted, description = "Stop reading ${p.name}'s share")
                        }
                    }
                    first = false
                }
            }
            if (peers.isEmpty()) Text("No one else yet. Tap Invite and send the invite privately (WhatsApp, Messages…); they paste it in Join group and send theirs back.",
                style = fdText(13f, color = c.muted), modifier = Modifier.padding(top = 10.dp))
            // No one has added your invite yet: until someone does, your things reach no one (once one member has you,
            // the others find you through their share).
            if (unseen.isNotEmpty() && intro != "joined") FdBanner("people",
                "${unseen.joinToString(", ") { it.name.ifBlank { "Someone" } }} ${if (unseen.size == 1) "hasn't" else "haven't"} added you yet. Send your invite so your documents show up for them too.",
                Modifier.padding(top = 10.dp)) { com.familydocs.app.ui.ds.FdTextButton("Send", { sendInvite(g.id) }, enabled = busy == null) }
            Spacer(Modifier.height(12.dp))
            FdButton("Invite someone", { sendInvite(g.id) }, icon = "people", enabled = busy == null)
            Spacer(Modifier.height(10.dp))
            FdButton("Show invite as a QR code", { showQr(g.id) }, kind = BtnKind.Ghost, icon = "scan", enabled = busy == null)
            Spacer(Modifier.height(10.dp))
            if (peers.isNotEmpty() || g.shareIndex != null) FdButton("Sync now", sync, kind = BtnKind.Ghost, icon = "refresh", enabled = busy == null)
            busy?.let { Text(it, style = fdText(12.5f, color = c.muted), modifier = Modifier.padding(top = 10.dp)) }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FdIcon("lock", 14.dp, tint = c.muted)
                Text("Each phone shares an encrypted copy from its own Drive. The Drive link alone is unreadable: it needs the group code, which travels only " +
                    "inside the invite, so send invites privately. Things shared with you are read-only here; logins shared are only the Common ones.",
                    style = fdText(11.5f, color = c.muted))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    qr?.let { inv -> InviteQrSheet(inv, g.name) { qr = null } }
    croppingFor?.let { bmp -> PhotoCropSheet(bmp, "Group picture", start = cropStart, onDone = { p ->
        scope.launch { app.repo.setGroupPicture(g.id, null, p.bytes); p.keep(app) }; croppingFor = null }, onDismiss = { croppingFor = null }) }
    managing?.let { key ->
        val name = g.peerList.firstOrNull { it.key == key }?.name ?: "Member"
        val isAdmin = key in g.adminKeys
        FdSheet({ managing = null }) {
            com.familydocs.app.ui.ds.SheetTitle(name, if (isAdmin) "Admin of ${g.name}: can change anyone's documents and profiles." else "Member of ${g.name}: changes only their own things.")
            FdButton(if (isAdmin) "Remove admin role" else "Make admin", {
                scope.launch {
                    runCatching { app.share.setAdmin(g.id, key, !isAdmin) }
                        .onSuccess { toast.show(if (isAdmin) "$name is no longer an admin" else "$name is now an admin") }.onFailure { toast.show(it.message ?: "Couldn't change it") }
                    managing = null
                }
            }, kind = BtnKind.Ghost, icon = "shield")
            Spacer(Modifier.height(10.dp))
            FdButton("Remove from group", { removing = key; managing = null }, kind = BtnKind.Ghost, icon = "trash", contentColor = c.danger)
        }
    }
    removing?.let { key ->
        val name = g.peerList.firstOrNull { it.key == key }?.name ?: "them"
        com.familydocs.app.ui.ds.FdConfirmSheet("Remove $name from ${g.name}?",
            "$name's documents disappear from everyone's phone and they no longer see ${g.name}'s things; their phone tells them you removed them. " +
                "Nothing is deleted from their own phone or Drive, and you can invite them again later.",
            "Remove", onConfirm = { scope.launch {
                runCatching { app.share.removeMember(g.id, key) }.onSuccess { toast.show("$name was removed from ${g.name}") }.onFailure { toast.show(it.message ?: "Couldn't remove") }
                removing = null
            } }, onDismiss = { removing = null })
    }
    forgetting?.let { key ->
        val name = g.peerList.firstOrNull { it.key == key }?.name ?: "them"
        com.familydocs.app.ui.ds.FdConfirmSheet("Stop reading $name's share?", "What $name shared is removed from this phone. Nothing in their Drive changes; you can add their invite again later.",
            "Remove", onConfirm = { scope.launch { app.share.forget(g.id, key); forgetting = null; toast.show("Removed") } }, onDismiss = { forgetting = null })
    }
}

/** One person in Group settings › People: picture, name, role and documents, sync state; tap to open their vault. */
@Composable
private fun PersonRow(name: String, m: com.familydocs.app.data.MemberEntity?, subtitle: String, status: String? = null, error: String? = null,
                      first: Boolean, onClick: (() -> Unit)?, trailing: @Composable () -> Unit = {}) {
    val c = Fd.c
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        com.familydocs.app.ui.ds.FdAvatar(name, 38.dp, photoFile = m?.photoFile, character = m?.avatar, shared = m?.shared == true)
        Column(Modifier.weight(1f)) {
            Text(name, style = fdText(14.5f, FontWeight.Medium))
            Text(subtitle, style = fdText(12f, color = c.muted))
            status?.let { Text(it, style = fdText(11.5f, color = c.muted)) }
            error?.let { Text(it, style = fdText(11.5f, color = c.danger), maxLines = 2) }
        }
        trailing()
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = fdText(11f, FontWeight.SemiBold, Fd.c.muted, spacing = .08f), modifier = Modifier.padding(bottom = 8.dp))
}


/**
 * Join a group: paste the invite someone in it sent you. (A group's code alone opens a group that's already on this phone.)
 */
@Composable
fun JoinGroupSheet(onJoined: (groupId: String, invitedBy: String?) -> Unit, onDismiss: () -> Unit, initial: String = "") {
    val app = LocalContainer.current
    val c = Fd.c
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // An invite for a group that's already here: it adds the person who sent it (their invite sent back to you).
    val groups by app.repo.groups.collectAsStateWithLifecycle(emptyList())
    val inv = remember(text) { GroupInvite.find(text) }
    val here = inv?.let { i -> groups.firstOrNull { it.id == i.groupId } }
    FdSheet(onDismiss) {
        if (here != null) SheetTitle("Add someone to ${here.name}", "This is their invite: adding it puts them in ${here.name} on this phone, and everyone else in the group finds them through you.")
        else SheetTitle("Join a group", "Paste the invite someone in the group sent you (or just tap the link they sent). Then send yours back to them, so they see you too.")
        FdInput(text, { text = it; error = null }, placeholder = "LR.… or the invite link", singleLine = false, error = error != null)
        error?.let { Text(it, style = fdText(12.5f, color = c.danger), modifier = Modifier.padding(top = 8.dp)) }
        FdButton(if (busy) "Opening the invite…" else if (here != null) "Add them" else "Join", {
            scope.launch {
                busy = true
                if (inv != null) {
                    runCatching { app.share.accept(null, inv) }.onSuccess { r ->
                        // publishes your share and reads theirs in the background (needs Drive)
                        com.familydocs.app.backup.BackupWorker.syncShares(app.appContext)
                        if (r.joined) onJoined(r.group.id, r.fromName)
                        else {
                            toast.show(if (r.synced) "You and ${r.fromName} are now synced!" else "${r.fromName} is added. You're synced once their phone picks you up.")
                            onJoined(r.group.id, null)
                        }
                        onDismiss()
                    }.onFailure { error = it.message ?: "Couldn't use that invite" }
                } else {
                    val local = app.repo.groupByCode(text)
                    if (local != null) { onJoined(local.id, null); onDismiss() }
                    else error = "That isn't an invite. Ask someone in the group for one (Group settings › Invite someone) and paste all of it here."
                }
                busy = false
            }
        }, Modifier.padding(top = 14.dp), enabled = text.isNotBlank() && !busy)
        Spacer(Modifier.height(12.dp))
    }
}


/** The group's picture: your own photo if set, else its template illustration; round. */
@Composable
fun GroupPic(g: com.familydocs.app.data.GroupEntity, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, description: String? = null) {
    val photo = com.familydocs.app.ui.components.rememberVaultImage(g.photoFile, 600)
    val m = modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape)
    if (photo != null) androidx.compose.foundation.Image(photo, description, m, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    else androidx.compose.foundation.Image(com.familydocs.app.ui.ds.fdPainter(groupArt(g.pic)), description, m, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
}

@Suppress("UNUSED_PARAMETER")
fun groupArt(pic: String?): Int = com.familydocs.app.R.drawable.art_group_default

/**
 * Picture chooser: the illustration, or a photo from the phone's gallery. [selected] is "default" or "photo";
 * [photo] is a preview of the chosen photo.
 */
@Composable
fun GroupPicPicker(selected: String, photo: androidx.compose.ui.graphics.ImageBitmap?, onTemplate: (String) -> Unit, onPhoto: (android.net.Uri) -> Unit) {
    val c = Fd.c
    val app = LocalContainer.current
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(onPhoto) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        val item: @Composable (Boolean, String, () -> Unit, @Composable () -> Unit) -> Unit = { on, label, click, pic ->
            Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = click).padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(60.dp).border(2.dp, if (on) c.accent else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.CircleShape).padding(3.dp)) { pic() }
                Text(label, style = fdText(10.5f, if (on) FontWeight.SemiBold else FontWeight.Normal, if (on) c.fg else c.muted), maxLines = 1)
            }
        }
        item(selected != "photo", "Illustration", { onTemplate(com.familydocs.app.domain.GroupPics.DEFAULT) }) {
            androidx.compose.foundation.Image(com.familydocs.app.ui.ds.fdPainter(groupArt(null)), "Illustration", Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
        item(selected == "photo", if (photo != null) "Your photo" else "Add photo", {
            app.appLock.expectExternalActivity()
            pick.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) {
            if (photo != null) androidx.compose.foundation.Image(photo, "Your photo", Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else Box(Modifier.fillMaxSize().dashedBorder(c.line, radius = 22.dp), contentAlignment = Alignment.Center) { FdIcon("image", 18.dp, tint = c.muted) }
        }
    }
}

/** Reads a picked image for cropping. */
suspend fun readPhoto(context: android.content.Context, uri: android.net.Uri): android.graphics.Bitmap? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { com.familydocs.app.ocr.Images.decode(it, 1600) } }.getOrNull()
    }

/** A cropped picture, and how it was framed (so it can be kept for a later "Edit photo"). */
class CroppedPhoto(val bytes: ByteArray, val image: androidx.compose.ui.graphics.ImageBitmap, val original: android.graphics.Bitmap,
                   val zoom: Float, val panX: Float, val panY: Float, val originalId: String?) {
    /** Remembers the original behind [bytes] once they're saved (off the main thread). */
    suspend fun keep(app: com.familydocs.app.AppContainer) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { app.photoSources.keep(bytes, original, zoom, panX, panY, originalId) }
    }
}

/** Position and zoom a photo in the circle (the same cropper as people's photos); [onDone] gets the saved JPEG. */
@Composable
fun PhotoCropSheet(bitmap: android.graphics.Bitmap, title: String, onDone: (CroppedPhoto) -> Unit, onDismiss: () -> Unit,
                   start: com.familydocs.app.data.PhotoSources.Source? = null, out: Int = 512) {
    val scope = rememberCoroutineScope()
    val box = com.familydocs.app.ui.ds.cropBoxPx()
    val crop = remember(bitmap) { com.familydocs.app.ui.ds.CropState().apply {
        start?.let { zoom = it.zoom; pan = androidx.compose.ui.geometry.Offset(it.panX * box, it.panY * box) } } }
    FdSheet(onDismiss) {
        SheetTitle(title, "Drag and zoom so the circle shows what you want.")
        com.familydocs.app.ui.ds.FdPhotoCropper(bitmap, crop) {}
        FdButton("Use this photo", {
            scope.launch {
                val sq = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.familydocs.app.ui.ds.cropToSquare(bitmap, crop, box, out) }
                onDone(CroppedPhoto(com.familydocs.app.ocr.Images.jpeg(sq), sq.asImageBitmap(), bitmap, crop.zoom, crop.pan.x / box, crop.pan.y / box, start?.originalId))
            }
        }, Modifier.padding(top = 14.dp))
        Spacer(Modifier.height(12.dp))
    }
}

/** Loads a saved picture for "Edit photo" (its original and framing when this phone has them). */
suspend fun loadPhotoSource(app: com.familydocs.app.AppContainer, photoFile: String?): com.familydocs.app.data.PhotoSources.Source? =
    photoFile?.let { f -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.photoSources.load(f) }.getOrNull() } }

/** New group: name and picture (the illustration, or your own photo). */
@Composable
fun NewGroupSheet(onCreated: (com.familydocs.app.data.GroupEntity) -> Unit, onDismiss: () -> Unit) {
    val app = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    val pic = com.familydocs.app.domain.GroupPics.DEFAULT
    var photo by remember { mutableStateOf<CroppedPhoto?>(null) }
    var cropping by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    FdSheet(onDismiss) {
        SheetTitle("New group", "Its own people, Common vault, events and code. You're in every group.")
        FdInput(name, { name = it }, placeholder = "Group name, e.g. Flatmates")
        Label2("Picture")
        GroupPicPicker(if (photo != null) "photo" else pic, photo?.image, onTemplate = { photo = null },
            onPhoto = { uri -> scope.launch { cropping = readPhoto(context, uri) } })
        FdButton(if (busy) "Creating…" else "Create group", {
            scope.launch { busy = true; val g = app.repo.createGroup(name, pic, photo?.bytes); photo?.keep(app); busy = false; onCreated(g); onDismiss() }
        }, Modifier.padding(top = 16.dp), enabled = name.isNotBlank() && !busy)
        Spacer(Modifier.height(12.dp))
    }
    cropping?.let { bmp -> PhotoCropSheet(bmp, "Group picture", onDone = { p -> photo = p; cropping = null }, onDismiss = { cropping = null }) }
}

@Composable
private fun Label2(text: String) {
    Text(text.uppercase(), style = fdText(11f, FontWeight.SemiBold, Fd.c.muted, spacing = .08f), modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}
