package com.familydocs.app.ui.home

import com.familydocs.app.ui.ds.FdEase
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.border
import androidx.compose.animation.core.spring
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familydocs.app.data.DocumentEntity
import com.familydocs.app.data.MemberEntity
import com.familydocs.app.domain.maskValue
import com.familydocs.app.security.ThemeMode
import com.familydocs.app.ui.components.LocalContainer
import com.familydocs.app.ui.components.LocalThemeReveal
import com.familydocs.app.ui.ds.FdBanner
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdAvatar
import com.familydocs.app.ui.ds.FdDocIcon
import com.familydocs.app.ui.ds.FdEmpty
import com.familydocs.app.ui.ds.FdIcon
import com.familydocs.app.ui.ds.FdIconButton
import com.familydocs.app.ui.ds.FdInput
import com.familydocs.app.ui.ds.dashedBorder
import com.familydocs.app.ui.ds.fdText
import com.familydocs.app.domain.Events
import com.familydocs.app.ui.events.EventsStrip
import com.familydocs.app.ui.events.visibleTo
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun FamilyScreen(onMember: (String) -> Unit, onSettings: () -> Unit, onDocument: (String) -> Unit, onEvent: (String) -> Unit, onNewEvent: () -> Unit,
                 onUpdates: () -> Unit = onSettings) {
    val app = LocalContainer.current
    val c = Fd.c
    val groups by app.repo.groups.collectAsStateWithLifecycle(emptyList())
    val gid by app.repo.groupId.collectAsStateWithLifecycle()
    // People are the group's members with their own accounts (and Common). Once: the people this phone made up earlier
    // (the old preset family) go; any documents they had move to Common.
    LaunchedEffect(Unit) {
        if (!app.settings.value.peopleCleanupDone) { app.repo.foldLocalPeople(); app.settings.update { it.copy(peopleCleanupDone = true, familyPresetDone = true) } }
    }
    // Whenever Home comes back to the screen: publish and read the group's shares.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                com.familydocs.app.backup.BackupWorker.syncShares(app.appContext)
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    var sheet by remember { mutableStateOf<String?>(null) } // "create" | "join"
    // The "not synced yet" sheet (group id): opens after joining a group; minimised, it's the short box on Home.
    var syncSheetFor by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val openSync by app.openSyncSheet.collectAsStateWithLifecycle()
    LaunchedEffect(openSync) { openSync?.let { syncSheetFor = it; app.openSyncSheet.value = null } }
    // Opened from an invite link (or an invite shared to Lararium): the join sheet, filled in.
    var joinText by remember { mutableStateOf("") }
    val incoming by app.incomingInvite.collectAsStateWithLifecycle()
    LaunchedEffect(incoming) { incoming?.let { joinText = it; sheet = "join"; app.incomingInvite.value = null } }
    // Group settings (with a short explanation right after creating / joining): group id, intro, who invited you
    var settingsFor by remember { mutableStateOf<Triple<String, String?, String?>?>(null) }
    // First time on Home with a group you made at sign-up: explain backups and sharing once.
    LaunchedEffect(groups.isNotEmpty()) {
        val first = groups.firstOrNull() ?: return@LaunchedEffect
        if (!app.settings.value.groupIntroDone) {
            app.settings.update { it.copy(groupIntroDone = true) }
            if (first.peerList.isEmpty()) settingsFor = Triple(first.id, "created", null)
        }
    }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        GroupHeader(groups, gid, onSettings, onCreate = { sheet = "create" }, onJoin = { sheet = "join" },
            onGroupSettings = { id -> settingsFor = Triple(id, null, null) })
        // Restored on this phone, but the logins are locked with the backup password: ask for it.
        val loginsWaiting by app.loginLock.waiting.collectAsStateWithLifecycle()
        var unlocking by remember { mutableStateOf(false) }
        if (loginsWaiting) FdBanner("lock", "Your logins are locked with your backup password", Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp)) {
            com.familydocs.app.ui.ds.FdTextButton("Unlock", { unlocking = true })
        }
        if (unlocking) com.familydocs.app.ui.settings.UnlockLoginsSheet { unlocking = false }
        // What a group admin changed in your things (or about you): stays until you close it.
        val notes by app.share.notices.collectAsStateWithLifecycle()
        notes.takeLast(3).reversed().forEach { n ->
            FdBanner(n.icon, n.text, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp)) {
                FdIconButton("close", { app.share.dismissNotice(n.id) }, size = 32.dp, iconSize = 15.dp, description = "Dismiss")
            }
        }
        // Not synced with everyone in this group yet: a short box; tapping it opens the sheet with who and your link.
        val shown = groups.firstOrNull { it.id == gid }
        val waiting = shown?.peerList.orEmpty().filter { !it.synced }
        if (shown != null && waiting.isNotEmpty()) SyncBox(shown, waiting, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp)) { syncSheetFor = shown.id }
        // First time after an update: what changed, until it's closed.
        val news by app.whatsNew.entries.collectAsStateWithLifecycle()
        if (news.isNotEmpty()) WhatsNewBox(news, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp)) { app.whatsNew.dismiss() }
        // Switching groups: the old page fades back a touch, the new one rises in and settles.
        AnimatedContent(gid, transitionSpec = {
            (fadeIn(tween(280, delayMillis = 90)) + slideInVertically(tween(380, easing = FdEase)) { it / 16 } + scaleIn(tween(380, easing = FdEase), initialScale = .97f)) togetherWith
                (fadeOut(tween(150)) + scaleOut(tween(200), targetScale = .985f))
        }, label = "groupSwitch") { g -> GroupHome(g, onMember, onSettings, onDocument, onEvent, onNewEvent, onUpdates) }
    }
    when (sheet) {
        "create" -> NewGroupSheet(onCreated = { g -> app.selectGroup(g.id); app.settings.update { it.copy(groupIntroDone = true) }; settingsFor = Triple(g.id, "created", null) },
            onDismiss = { sheet = null })
        "join" -> androidx.compose.runtime.key(joinText) { JoinGroupSheet(onJoined = { id, from ->
            app.selectGroup(id)
            // joined a group that already has people: who you're not synced with yet, and your link to send them
            if (from != null) syncSheetFor = id
        }, onDismiss = { sheet = null; joinText = "" }, initial = joinText) }
    }
    settingsFor?.let { (id, intro, from) -> GroupSettingsSheet(id, intro, from, onMember = onMember) { settingsFor = null } }
    syncSheetFor?.let { id -> groups.firstOrNull { it.id == id }?.let { g -> SyncSheet(g) { syncSheetFor = null } } }
}

/** One group's home: its people, search and events (keyed by group, so the switch animation shows the right page). */
@Composable
private fun GroupHome(g: String?, onMember: (String) -> Unit, onSettings: () -> Unit, onDocument: (String) -> Unit, onEvent: (String) -> Unit, onNewEvent: () -> Unit,
                      onUpdates: () -> Unit) {
    val app = LocalContainer.current
    val c = Fd.c
    val account by app.repo.account.collectAsStateWithLifecycle(null)
    val members by remember(g) { app.repo.membersOf(g) }.collectAsStateWithLifecycle(emptyList())
    val docs by remember(g) { app.repo.documentsOf(g) }.collectAsStateWithLifecycle(emptyList())
    val counts by app.repo.documentCounts.collectAsStateWithLifecycle(emptyList())
    val allEvents by remember(g) { app.repo.eventsOf(g) }.collectAsStateWithLifecycle(emptyList())
    // Card art, flags and photos for this group decoded in the background, so every vault opens smoothly.
    LaunchedEffect(docs, members) { if (docs.isNotEmpty() || members.isNotEmpty()) com.familydocs.app.ui.components.warmPictures(app, docs, members) }
    var query by rememberSaveable { mutableStateOf("") }
    // Searching: a back arrow in the box (and the back gesture) clears the search and returns to the home page.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val searching = query.isNotBlank()
    fun endSearch() { query = ""; focus.clearFocus() }
    androidx.activity.compose.BackHandler(enabled = searching) { endSearch() }
    val self = members.firstOrNull { it.isSelf }
    val events = allEvents.filter { it.visibleTo(self?.id) && Events.isCurrent(it.startDay, it.endDay) }
    val groups by app.repo.groups.collectAsStateWithLifecycle(emptyList())
    val shared = groups.firstOrNull { it.id == g }?.let { it.peerList.isNotEmpty() || it.shareIndex != null } == true
    // passports, visas, insurance… running out within about 6 months (or just ran out)
    val expiring = remember(docs, members) { com.familydocs.app.events.ExpiryReminders.due(docs, members) }
    val full = GridItemSpan(2)
    // worked out once per query (not on every recomposition while something animates)
    val hits = remember(query, members, docs) { if (query.isBlank()) emptyList() else search(query, members, docs) }

    // "Made with ♥" sits at the very bottom of the screen even when there's little above it: a spacer before it takes
    // up whatever room is left (none once the page is longer than the screen).
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    var footerGap by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val searchingNow by androidx.compose.runtime.rememberUpdatedState(searching)
    LaunchedEffect(gridState) {
        androidx.compose.runtime.snapshotFlow { gridState.layoutInfo }.collect { info ->
            if (searchingNow) return@collect // (search results have no footer)
            val last = info.visibleItemsInfo.lastOrNull() ?: return@collect
            footerGap = if (last.index != info.totalItemsCount - 1) 0
                else (footerGap + info.viewportEndOffset - info.afterContentPadding - (last.offset.y + last.size.height)).coerceAtLeast(0)
        }
    }
    LazyVerticalGrid(
        GridCells.Fixed(2), Modifier.fillMaxSize(), state = gridState,
        contentPadding = PaddingValues(bottom = 36.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { full }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${greeting()}, ${account?.name?.substringBefore(' ').orEmpty()}", style = fdText(14f, color = c.muted), modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (shared) SyncStatusLine(onSettings)
                }
                FdInput(query, { query = it }, Modifier.padding(top = 14.dp), placeholder = "Search documents, numbers…", icon = if (searching) null else "search",
                    leading = if (searching) { { FdIconButton("back", ::endSearch, size = 30.dp, iconSize = 20.dp, description = "Back to home") } } else null)
                // A newer version on GitHub: a quiet hint that opens Settings at the update; while it downloads (it keeps
                // going if you come back here), the same box shows the progress.
                val update by app.updater.state.collectAsStateWithLifecycle()
                when (val u = update) {
                    is com.familydocs.app.update.AppUpdater.State.Available ->
                        FdBanner("cloud", "Lararium ${u.release.version} is available", Modifier.padding(top = 12.dp).clickable(onClick = onUpdates)) {
                            Text("Update", style = fdText(12.5f, FontWeight.SemiBold, c.primary))
                        }
                    is com.familydocs.app.update.AppUpdater.State.Downloading -> {
                        val shown by androidx.compose.animation.core.animateFloatAsState(u.progress, tween(300), label = "updateProgress")
                        Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.accentSoft).clickable(onClick = onUpdates)
                            .padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FdIcon("download", 18.dp)
                                Text("Downloading Lararium ${u.release.version}…", style = fdText(12.5f), modifier = Modifier.weight(1f))
                                Text("${(u.progress * 100).toInt()}%", style = fdText(12.5f, FontWeight.SemiBold))
                                FdIconButton("close", { app.updater.cancel() }, size = 28.dp, iconSize = 14.dp, tint = c.muted, description = "Stop the download")
                            }
                            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.surface)) {
                                Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(c.accent))
                            }
                        }
                    }
                    is com.familydocs.app.update.AppUpdater.State.Failed ->
                        FdBanner("info", "The update didn't finish", Modifier.padding(top = 12.dp).clickable(onClick = onUpdates)) {
                            Text("Retry", style = fdText(12.5f, FontWeight.SemiBold, c.primary))
                        }
                    else -> {}
                }
            }
        }

        if (query.isNotBlank()) {
            if (hits.isEmpty()) item(span = { full }) { FdEmpty("Nothing matches “$query”.", Modifier.padding(top = 10.dp)) }
            else items(hits, span = { full }) { h ->
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
                        .clickable { if (h.doc != null) onDocument(h.doc.id) else onMember(h.member.id) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (h.doc != null) FdDocIcon(h.doc) else FdAvatar(h.member.name, 46.dp, photoFile = h.member.photoFile, character = h.member.avatar, shared = h.member.shared)
                    Column(Modifier.weight(1f)) {
                        Text(h.doc?.name ?: h.member.name, style = fdText(14f, FontWeight.Bold))
                        Text(listOfNotNull(if (h.doc != null) h.member.name else h.member.relation, h.match).joinToString(" · "),
                            style = fdText(12f, color = c.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else {
            if (expiring.isNotEmpty()) item(span = { full }) { ExpiringBox(expiring, onDocument, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) }
            item(span = { full }) { Box(Modifier.size(28.dp)) }
            items(members, key = { it.id }) { m ->
                val n = counts.firstOrNull { it.memberId == m.id }?.n ?: 0
                Person(m.name, "${if (m.shared) "Shared" else m.relation} · $n doc${if (n == 1) "" else "s"}", { onMember(m.id) },
                    Modifier.padding(start = if (members.indexOf(m) % 2 == 0) 24.dp else 0.dp, end = if (members.indexOf(m) % 2 == 1) 24.dp else 0.dp, bottom = 30.dp)) {
                    FdAvatar(m.name, 132.dp, photoFile = m.photoFile, character = m.avatar, shared = m.shared, lift = true)
                }
            }
            // The + is for events now; adding family members lives in Settings › Family.
            item(span = { full }) { EventsStrip(events, onEvent, onNewEvent) }
            item(span = { full }) { Spacer(Modifier.height(with(androidx.compose.ui.platform.LocalDensity.current) { footerGap.toDp() })) }
            item(span = { full }) { com.familydocs.app.ui.ds.FdMadeWith(Modifier.padding(top = 30.dp, bottom = 6.dp)) }
        }
    }
}

/** `.mem`: big circle, name, relation · count. Presses scale the picture like the demo's hover. */
@Composable
private fun Person(title: String, subtitle: String?, onClick: () -> Unit, modifier: Modifier, muted: Boolean = false, picture: @Composable () -> Unit) {
    val c = Fd.c
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 1.03f else 1f, tween(200), label = "mem")
    Column(modifier.fillMaxWidth().clickable(interactionSource = source, indication = null, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.scale(s)) { picture() }
        Text(title, style = fdText(17f, if (muted) FontWeight.SemiBold else FontWeight.Bold, if (muted) c.muted else c.fg, lineHeight = 19f),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        if (subtitle != null) Text(subtitle, style = fdText(12.5f, color = c.muted), maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

/**
 * Sun/moon next to the lock: flips light ⇄ dark with the circular reveal from this button,
 * and the new icon spins in (demo `.spun`: from -120° and 40% size).
 */
@Composable
private fun ThemeButton() {
    val app = LocalContainer.current
    val reveal = LocalThemeReveal.current
    val scope = rememberCoroutineScope()
    val dark = Fd.c.dark
    val spin = remember { Animatable(0f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(dark) {
        if (first) { first = false; return@LaunchedEffect }
        spin.snapTo(1f); spin.animateTo(0f, tween(550, easing = CubicBezierEasing(.3f, .7f, .2f, 1f)))
    }
    Box(Modifier.onGloballyPositioned { reveal.origin = it.boundsInRoot().center }) {
        FdIconButton(if (dark) "sun" else "moon", {
            scope.launch { reveal.play { app.settings.update { it.copy(theme = if (dark) ThemeMode.LIGHT else ThemeMode.DARK) } } }
        }, iconSize = 19.dp, size = 40.dp, background = Fd.c.surface, description = if (dark) "Switch to light mode" else "Switch to dark mode",
            modifier = Modifier.border(1.dp, Fd.c.line, CircleShape).graphicsLayer { val v = spin.value; rotationZ = -120f * v; scaleX = 1f - .6f * v; scaleY = 1f - .6f * v; alpha = 1f - v })
    }
}

private class Hit(val member: MemberEntity, val doc: DocumentEntity?, val match: String?)

private fun search(q: String, members: List<MemberEntity>, docs: List<DocumentEntity>): List<Hit> {
    val needle = q.trim().lowercase(); val compact = needle.replace(" ", "")
    val out = mutableListOf<Hit>()
    members.forEach { m ->
        if (m.name.lowercase().contains(needle)) out += Hit(m, null, null)
        docs.filter { it.memberId == m.id }.forEach { d ->
            val f = d.fields.firstOrNull { it.value.lowercase().replace(" ", "").contains(compact) }
            if (d.name.lowercase().contains(needle) || f != null) out += Hit(m, d, f?.let { "${it.label}: ${it.value}" })
        }
    }
    return out.take(30)
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 0..4 -> "Up late"; in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening"
}

/**
 * Home header: the app's name (Bitcount Grid Double) on the left; on the right the theme and lock buttons, the group's
 * round picture (tap it for the group menu, or ＋ when there's no group yet) and your own picture (Settings).
 * The menu unfolds below: a big group picture, its name and code, Group settings, your other groups, New group, Join.
 */
@Composable
private fun GroupHeader(groups: List<com.familydocs.app.data.GroupEntity>, gid: String?, onSettings: () -> Unit, onCreate: () -> Unit, onJoin: () -> Unit,
                        onGroupSettings: (String) -> Unit) {
    val app = LocalContainer.current
    val c = Fd.c
    val account by app.repo.account.collectAsStateWithLifecycle(null)
    val self by app.repo.self().collectAsStateWithLifecycle(null)
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val toast = com.familydocs.app.ui.ds.LocalToast.current
    var open by remember { mutableStateOf(false) }
    val current = groups.firstOrNull { it.id == gid }
    val ring by androidx.compose.animation.animateColorAsState(if (open) c.accent else c.accent.copy(alpha = .55f), tween(200), label = "groupRing")
    Column {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // The name shrinks to whatever room the four buttons leave (narrow phones, large font settings), never cut off.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f)) {
                val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                val base = fdText(27f, color = c.fg, spacing = .02f).copy(fontFamily = com.familydocs.app.ui.ds.Bitcount)
                val room = constraints.maxWidth
                val fitted = remember(room, base) {
                    var k = 1f
                    fun at(k: Float) = base.copy(fontSize = base.fontSize * k, letterSpacing = base.letterSpacing * k)
                    while (k > .5f && measurer.measure("Lararium", at(k), softWrap = false, maxLines = 1).size.width > room) k -= .04f
                    at(k)
                }
                Text("Lararium", style = fitted, maxLines = 1, softWrap = false)
            }
            ThemeButton()
            FdIconButton("lock", { app.appLock.lock() }, Modifier.border(1.dp, c.line, CircleShape), size = 40.dp, iconSize = 19.dp, background = c.surface, description = "Lock app")
            // the group's picture: rolls to the new group when you switch
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { if (groups.isEmpty()) onCreate() else open = !open }, contentAlignment = Alignment.Center) {
                if (current == null) Box(Modifier.size(38.dp).dashedBorder(c.line, radius = 19.dp), contentAlignment = Alignment.Center) { FdIcon("plus", 18.dp, tint = c.muted, description = "Add a group") }
                else AnimatedContent(current.id, transitionSpec = {
                    (scaleIn(spring(dampingRatio = .55f, stiffness = 420f), initialScale = .6f) + fadeIn(tween(160))) togetherWith (scaleOut(tween(140), targetScale = .7f) + fadeOut(tween(120)))
                }, label = "groupPic") { _ -> GroupPic(current, 35.dp, Modifier.border(1.5.dp, ring, CircleShape).padding(2.5.dp), description = "Group: ${current.name}") }
            }
            Box(Modifier.clip(CircleShape).clickable(onClick = onSettings)) {
                FdAvatar(account?.name ?: "", 40.dp, photoFile = self?.photoFile ?: account?.photoFile, character = self?.avatar)
            }
        }
        AnimatedVisibility(open, enter = expandVertically(spring(dampingRatio = .82f, stiffness = 420f), expandFrom = Alignment.Top) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(220, easing = FdEase), shrinkTowards = Alignment.Top) + fadeOut(tween(150))) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
                .border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(vertical = 4.dp)) {
                current?.let { g ->
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val pop = remember { Animatable(.7f) }
                        LaunchedEffect(g.id) { pop.snapTo(.7f); pop.animateTo(1f, spring(dampingRatio = .55f, stiffness = 380f)) }
                        GroupPic(g, 92.dp, Modifier.scale(pop.value))
                        Text(g.name, style = fdText(20f, FontWeight.Bold), modifier = Modifier.padding(top = 10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(g.code, style = fdText(12.5f, color = c.fg.copy(alpha = .5f), spacing = .08f))
                            FdIconButton("copy", { clipboard.setText(androidx.compose.ui.text.AnnotatedString(g.code)); toast.show("Code copied") }, size = 30.dp, iconSize = 14.dp,
                                tint = c.muted, description = "Copy code")
                        }
                    }
                    Row(Modifier.fillMaxWidth().clickable { open = false; onGroupSettings(g.id) }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FdIcon("sliders", 18.dp, tint = c.muted); Text("Group settings", style = fdText(14f), modifier = Modifier.weight(1f))
                        Text("name, picture, backup, sharing", style = fdText(11.5f, color = c.muted))
                    }
                }
                val others = groups.filter { it.id != gid }
                if (others.isNotEmpty()) {
                    Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(1.dp).background(c.line))
                    others.forEach { g ->
                        Row(Modifier.fillMaxWidth().clickable { open = false; app.selectGroup(g.id) }.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GroupPic(g, 30.dp)
                            Column(Modifier.weight(1f)) {
                                Text(g.name, style = fdText(15f, FontWeight.Medium))
                                Text(g.code, style = fdText(11.5f, color = c.fg.copy(alpha = .45f), spacing = .08f))
                            }
                        }
                    }
                }
                Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(1.dp).background(c.line))
                Row(Modifier.fillMaxWidth().clickable { open = false; onCreate() }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) { FdIcon("plus", 18.dp, tint = c.muted); Text("New group", style = fdText(14f)) }
                Row(Modifier.fillMaxWidth().clickable { open = false; onJoin() }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) { FdIcon("key", 18.dp, tint = c.muted); Text("Join a group", style = fdText(14f)) }
            }
        }
    }
}

/** New group / join with code: one field, one button; [onSubmit] returns an error to show, or null when done. */
@Composable
private fun GroupSheet(title: String, text: String, placeholder: String, action: String, onDismiss: () -> Unit, multiline: Boolean = false, onSubmit: suspend (String) -> String?) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    com.familydocs.app.ui.ds.FdSheet(onDismiss) {
        com.familydocs.app.ui.ds.SheetTitle(title, text)
        FdInput(value, { value = it; error = null }, placeholder = placeholder, singleLine = !multiline)
        error?.let { Text(it, style = fdText(12.5f, color = Fd.c.danger), modifier = Modifier.padding(top = 8.dp)) }
        var busy by remember { mutableStateOf(false) }
        com.familydocs.app.ui.ds.FdButton(if (busy) "One moment…" else action, { scope.launch { busy = true; error = onSubmit(value); busy = false; if (error == null) onDismiss() } },
            Modifier.padding(top = 14.dp), enabled = value.isNotBlank() && !busy)
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * "What's new in 0.8.6": the changes since the version you had, with ✕. Skipped versions are included: the newest
 * shows open, the earlier ones fold out under "Also new since you last updated".
 */
@Composable
private fun WhatsNewBox(entries: List<com.familydocs.app.domain.Changelog.Entry>, modifier: Modifier = Modifier, onClose: () -> Unit) {
    val c = Fd.c
    var all by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.accentSoft).animateContentSize(tween(300, easing = com.familydocs.app.ui.ds.FdEase))
        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FdIcon("sparkle", 18.dp)
            Text("What's new in ${entries.first().version}", style = fdText(13.5f, FontWeight.SemiBold), modifier = Modifier.weight(1f))
            FdIconButton("close", onClose, size = 32.dp, iconSize = 15.dp, description = "Close")
        }
        Column(Modifier.padding(start = 28.dp, end = 10.dp)) {
            val shown = if (all) entries else entries.take(1)
            shown.forEach { e ->
                if (shown.size > 1) Text(e.version, style = fdText(11.5f, FontWeight.SemiBold, c.muted), modifier = Modifier.padding(top = 6.dp))
                e.notes.forEach { n ->
                    Row(Modifier.padding(top = 4.dp)) {
                        Text("•", style = fdText(12.5f, color = c.accent), modifier = Modifier.width(12.dp))
                        Text(n, style = fdText(12.5f), modifier = Modifier.weight(1f))
                    }
                }
            }
            if (entries.size > 1) Text(if (all) "Show less" else "Also new since you last updated: ${entries.drop(1).joinToString(", ") { it.version }} ▾",
                style = fdText(12f, FontWeight.SemiBold, c.primary), modifier = Modifier.padding(top = 8.dp).clickable { all = !all })
        }
    }
}

/** Home: "Not synced with everyone in Family" (or with one person by name); tap to open [SyncSheet]. */
@Composable
private fun SyncBox(g: com.familydocs.app.data.GroupEntity, waiting: List<com.familydocs.app.data.Peer>, modifier: Modifier, onOpen: () -> Unit) {
    val c = Fd.c
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.accentSoft).clickable(onClick = onOpen).padding(start = 12.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FdIcon("refresh", 18.dp)
        Text(if (waiting.size == 1 && g.peerList.size > 1) "Not synced with ${waiting[0].name.ifBlank { "someone" }} in ${g.name}"
             else if (g.peerList.size == 1) "Not synced with ${waiting[0].name.ifBlank { "the others" }} in ${g.name} yet"
             else "Not synced with everyone in ${g.name}", style = fdText(12.5f, FontWeight.Medium), modifier = Modifier.weight(1f), maxLines = 2)
        Text("Show", style = fdText(12.5f, FontWeight.SemiBold, c.primary))
    }
}

/**
 * Who in [g] you're not synced with yet and why, with your link to send them (tapping it on their phone adds you).
 * A swipe down or a tap outside minimises it to the box on Home; once everyone's synced it says so.
 */
@Composable
private fun SyncSheet(g: com.familydocs.app.data.GroupEntity, onMinimise: () -> Unit) {
    val app = LocalContainer.current
    val c = Fd.c
    val waiting = g.peerList.filter { !it.synced }
    val members by remember(g.id) { app.repo.membersOf(g.id) }.collectAsStateWithLifecycle(emptyList())
    var sending by remember { mutableStateOf(false) }
    val send = com.familydocs.app.ui.home.rememberInviteSender { sending = it }
    var qr by remember { mutableStateOf<com.familydocs.app.domain.GroupInvite?>(null) }
    val showQr = com.familydocs.app.ui.home.rememberInvite({ sending = it }) { qr = it }
    // look again now (their phones may have added you meanwhile)
    LaunchedEffect(g.id) { com.familydocs.app.backup.BackupWorker.syncShares(app.appContext) }
    val names = com.familydocs.app.data.Peer.names(waiting.map { it.name.ifBlank { "someone" } })
    com.familydocs.app.ui.ds.FdSheet(onMinimise) {
        Column(Modifier.padding(bottom = 12.dp)) {
            Text(if (waiting.isEmpty()) "You're synced with everyone" else "Not synced with $names yet", style = fdText(18f, FontWeight.Bold))
            Text("in ${g.name}", style = fdText(13f, color = c.muted), modifier = Modifier.padding(top = 2.dp))
        }
        if (waiting.isEmpty()) {
            FdBanner("check", "Everyone in ${g.name} sees your documents, and you see theirs.")
            com.familydocs.app.ui.ds.FdButton("Done", onMinimise, Modifier.padding(top = 16.dp))
        } else {
            Text("Send them your link. Tapping it on their phone adds you, and you're synced. Sending it once to the family chat is enough.",
                style = fdText(13f, color = c.muted), modifier = Modifier.padding(bottom = 12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp))) {
                waiting.forEachIndexed { i, p ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                    val m = members.firstOrNull { it.origin == p.key && !it.shared }
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        com.familydocs.app.ui.ds.FdAvatar(p.name.ifBlank { m?.name ?: "?" }, 36.dp, photoFile = m?.photoFile, character = m?.avatar)
                        Column(Modifier.weight(1f)) {
                            Text(p.name.ifBlank { m?.name ?: "Group member" }, style = fdText(14.5f, FontWeight.Medium))
                            Text(when { p.at == 0L -> "Not read yet"; p.sees == false -> "Hasn't added you yet"; else -> "Syncing…" }, style = fdText(12f, color = c.muted))
                        }
                    }
                }
            }
            com.familydocs.app.ui.ds.FdButton(if (sending) "Preparing your link…" else "Send my link to them", { send(g.id) }, Modifier.padding(top = 16.dp),
                icon = "share", enabled = !sending)
            com.familydocs.app.ui.ds.FdTextButton("They're right here: show a QR code", { showQr(g.id) }, enabled = !sending)
            Text("Already sent it? You're synced as soon as they tap it; this stays on Home until then.",
                style = fdText(12f, color = c.muted), modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(10.dp))
    }
    qr?.let { inv -> InviteQrSheet(inv, g.name) { qr = null } }
}

/**
 * Home, next to the greeting: "● Up to date · just now", "Sharing your changes…", or what's wrong (tap: retry, or
 * Settings when Google Drive needs you to sign in again).
 */
@Composable
private fun SyncStatusLine(onSettings: () -> Unit) {
    val app = LocalContainer.current
    val c = Fd.c
    val s by app.syncStatus.collectAsStateWithLifecycle()
    var now by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(10_000) } }
    val last = maxOf(s.sharedAt, s.checkedAt)
    val ago = (now - last).coerceAtLeast(0L) / 1000
    val (dot, text) = when {
        s.problem != null -> c.danger to s.problem!!
        s.offline -> c.muted to "Offline"
        s.sharing -> c.accent to "Sharing…"
        last == 0L -> c.muted to "Syncing…"
        else -> c.ok to "Up to date · " + when { ago < 60 -> "just now"; ago < 3600 -> "${ago / 60} min ago"; else -> "${ago / 3600} h ago" }
    }
    val tint by androidx.compose.animation.animateColorAsState(dot, tween(300), label = "syncDot")
    Row(Modifier.clip(RoundedCornerShape(99.dp)).clickable(enabled = s.problem != null) { if (s.needsGoogle) onSettings() else app.publishSoon() }
        .padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
        Text(text, style = fdText(11.5f, color = if (s.problem != null) c.danger else c.muted), maxLines = 1)
    }
}

/** Home: documents running out within about 6 months (or that just did), soonest first; tap one to open it. */
@Composable
private fun ExpiringBox(list: List<com.familydocs.app.events.ExpiryReminders.Due>, onDocument: (String) -> Unit, modifier: Modifier = Modifier) {
    val app = LocalContainer.current
    val c = Fd.c
    val context = androidx.compose.ui.platform.LocalContext.current
    var all by remember { mutableStateOf(false) }
    var canNotify by remember { mutableStateOf(com.familydocs.app.events.EventReminders.canNotify(context)) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        canNotify = ok
        if (ok) com.familydocs.app.events.ExpiryReminders.schedule(context)
    }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))
        .animateContentSize(tween(260, easing = com.familydocs.app.ui.ds.FdEase)).padding(vertical = 6.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FdIcon("calendar", 17.dp, tint = c.accent)
            Text("Expiring soon", style = fdText(13.5f, FontWeight.SemiBold), modifier = Modifier.weight(1f))
            if (!canNotify && android.os.Build.VERSION.SDK_INT >= 33)
                com.familydocs.app.ui.ds.FdTextButton("Remind me", { app.appLock.expectExternalActivity(); ask.launch(android.Manifest.permission.POST_NOTIFICATIONS) })
        }
        (if (all) list else list.take(3)).forEach { d ->
            val urgent = d.level != com.familydocs.app.domain.Expiry.Level.SIX_MONTHS
            Row(Modifier.fillMaxWidth().clickable { onDocument(d.doc.id) }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FdDocIcon(d.doc, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text("${d.doc.name} · ${if (d.member.isSelf) "You" else d.member.name}", style = fdText(14f, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(com.familydocs.app.domain.Expiry.label(d.expiry), style = fdText(12f, if (urgent) FontWeight.SemiBold else FontWeight.Normal, if (urgent) c.danger else c.muted))
                }
                FdIcon("chev", 16.dp, tint = c.muted)
            }
        }
        if (list.size > 3) Text(if (all) "Show less" else "Show all ${list.size}", style = fdText(12.5f, FontWeight.SemiBold, c.primary),
            modifier = Modifier.padding(start = 14.dp, top = 2.dp, bottom = 6.dp).clickable { all = !all })
    }
}
