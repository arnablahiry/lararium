@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.familydocs.app.ui.member

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.LocalOverscrollConfiguration
import kotlin.math.abs
import kotlin.math.sign
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familydocs.app.data.DocumentEntity
import com.familydocs.app.domain.Countries
import com.familydocs.app.security.VaultView
import com.familydocs.app.ui.components.LocalContainer
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdAvatar
import com.familydocs.app.ui.ds.FdDocIcon
import com.familydocs.app.ui.ds.FdDocTile
import com.familydocs.app.ui.ds.FdEmpty
import com.familydocs.app.ui.ds.FdFab
import com.familydocs.app.ui.ds.FdFlag
import com.familydocs.app.ui.ds.FdIcon
import com.familydocs.app.ui.ds.FdIconButton
import com.familydocs.app.ui.ds.FdLic
import com.familydocs.app.ui.ds.FdList
import com.familydocs.app.ui.ds.FdListItem
import com.familydocs.app.ui.ds.FdSectionTitle
import com.familydocs.app.ui.ds.FdSegmented
import com.familydocs.app.ui.ds.FdTopBar
import com.familydocs.app.ui.ds.FdWalletStack
import com.familydocs.app.ui.ds.fdText
import com.familydocs.app.ui.ds.tail
import com.familydocs.app.ui.ds.validUntil
import kotlinx.coroutines.launch

private const val PASSWORD_HUE = 0xFF8C6A2B

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemberScreen(memberId: String, onBack: () -> Unit, onEdit: () -> Unit, onPasswords: () -> Unit, onDocument: (String) -> Unit, onAddDocument: () -> Unit) {
    val app = LocalContainer.current
    val c = Fd.c
    val member by app.repo.member(memberId).collectAsStateWithLifecycle(null)
    val allDocs by app.repo.documents(memberId).collectAsStateWithLifecycle(emptyList())
    // put away (replaced by a renewed one): listed at the bottom, not in the wallet
    val docs = remember(allDocs) { allDocs.filter { !it.archived } }
    val archivedDocs = remember(allDocs) { allDocs.filter { it.archived }.sortedByDescending { it.archivedAt } }
    var showArchived by rememberSaveable(memberId) { mutableStateOf(false) }
    val passwords by app.repo.passwords(memberId).collectAsStateWithLifecycle(emptyList())
    val everyone by app.repo.members.collectAsStateWithLifecycle(emptyList())
    val account by app.repo.account.collectAsStateWithLifecycle(null)
    val settings by app.settings.state.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var openBounds by remember { mutableStateOf<Rect?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Back puts a lifted card back before leaving the screen.
    BackHandler(enabled = expanded != null) { expanded = null }
    var hidden by rememberSaveable(memberId) { mutableStateOf(listOf<String>()) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val m = member ?: return
    val order = Countries.decode(account?.countries)
    val codes = docs.map { it.issuer }.filter { it.isNotEmpty() }.distinct().sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }
    val shown = if (codes.size > 1) docs.filter { it.issuer !in hidden } else docs
    val open: (DocumentEntity) -> Unit = { expanded = null; onDocument(it.id) }

    Box(
        Modifier.fillMaxSize().background(c.bg)
            .onGloballyPositioned { origin = it.positionInRoot() }
            // While a card is lifted, a tap anywhere outside it only puts it back (the tap does nothing else).
            // Drags still scroll the list as usual.
            .pointerInput(expanded != null) {
                if (expanded == null) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val open = openBounds
                    if (open != null && open.contains(down.position + origin)) return@awaitEachGesture
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                        if (!ch.pressed) { ch.consume(); expanded = null; break }
                    }
                }
            }
            .statusBarsPadding(),
    ) {
        // Rubber band for the wallet: dragging past the top/bottom spreads/squishes the cards; letting go springs them back.
        // Another member's vault: only a group admin can add or change things in it (their phone takes the changes over).
        val adminHere = com.familydocs.app.ui.components.rememberIsAdmin(m.groupId)
        val canEdit = m.origin == null || adminHere
        val rubber = remember { RubberBand(scope) }
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        LazyColumn(Modifier.fillMaxSize().nestedScroll(rubber), state = list, contentPadding = PaddingValues(bottom = 120.dp)) {
            item { FdTopBar("", onBack) { if (canEdit) FdIconButton("edit", onEdit, iconSize = 20.dp, description = "Edit ${m.name}") } }
            // ---- hero ----
            item {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    FdAvatar(m.name, 104.dp, photoFile = m.photoFile, character = m.avatar, shared = m.shared, lift = true)
                    Text(m.name, style = fdText(28f, lineHeight = 32f), modifier = Modifier.padding(top = 12.dp))
                    Text("${if (m.shared) "Shared" else m.relation} · ${docs.size} document${if (docs.size == 1) "" else "s"}", style = fdText(13f, color = c.muted),
                        modifier = Modifier.padding(top = 4.dp))
                    if (m.shared) Row(
                        Modifier.padding(top = 8.dp).clip(RoundedCornerShape(99.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(99.dp))
                            .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row { everyone.filter { !it.shared }.forEachIndexed { i, p ->
                            FdAvatar(p.name, 26.dp, Modifier.offset(x = (-7 * i).dp).border(2.dp, c.surface, androidx.compose.foundation.shape.CircleShape),
                                photoFile = p.photoFile, character = p.avatar)
                        } }
                        Text("Everyone in the family can see and edit", style = fdText(12f, color = c.muted))
                    }
                }
            }
            // ---- vault header + view toggle ----
            item {
                FdSectionTitle("Vault") {
                    FdSegmented(listOf(Triple(VaultView.CARDS, "card", "Cards"), Triple(VaultView.LIST, "sliders", "List"), Triple(VaultView.ICONS, "grid", "Icons")),
                        settings.vaultView) { v -> expanded = null; app.settings.update { it.copy(vaultView = v) } }
                }
            }
            if (codes.size > 1) item {
                FlowRow(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    codes.forEach { code -> CountryPill(code, docs.count { it.issuer == code }, code !in hidden) {
                        expanded = null; hidden = if (code in hidden) hidden - code else hidden + code
                    } }
                }
            }
            // Logins are private: another member's never leave their phone, and nobody (admins included) sees or adds them here.
            val ownLogins = m.origin == null
            when (settings.vaultView) {
                VaultView.CARDS -> {
                    if (ownLogins) item { PasswordsCard(passwords.size, onPasswords) }
                    item {
                        FdWalletStack(
                            shown, if (m.shared) "Family" else m.name, expanded,
                            onExpand = { id, topPx ->
                                expanded = id
                                if (id != null)
                                // bring the lifted card near the top (the stack is item 4, or 5 when the country pills show)
                                scope.launch { list.animateScrollToItem((if (codes.size > 1) 5 else 4) - (if (ownLogins) 0 else 1), (topPx - 120).toInt().coerceAtLeast(0)) }
                            },
                            onOpen = open,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
                            owner = m,
                            onOpenBounds = { openBounds = it },
                            stretch = { rubber.value },
                            onReorder = { newShown ->
                                // Only the visible cards moved: hidden ones (other countries) keep their places.
                                val moved = newShown.iterator(); val shownIds = newShown.toSet()
                                val full = docs.map { d -> if (d.id in shownIds) moved.next() else d.id }
                                scope.launch { app.repo.reorderDocuments(full) }
                            },
                        )
                    }
                }
                VaultView.LIST -> item {
                    FdList(Modifier.padding(bottom = 10.dp)) {
                        if (ownLogins) FdListItem("Passwords", "${passwords.size} saved logins", first = true, onClick = onPasswords,
                            leading = { FdDocTile("Passwords", 42.dp, iconOverride = "key", hueOverride = PASSWORD_HUE) })
                        shown.forEachIndexed { i, d ->
                            FdListItem(d.name, d.tail(), first = !ownLogins && i == 0, onClick = { open(d) }, leading = { FdDocIcon(d, 42.dp) },
                                extraLines = listOfNotNull(d.validUntil()?.let { "Valid until $it" }),
                                trailing = { FdFlag(d.issuer, 20.dp); FdIcon("chev", 18.dp, tint = c.muted) }, chevron = false)
                        }
                    }
                }
                VaultView.ICONS -> item {
                    val tiles: List<DocumentEntity?> = (if (ownLogins) listOf<DocumentEntity?>(null) else emptyList()) + shown
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        tiles.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { d -> Tile(d, passwords.size, Modifier.weight(1f)) { if (d == null) onPasswords() else open(d) } }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
            if (shown.isEmpty()) item {
                FdEmpty(if (docs.isEmpty()) "No documents yet. Tap Add document to scan or upload ${m.name}'s first card." else "No documents for the selected countries.",
                    Modifier.padding(top = 12.dp))
            }
            // ---- archived: old passports, licences… kept for reference ----
            if (archivedDocs.isNotEmpty()) item {
                Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp).clip(RoundedCornerShape(99.dp)).clickable { showArchived = !showArchived }
                    .padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FdIcon("archive", 16.dp, tint = c.muted)
                    Text("Archived · ${archivedDocs.size}", style = fdText(12.5f, FontWeight.SemiBold, c.muted))
                    FdIcon("chev", 14.dp, tint = c.muted, modifier = Modifier.graphicsLayer { rotationZ = if (showArchived) 90f else 0f })
                }
            }
            if (archivedDocs.isNotEmpty() && showArchived) item {
                FdList(Modifier.graphicsLayer { alpha = .75f }) {
                    archivedDocs.forEachIndexed { i, d ->
                        FdListItem(d.name, "Archived ${java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK).format(java.util.Date(d.archivedAt ?: 0L))}" +
                            (d.validUntil()?.let { " · valid until $it" } ?: ""), first = i == 0, onClick = { open(d) }, leading = { FdDocIcon(d, 42.dp) })
                    }
                }
            }
        }
        }
        // Another member of the group: read-only here (they add documents on their phone), unless you're a group admin.
        if (canEdit) FdFab("Add document", "plus", onAddDocument, Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 18.dp, bottom = 18.dp))
    }
}

/** `.pwcard` */
@Composable
private fun PasswordsCard(count: Int, onClick: () -> Unit) {
    val c = Fd.c
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FdLic("key")
        Column(Modifier.weight(1f)) {
            Text("Passwords", style = fdText(14f, FontWeight.Bold))
            Text("$count saved login${if (count == 1) "" else "s"}", style = fdText(12f, color = c.muted))
        }
        FdIcon("chev", 18.dp, tint = c.muted)
    }
}

/** `.cpill`: country filter; off = half transparent with a grey flag. */
@Composable
private fun CountryPill(code: String, count: Int, on: Boolean, onClick: () -> Unit) {
    val c = Fd.c
    val a by animateFloatAsState(if (on) 1f else .5f, tween(200), label = "pill")
    val border by animateColorAsState(if (on) c.primary else c.line, tween(200), label = "pillB")
    Row(
        Modifier.alpha(a).clip(RoundedCornerShape(99.dp)).background(if (on) c.mix(c.primary, .08f, c.surface) else c.surface)
            .border(1.5.dp, border, RoundedCornerShape(99.dp)).clickable(onClick = onClick).padding(start = 11.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        FdFlag(code, 20.dp, greyscale = !on)
        Text(Countries.short(code), style = fdText(14f, FontWeight.SemiBold, spacing = .02f))
        Box(Modifier.defaultMinSize(minWidth = 24.dp).clip(RoundedCornerShape(99.dp)).background(c.mix(c.fg, .07f, c.surface)).padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center) { Text("$count", style = fdText(12f, FontWeight.Medium, c.muted, lineHeight = 18f)) }
    }
}

/** `.tile` (Icons view) */
@Composable
private fun Tile(d: DocumentEntity?, pwCount: Int, modifier: Modifier, onClick: () -> Unit) {
    val c = Fd.c
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(20.dp))
                .clickable(onClick = onClick).padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (d == null) FdDocTile("Passwords", iconOverride = "key", hueOverride = PASSWORD_HUE) else FdDocIcon(d)
            Text(d?.name ?: "Passwords", style = fdText(12f, FontWeight.SemiBold, lineHeight = 15f), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(d?.tail() ?: "$pwCount saved", style = fdText(10f, color = c.muted), modifier = Modifier.offset(y = (-4).dp))
        }
        if (d != null && d.issuer.isNotEmpty()) Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { FdFlag(d.issuer, 16.dp) }
    }
}

/**
 * Overscroll for the wallet: what the list can't scroll (past the top or the bottom) is collected with growing
 * resistance into [value] (px: + pulled down at the top, − pushed up at the bottom); on release it springs back to 0
 * with a little bounce. Scrolling back the other way first undoes the stretch.
 */
@Stable
private class RubberBand(private val scope: kotlinx.coroutines.CoroutineScope) : NestedScrollConnection {
    var value by mutableFloatStateOf(0f); private set
    private var settle: kotlinx.coroutines.Job? = null

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val o = value
        if (source != NestedScrollSource.UserInput || o == 0f || sign(available.y) == sign(o)) return Offset.Zero
        settle?.cancel()
        val next = if (o > 0f) maxOf(0f, o + available.y) else minOf(0f, o + available.y)
        value = next
        return Offset(0f, next - o)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
        settle?.cancel()
        val resist = 1f / (1f + abs(value) / 140f)
        value = (value + available.y * .3f * resist).coerceIn(-180f, 260f)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (value == 0f) return Velocity.Zero
        val from = value
        settle = scope.launch {
            animate(from, 0f, animationSpec = spring(dampingRatio = .75f, stiffness = 320f)) { v, _ -> value = v }
        }
        return available // the release springs the cards back instead of flinging the list
    }
}
