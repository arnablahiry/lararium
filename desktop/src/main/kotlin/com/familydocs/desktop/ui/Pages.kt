package com.familydocs.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.familydocs.app.data.DocumentEntity
import com.familydocs.app.data.EventEntity
import com.familydocs.app.data.MemberEntity
import com.familydocs.app.domain.Countries
import com.familydocs.app.domain.Events
import com.familydocs.app.domain.Expiry
import com.familydocs.app.ocr.FieldKeys
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdAvatar
import com.familydocs.app.ui.ds.FdDocIcon
import com.familydocs.app.ui.ds.FdEmpty
import com.familydocs.app.ui.ds.FdEventPic
import com.familydocs.app.ui.ds.FdFlag
import com.familydocs.app.ui.ds.FdIcon
import com.familydocs.app.ui.ds.FdIconButton
import com.familydocs.app.ui.ds.FdListItem
import com.familydocs.app.ui.ds.FdMadeWith
import com.familydocs.app.ui.ds.FdSectionTitle
import com.familydocs.app.ui.ds.FdSegmented
import com.familydocs.app.ui.ds.FdWalletCard
import com.familydocs.app.ui.ds.fdText
import com.familydocs.app.ui.ds.tail
import com.familydocs.app.ui.ds.validUntil
import com.familydocs.desktop.Page
import com.familydocs.desktop.rememberApp
import java.time.LocalDate
import java.util.Calendar

/** A page's scrolling column, as wide as reads well, with the Mac's scrollbar. */
@Composable
fun PageColumn(maxWidth: Dp = 1100.dp, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = maxWidth).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 40.dp), content = content)
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp))
    }
}

/** Back (and a title) at the top of a page. */
@Composable
fun PageBar(title: String = "", right: @Composable () -> Unit = {}) {
    val app = rememberApp()
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (app.stack.size > 1) FdIconButton("back", { app.back() }, size = 38.dp, iconSize = 20.dp, description = "Back (Esc)")
        Text(title, style = fdText(17f, FontWeight.SemiBold), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        right()
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Hello"
}

/** A document whose expiry is coming up (or just passed). */
data class Due(val doc: DocumentEntity, val member: MemberEntity, val expiry: LocalDate, val level: Expiry.Level)

fun expiring(view: GroupView, today: LocalDate = LocalDate.now()): List<Due> = view.docs.mapNotNull { d ->
    val m = view.member(d.memberId) ?: return@mapNotNull null
    val e = Expiry.parse(d.fields.firstOrNull { FieldKeys.canonical(it.label) == "expiry" && it.value.isNotBlank() }?.value) ?: return@mapNotNull null
    val level = Expiry.level(e, today) ?: return@mapNotNull null
    if (level == Expiry.Level.EXPIRED && Expiry.daysLeft(e, today) < -90) return@mapNotNull null
    Due(d, m, e, level)
}.sortedBy { it.expiry }

// ---------------- home ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomePage(view: GroupView) {
    val app = rememberApp()
    val c = Fd.c
    val first = view.data.account?.name?.substringBefore(' ').orEmpty()
    PageColumn {
        Text("${greeting()}, $first", style = fdText(14f, color = c.muted))
        Text(view.group?.name ?: "Your vault", style = fdText(30f, FontWeight.Bold), modifier = Modifier.padding(top = 2.dp))
        val due = remember(view) { expiring(view) }
        if (due.isNotEmpty()) {
            FdSectionTitle("Expiring soon", Modifier.padding(start = 0.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(vertical = 4.dp)) {
                due.take(6).forEach { d ->
                    val urgent = d.level != Expiry.Level.SIX_MONTHS
                    Row(Modifier.fillMaxWidth().clickable { app.go(Page.Doc(d.doc.id)) }.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FdDocIcon(d.doc, 40.dp)
                        Column(Modifier.weight(1f)) {
                            Text("${d.doc.name} · ${if (d.member.isSelf) "You" else d.member.name}", style = fdText(14f, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(Expiry.label(d.expiry), style = fdText(12f, if (urgent) FontWeight.SemiBold else FontWeight.Normal, if (urgent) c.danger else c.muted))
                        }
                        FdIcon("chev", 16.dp, tint = c.muted)
                    }
                }
            }
        }
        var addingPerson by remember { mutableStateOf(false) }
        FdSectionTitle("People", Modifier.padding(start = 0.dp)) {
            com.familydocs.app.ui.ds.FdTextButton("Add a person", { addingPerson = true })
        }
        if (addingPerson) PersonEditSheet(view, null, view.group?.id) { addingPerson = false }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            view.members.forEach { m ->
                Column(Modifier.width(150.dp).clip(RoundedCornerShape(20.dp)).clickable { app.go(Page.Person(m.id)) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    FdAvatar(m.name, 120.dp, photoFile = m.photoFile, character = m.avatar, shared = m.shared, lift = true)
                    Text(m.name, style = fdText(16f, FontWeight.SemiBold), modifier = Modifier.padding(top = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val n = view.count(m.id)
                    Text("${if (m.shared) "Shared" else if (m.isSelf) "You" else m.relation} · $n doc${if (n == 1) "" else "s"}", style = fdText(12f, color = c.muted))
                }
            }
        }
        val upcoming = view.events.filter { Events.isCurrent(it.startDay, it.endDay) }
        if (upcoming.isNotEmpty()) {
            FdSectionTitle("Events", Modifier.padding(start = 0.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                upcoming.forEach { e -> EventTile(e) { app.go(Page.Event(e.id)) } }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
fun EventTile(e: EventEntity, onClick: () -> Unit) {
    val c = Fd.c
    Row(Modifier.width(300.dp).clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))
        .clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FdEventPic(e.art, e.photoFile, 54.dp)
        Column(Modifier.weight(1f)) {
            Text(e.name, style = fdText(14.5f, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Events.dates(e.startDay, e.endDay), style = fdText(12f, color = c.muted))
            Text(Events.countdown(e.startDay, e.endDay), style = fdText(12f, FontWeight.Medium, c.accent))
        }
    }
}

// ---------------- a person's vault ----------------

enum class VaultLook { CARDS, LIST }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonPage(view: GroupView, id: String) {
    val app = rememberApp()
    val c = Fd.c
    val m = view.member(id) ?: run { FdEmpty("This person isn't in this group."); return }
    val all = view.docs.filter { it.memberId == id }.sortedWith(compareBy({ it.sortOrder ?: Int.MAX_VALUE }, { it.createdAt }))
    val archived = view.allDocs.filter { it.memberId == id && it.archived && it.eventId == null }
    var look by remember { mutableStateOf(VaultLook.CARDS) }
    var showArchived by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    // country filter, as on the phone: the pills show when there's more than one country
    val order = Countries.decode(view.data.account?.countries)
    val codes = all.map { it.issuer }.filter { it.isNotEmpty() }.distinct().sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }
    var hidden by remember(id) { mutableStateOf(setOf<String>()) }
    val docs = if (codes.size > 1) all.filter { it.issuer !in hidden } else all
    val canAdd = m.origin == null || view.group?.isAdmin(view.data.me) == true
    PageColumn {
        PageBar {
            if (view.canEdit(m)) com.familydocs.app.ui.ds.FdButton(if (m.isSelf) "Edit profile" else "Edit", { editing = true }, Modifier.width(150.dp),
                com.familydocs.app.ui.ds.BtnKind.Ghost, icon = "edit")
            if (canAdd) com.familydocs.app.ui.ds.FdButton("Add document", { adding = true }, Modifier.width(190.dp), icon = "plus")
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            FdAvatar(m.name, 104.dp, photoFile = m.photoFile, character = m.avatar, shared = m.shared, lift = true)
            Text(m.name, style = fdText(28f), modifier = Modifier.padding(top = 12.dp))
            Text("${if (m.shared) "Shared" else if (m.isSelf) "You" else m.relation} · ${all.size} document${if (all.size == 1) "" else "s"}",
                style = fdText(13f, color = c.muted), modifier = Modifier.padding(top = 4.dp))
        }
        FdSectionTitle("Vault", Modifier.padding(start = 0.dp)) {
            FdSegmented(listOf(Triple(VaultLook.CARDS, "card", "Cards"), Triple(VaultLook.LIST, "sliders", "List")), look) { look = it }
        }
        if (codes.size > 1) FlowRow(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            codes.forEach { code -> CountryPill(code, all.count { it.issuer == code }, code !in hidden) { hidden = if (code in hidden) hidden - code else hidden + code } }
        }
        if (docs.isEmpty()) FdEmpty(if (all.isEmpty()) "No documents yet. Add one here or on the phone." else "No documents for the selected countries.")
        else when (look) {
            VaultLook.CARDS -> CardGrid(docs, m.name) { app.go(Page.Doc(it.id)) }
            VaultLook.LIST -> DocList(docs) { app.go(Page.Doc(it.id)) }
        }
        if (archived.isNotEmpty()) {
            Row(Modifier.padding(top = 22.dp).clip(RoundedCornerShape(99.dp)).clickable { showArchived = !showArchived }.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FdIcon("archive", 16.dp, tint = c.muted)
                Text("Archived · ${archived.size}", style = fdText(12.5f, FontWeight.SemiBold, c.muted))
            }
            if (showArchived) Box(Modifier.padding(top = 8.dp).graphicsLayer { alpha = .75f }) { DocList(archived) { app.go(Page.Doc(it.id)) } }
        }
    }
    if (adding) DocEditSheet(view, null, m.id, onDismiss = { adding = false }, onSaved = { app.go(Page.Doc(it)) })
    if (editing) PersonEditSheet(view, m, m.groupId) { editing = false }
}

/** `.cpill`: country filter; off = half transparent with a grey flag. */
@Composable
private fun CountryPill(code: String, count: Int, on: Boolean, onClick: () -> Unit) {
    val c = Fd.c
    val a by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else .5f, androidx.compose.animation.core.tween(200), label = "pill")
    val border by androidx.compose.animation.animateColorAsState(if (on) c.primary else c.line, androidx.compose.animation.core.tween(200), label = "pillB")
    Row(
        Modifier.graphicsLayer { alpha = a }.clip(RoundedCornerShape(99.dp)).background(if (on) c.mix(c.primary, .08f, c.surface) else c.surface)
            .border(1.5.dp, border, RoundedCornerShape(99.dp)).clickable(onClick = onClick).padding(start = 11.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        FdFlag(code, 20.dp, greyscale = !on)
        Text(Countries.short(code), style = fdText(14f, FontWeight.SemiBold, spacing = .02f))
        Box(Modifier.clip(RoundedCornerShape(99.dp)).background(c.mix(c.fg, .07f, c.surface)).padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center) { Text("$count", style = fdText(12f, FontWeight.Medium, c.muted)) }
    }
}

/** The wallet as a grid of cards (the phone's cards, laid out side by side). */
@Composable
fun CardGrid(docs: List<DocumentEntity>, holder: String, onOpen: (DocumentEntity) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 3 side by side in a window filling the screen (as wide as they read well), fewer as the window gets narrower
        val cols = ((maxWidth + 18.dp) / 318.dp).toInt().coerceIn(1, 3)
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            docs.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    row.forEach { d ->
                        Box(Modifier.weight(1f).aspectRatio(1.586f).clip(RoundedCornerShape(18.dp)).clickable { onOpen(d) }) {
                            FdWalletCard(d, holder, open = false, Modifier.fillMaxSize())
                        }
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun DocList(docs: List<DocumentEntity>, onOpen: (DocumentEntity) -> Unit) {
    val c = Fd.c
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))) {
        docs.forEachIndexed { i, d ->
            FdListItem(d.name, d.tail(), first = i == 0, onClick = { onOpen(d) }, leading = { FdDocIcon(d, 42.dp) },
                extraLines = listOfNotNull(d.validUntil()?.let { "Valid until $it" }),
                trailing = { FdFlag(d.issuer, 20.dp); FdIcon("chev", 18.dp, tint = c.muted) }, chevron = false)
        }
    }
}

// ---------------- search ----------------

@Composable
fun SearchPage(view: GroupView, query: String) {
    val app = rememberApp()
    val c = Fd.c
    val q = query.trim().lowercase()
    val people = view.members.filter { it.name.lowercase().contains(q) }
    val hits = view.allDocs.filter { d ->
        d.name.lowercase().contains(q) || d.fields.any { it.value.lowercase().contains(q) || it.label.lowercase().contains(q) } || d.rawText.lowercase().contains(q) ||
            view.member(d.memberId)?.name?.lowercase()?.contains(q) == true
    }
    PageColumn(maxWidth = 900.dp) {
        Text("“$query”", style = fdText(24f, FontWeight.Bold))
        Text("${hits.size} document${if (hits.size == 1) "" else "s"}${if (people.isNotEmpty()) " · ${people.size} ${if (people.size == 1) "person" else "people"}" else ""}",
            style = fdText(13f, color = c.muted), modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        if (people.isNotEmpty()) Column(Modifier.padding(bottom = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))) {
            people.forEachIndexed { i, m ->
                FdListItem(m.name, if (m.shared) "Shared" else m.relation, first = i == 0, onClick = { app.go(Page.Person(m.id)) },
                    leading = { FdAvatar(m.name, 40.dp, photoFile = m.photoFile, character = m.avatar, shared = m.shared) })
            }
        }
        if (hits.isEmpty() && people.isEmpty()) FdEmpty("Nothing matches “$query”.")
        else if (hits.isNotEmpty()) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))) {
            hits.forEachIndexed { i, d ->
                val match = d.fields.firstOrNull { it.value.lowercase().contains(q) }?.let { "${it.label}: ${it.value}" }
                FdListItem(d.name, listOfNotNull(view.member(d.memberId)?.name, if (d.archived) "archived" else null, match).joinToString(" · "), first = i == 0,
                    onClick = { app.go(Page.Doc(d.id)) }, leading = { FdDocIcon(d, 42.dp) })
            }
        }
    }
}

// ---------------- events ----------------

@Composable
fun EventsPage(view: GroupView) {
    val app = rememberApp()
    val c = Fd.c
    val (now, past) = view.events.partition { Events.isCurrent(it.startDay, it.endDay) }
    var adding by remember { mutableStateOf(false) }
    if (adding) EventEditSheet(view, null, onDismiss = { adding = false }, onSaved = { app.go(Page.Event(it)) })
    PageColumn(maxWidth = 900.dp) {
        PageBar("Events") { com.familydocs.app.ui.ds.FdButton("New event", { adding = true }, Modifier.width(170.dp), icon = "plus") }
        if (view.events.isEmpty()) FdEmpty("No events yet. Trips, birthdays and appointments: add one here or on the phone.")
        if (now.isNotEmpty()) {
            FdSectionTitle("Coming up", Modifier.padding(start = 0.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { now.forEach { e -> EventRow(e) { app.go(Page.Event(e.id)) } } }
        }
        if (past.isNotEmpty()) {
            FdSectionTitle("Past", Modifier.padding(start = 0.dp))
            Column(Modifier.graphicsLayer { alpha = .8f }, verticalArrangement = Arrangement.spacedBy(10.dp)) { past.reversed().forEach { e -> EventRow(e) { app.go(Page.Event(e.id)) } } }
        }
    }
}

@Composable
private fun EventRow(e: EventEntity, onClick: () -> Unit) {
    val c = Fd.c
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))
        .clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        FdEventPic(e.art, e.photoFile, 58.dp)
        Column(Modifier.weight(1f)) {
            Text(e.name, style = fdText(15f, FontWeight.SemiBold))
            Text("${Events.dates(e.startDay, e.endDay)} · ${Events.countdown(e.startDay, e.endDay)}", style = fdText(12.5f, color = c.muted))
        }
        FdIcon("chev", 18.dp, tint = c.muted)
    }
}

@Composable
fun EventPage(view: GroupView, id: String) {
    val app = rememberApp()
    val c = Fd.c
    val e = view.events.firstOrNull { it.id == id } ?: run { FdEmpty("This event isn't here any more."); return }
    val people = e.peopleIds.mapNotNull { view.member(it) }
    val pinned = e.pinIds.mapNotNull { pid -> view.data.docs.firstOrNull { it.id == pid && it.eventId == null } }
    val own = view.data.docs.filter { it.eventId == e.id }
    val others = e.visibleIds.filter { it != view.data.me }.mapNotNull { view.member(it)?.name }
    val organiser = e.organiserKey?.let { k -> view.data.members.firstOrNull { it.origin == k && !it.shared }?.name ?: "another member" }
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    if (editing) EventEditSheet(view, e, onDismiss = { editing = false })
    if (adding) view.data.me?.let { me -> DocEditSheet(view, null, me, eventId = e.id, onDismiss = { adding = false }) }
    PageColumn(maxWidth = 900.dp) {
        PageBar {
            com.familydocs.app.ui.ds.FdButton("Edit", { editing = true }, Modifier.width(120.dp), com.familydocs.app.ui.ds.BtnKind.Ghost, icon = "edit")
            com.familydocs.app.ui.ds.FdButton("Add a ticket", { adding = true }, Modifier.width(170.dp), icon = "plus")
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            FdEventPic(e.art, e.photoFile, 120.dp, lift = true)
            Text(e.name, style = fdText(28f), modifier = Modifier.padding(top = 12.dp))
            Text("${Events.dates(e.startDay, e.endDay)} · ${Events.countdown(e.startDay, e.endDay)}", style = fdText(13f, color = c.muted), modifier = Modifier.padding(top = 4.dp))
            if (people.isNotEmpty()) Text(people.joinToString(", ") { it.name } + " going", style = fdText(12.5f, color = c.muted), modifier = Modifier.padding(top = 8.dp))
            Text(if (e.visibleIds.isEmpty()) "Visible to everyone in the group" else if (others.isEmpty()) "Only you can see this" else "Visible to you, ${others.joinToString(", ")}",
                style = fdText(11.5f, color = c.muted), modifier = Modifier.padding(top = 4.dp))
            organiser?.let { Text("Organised by $it", style = fdText(11.5f, color = c.muted), modifier = Modifier.padding(top = 2.dp)) }
            e.editedBy?.let { k -> view.data.members.firstOrNull { it.origin == k && !it.shared }?.name }?.let {
                Text("Changed by $it", style = fdText(11.5f, color = c.muted), modifier = Modifier.padding(top = 2.dp))
            }
        }
        FdSectionTitle("Documents", Modifier.padding(start = 0.dp)) { Text("${pinned.size + own.size}", style = fdText(11f, FontWeight.Medium, c.muted)) }
        if (pinned.isEmpty() && own.isEmpty()) FdEmpty("Tickets, bookings and pinned documents show up here.")
        else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))) {
            (pinned + own).forEachIndexed { i, d ->
                FdListItem(d.name, if (d.eventId != null) "For everyone going" else (view.member(d.memberId)?.name ?: "") + " · " + d.tail(), first = i == 0,
                    onClick = { app.go(Page.Doc(d.id)) }, leading = { FdDocIcon(d, 42.dp) })
            }
        }
        if (e.notes.isNotBlank()) {
            FdSectionTitle("Notes", Modifier.padding(start = 0.dp))
            Text(com.familydocs.app.ui.ds.NoteFormat.reading(e.notes).text, style = fdText(15f, color = c.fg),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(14.dp))
        }
    }
}

