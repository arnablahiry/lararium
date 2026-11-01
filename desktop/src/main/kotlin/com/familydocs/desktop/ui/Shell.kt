package com.familydocs.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familydocs.app.data.DocumentEntity
import com.familydocs.app.data.EventEntity
import com.familydocs.app.data.GroupEntity
import com.familydocs.app.data.MemberEntity
import com.familydocs.app.ui.components.rememberVaultImage
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdAvatar
import com.familydocs.app.ui.ds.FdIcon
import com.familydocs.app.ui.ds.FdInput
import com.familydocs.app.ui.ds.fdPainter
import com.familydocs.app.ui.ds.fdText
import com.familydocs.desktop.Page
import com.familydocs.desktop.Theme
import com.familydocs.desktop.VaultData
import com.familydocs.desktop.focusSearch
import com.familydocs.desktop.rememberApp
import kotlinx.coroutines.delay

/** What the current group holds: its people (you first, Common), their documents and the events you can see. */
class GroupView(val data: VaultData, val group: GroupEntity?) {
    val members: List<MemberEntity> = data.members.filter { it.isSelf || it.groupId == group?.id }
    private val ids = members.map { it.id }.toSet()
    val allDocs: List<DocumentEntity> = data.docs.filter { it.memberId in ids }
    /** In the wallet: not event tickets, not archived. */
    val docs: List<DocumentEntity> = allDocs.filter { it.eventId == null && !it.archived }
    val events: List<EventEntity> = data.events.filter { e -> e.groupId == group?.id && (e.visibleIds.isEmpty() || data.me == null || data.me in e.visibleIds) }
    fun member(id: String?) = data.members.firstOrNull { it.id == id }
    fun doc(id: String?) = data.docs.firstOrNull { it.id == id }
    fun count(memberId: String) = docs.count { it.memberId == memberId }
}

@Composable
fun rememberGroupView(): GroupView? {
    val app = rememberApp()
    val data = app.data ?: return null
    val g = app.currentGroup
    return remember(data, g) { GroupView(data, g) }
}

/** The main window: the sidebar (groups, search, people, events, you) and the page you're on. */
@Composable
fun Shell() {
    val app = rememberApp()
    val c = Fd.c
    val view = rememberGroupView() ?: return
    Row(Modifier.fillMaxSize().background(c.bg)) {
        Sidebar(view)
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
        AnimatedContent(app.page, Modifier.weight(1f).fillMaxHeight(), transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "page") { p ->
            when (p) {
                Page.Home -> HomePage(view)
                is Page.Person -> PersonPage(view, p.id)
                is Page.Doc -> DocumentPage(view, p.id)
                Page.Events -> EventsPage(view)
                is Page.Event -> EventPage(view, p.id)
                is Page.Search -> SearchPage(view, p.query)
            }
        }
    }
}

@Composable
private fun Sidebar(view: GroupView) {
    val app = rememberApp()
    val c = Fd.c
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    DisposableEffect(Unit) { app.focusSearch = { focus.requestFocus() }; onDispose { app.focusSearch = null } }
    // searching as you type
    LaunchedEffect(query) {
        if (query.isBlank()) { if (app.page is Page.Search) app.back(); return@LaunchedEffect }
        delay(150)
        if (app.page is Page.Search) app.stack[app.stack.lastIndex] = Page.Search(query) else app.go(Page.Search(query))
    }
    Column(Modifier.width(272.dp).fillMaxHeight().background(c.surface.copy(alpha = if (c.dark) .55f else .7f))) {
        // room for the window's traffic lights
        Spacer(Modifier.height(40.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            Box(Modifier.padding(start = 6.dp, bottom = 14.dp)) { Wordmark(28f) }
            GroupSwitcher(view)
            FdInput(query, { query = it }, Modifier.padding(top = 12.dp).focusRequester(focus).onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && query.isNotEmpty()) { query = ""; true } else false
            }, placeholder = "Search  ⌘F", icon = "search")
            Spacer(Modifier.height(14.dp))
            NavRow("house", "Home", app.page == Page.Home) { query = ""; app.home() }
            NavRow("calendar", "Events", app.page == Page.Events || app.page is Page.Event, badge = view.events.count { com.familydocs.app.domain.Events.isCurrent(it.startDay, it.endDay) }.takeIf { it > 0 }?.toString()) {
                query = ""; app.go(Page.Events)
            }
            Text("PEOPLE", style = fdText(11f, FontWeight.SemiBold, c.muted, spacing = .09f), modifier = Modifier.padding(start = 10.dp, top = 22.dp, bottom = 8.dp))
            view.members.forEach { m ->
                val on = (app.page as? Page.Person)?.id == m.id
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) c.surface2 else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { query = ""; app.go(Page.Person(m.id)) }.padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FdAvatar(m.name, 30.dp, photoFile = m.photoFile, character = m.avatar, shared = m.shared)
                    Text(if (m.isSelf) "${m.name} (you)" else m.name, style = fdText(13.5f, if (on) FontWeight.SemiBold else FontWeight.Normal),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${view.count(m.id)}", style = fdText(11.5f, color = c.muted))
                }
            }
        }
        Footer()
    }
}

@Composable
private fun NavRow(icon: String, label: String, on: Boolean, badge: String? = null, onClick: () -> Unit) {
    val c = Fd.c
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) c.surface2 else androidx.compose.ui.graphics.Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FdIcon(icon, 19.dp, tint = if (on) c.fg else c.muted)
        Text(label, style = fdText(14f, if (on) FontWeight.SemiBold else FontWeight.Normal), modifier = Modifier.weight(1f))
        badge?.let { Box(Modifier.clip(RoundedCornerShape(99.dp)).background(c.accentSoft).padding(horizontal = 7.dp, vertical = 1.dp)) { Text(it, style = fdText(11f, FontWeight.SemiBold)) } }
    }
}

/** The group's picture and name; with more than one group, a click switches between them. */
@Composable
private fun GroupSwitcher(view: GroupView) {
    val app = rememberApp()
    val c = Fd.c
    val groups = view.data.groups
    var open by remember { mutableStateOf(false) }
    val g = view.group ?: return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(4.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = groups.size > 1) { open = !open }.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GroupPic(g, 34)
            Column(Modifier.weight(1f)) {
                Text(g.name, style = fdText(14.5f, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${view.members.size} ${if (view.members.size == 1) "person" else "people"} · ${view.docs.size} documents", style = fdText(11.5f, color = c.muted))
            }
            if (groups.size > 1) FdIcon("chev", 15.dp, tint = c.muted)
        }
        if (open) groups.filter { it.id != g.id }.forEach { o ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = false; app.selectGroup(o.id) }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GroupPic(o, 28)
                Text(o.name, style = fdText(13.5f))
            }
        }
    }
}

@Composable
fun GroupPic(g: GroupEntity, size: Int) {
    val photo = rememberVaultImage(g.photoFile, 400)
    val m = Modifier.size(size.dp).clip(CircleShape)
    if (photo != null) Image(photo, g.name, m, contentScale = ContentScale.Crop)
    else Image(fdPainter("art_group_default"), g.name, m, contentScale = ContentScale.Crop)
}

/** You, when it was last read from Drive (refresh), lock, theme and sign out. */
@Composable
private fun Footer() {
    val app = rememberApp()
    val c = Fd.c
    val data = app.data ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    val self = data.members.firstOrNull { it.isSelf }
    Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FdAvatar(data.account?.name ?: "", 34.dp, photoFile = self?.photoFile ?: data.account?.photoFile, character = self?.avatar)
            Column(Modifier.weight(1f)) {
                Text(data.account?.name ?: self?.name ?: "You", style = fdText(13.5f, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.email.ifBlank { data.account?.backupEmail.orEmpty() }, style = fdText(11f, color = c.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val ago = ((now - data.loadedAt) / 1000).coerceAtLeast(0)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (app.error != null) c.danger else if (app.refreshing) c.accent else c.ok))
            Text(when {
                app.refreshing -> "Updating…"
                app.error != null -> "Couldn't update"
                ago < 60 -> "Up to date · just now"
                ago < 3600 -> "Up to date · ${ago / 60} min ago"
                else -> "Up to date · ${ago / 3600} h ago"
            }, style = fdText(11.5f, color = if (app.error != null) c.danger else c.muted), modifier = Modifier.weight(1f))
        }
        if (data.problems.isNotEmpty()) Text(data.problems.joinToString("\n"), style = fdText(10.5f, color = c.muted), modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            FooterButton("refresh", "Update now (⌘R)") { app.load() }
            FooterButton("lock", "Lock (⌘L)", enabled = app.passcode != null) { app.lock() }
            FooterButton(if (c.dark) "sun" else "moon", "Light / dark") {
                app.chooseTheme(when (app.theme) { Theme.SYSTEM -> if (c.dark) Theme.LIGHT else Theme.DARK; Theme.LIGHT -> Theme.DARK; Theme.DARK -> Theme.LIGHT })
            }
            Spacer(Modifier.weight(1f))
            FooterButton("logout", "Sign out") { app.signOut() }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FooterButton(icon: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Fd.c
    androidx.compose.foundation.TooltipArea(tooltip = {
        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(c.fg).padding(horizontal = 8.dp, vertical = 4.dp)) { Text(label, style = fdText(11.5f, color = c.bg)) }
    }) {
        Box(Modifier.size(32.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
            FdIcon(icon, 17.dp, tint = if (enabled) c.muted else c.line)
        }
    }
}

