package com.familydocs.app.ui.document

import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.Image
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familydocs.app.domain.maskValue
import com.familydocs.app.ocr.Images
import com.familydocs.app.ui.components.LocalContainer
import com.familydocs.app.ui.components.copySensitive
import com.familydocs.app.ui.ds.BtnKind
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdButton
import com.familydocs.app.ui.ds.FdBanner
import com.familydocs.app.ui.ds.FdConfirmSheet
import com.familydocs.app.ui.ds.FdDocIcon
import com.familydocs.app.ui.ds.FdEmpty
import com.familydocs.app.ui.ds.FdIcon
import com.familydocs.app.ui.ds.FdIconButton
import com.familydocs.app.ui.ds.FdSectionTitle
import com.familydocs.app.ui.ds.FdTopBar
import com.familydocs.app.ui.ds.LocalToast
import com.familydocs.app.ui.ds.fdText
import com.familydocs.app.ui.ds.fmtDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DocumentScreen(docId: String, onBack: () -> Unit, onEdit: (memberId: String) -> Unit, onExtend: (memberId: String) -> Unit = {},
                   onOpen: (String) -> Unit = {}) {
    val app = LocalContainer.current
    val c = Fd.c
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val doc by app.repo.document(docId).collectAsStateWithLifecycle(null)
    val d = doc ?: return
    val member by app.repo.member(d.memberId).collectAsStateWithLifecycle(null)
    // later reports added to this one (Extend), and the one this is part of
    val supplements by remember(d.id) { app.repo.supplements(d.id) }.collectAsStateWithLifecycle(emptyList())
    val parent by (d.parentId?.let { app.repo.document(it) } ?: kotlinx.coroutines.flow.flowOf(null)).collectAsStateWithLifecycle(null)
    var zoomed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Every page of every file (front, back, PDF pages…), in order.
    val pages by produceState<List<ImageBitmap>>(emptyList(), d.fileId, d.moreFiles) {
        value = withContext(Dispatchers.IO) {
            d.attachments.flatMap { a ->
                app.files.get(a.id)?.let { bytes ->
                    if (a.mime == "application/pdf") Images.renderPdf(context, bytes, pages = 6).map { it.asImageBitmap() }
                    else listOfNotNull(Images.decode(bytes, 2000)?.asImageBitmap())
                }.orEmpty()
            }
        }
    }
    // Only the details that have a value.
    val filled = d.fields.filter { it.value.isNotBlank() }
    // Shared by another member of the group: read-only here (they edit it on their phone).
    val groups by app.repo.groups.collectAsStateWithLifecycle(emptyList())
    val sharedBy = d.origin?.let { o -> groups.flatMap { it.peerList }.firstOrNull { it.key == o }?.name ?: "another member" }
    // ...unless you're an admin of the group: then you can change or remove it, and their phone takes that over.
    // ---- the document's files out of the vault: Downloads, or another app ----
    var exporting by remember { mutableStateOf(false) }
    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree ?: return@rememberLauncherForActivityResult
        scope.launch {
            exporting = true
            val n = runCatching { DocumentExport.toFolder(context, tree, app.files, d) }.getOrDefault(0)
            exporting = false
            toast.show(if (n > 0) "Saved ${if (n == 1) "the file" else "$n files"}" else "Couldn't save the file")
        }
    }
    fun download() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) scope.launch {
            exporting = true
            val n = runCatching { DocumentExport.toDownloads(context, app.files, d) }.getOrDefault(0)
            exporting = false
            toast.show(if (n > 0) "Saved to Downloads › Lararium${if (n > 1) " ($n files)" else ""}" else "Couldn't save the file")
        } else { app.appLock.expectExternalActivity(); pickFolder.launch(null) }
    }
    fun share() {
        scope.launch {
            exporting = true
            val chooser = runCatching { DocumentExport.shareIntent(context, app.files, d) }.getOrNull()
            exporting = false
            if (chooser == null) toast.show("Couldn't prepare the file") else { app.appLock.expectExternalActivity(); context.startActivity(chooser) }
        }
    }
    val admin = com.familydocs.app.ui.components.rememberIsAdmin(com.familydocs.app.ui.components.groupOfShared(d.id) ?: member?.groupId)
    val canEdit = sharedBy == null || admin

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        FdTopBar("", onBack) { if (canEdit) FdIconButton("edit", { onEdit(d.memberId) }, iconSize = 20.dp, description = "Edit document") }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            // `.dhead`
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FdDocIcon(d)
                Column {
                    Text(d.name, style = fdText(20f, FontWeight.SemiBold))
                    Text(listOfNotNull(member?.name, member?.let { if (it.shared) "Shared" else it.relation }).joinToString(" · "), style = fdText(12f, color = c.muted))
                }
            }
            parent?.let { p -> FdBanner("file", "Added to ${p.name}", Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                com.familydocs.app.ui.ds.FdTextButton("Open", { onOpen(p.id) })
            } }
            // `.docimg`
            if (pages.isNotEmpty()) {
                val pager = rememberPagerState { pages.size }
                Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp))
                    .background(c.surface2)) {
                    // Pages of different shapes: the box's height follows your finger from one page's shape to the next
                    // (worked out in the layout step from the swipe position, so it's smooth and costs no recomposition).
                    HorizontalPager(pager, Modifier.fillMaxWidth().layout { m, cons ->
                        val w = cons.maxWidth
                        fun hOf(i: Int): Float = pages.getOrNull(i)?.let { minOf(w * it.height.toFloat() / it.width.coerceAtLeast(1), w * 1.45f) } ?: w.toFloat()
                        val off = pager.currentPageOffsetFraction
                        val from = pager.currentPage
                        val to = (if (off > 0) from + 1 else from - 1).coerceIn(0, pages.lastIndex)
                        val h = (hOf(from) + (hOf(to) - hOf(from)) * kotlin.math.abs(off)).roundToInt().coerceAtLeast(1)
                        val p = m.measure(cons.copy(minHeight = h, maxHeight = h))
                        layout(p.width, h) { p.place(0, 0) }
                    }) { i ->
                        Image(pages[i], "${d.name}, page ${i + 1}", Modifier.fillMaxSize().clickable { zoomed = true }, contentScale = ContentScale.Fit)
                    }
                    Box(Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xB30A0E18)).padding(horizontal = 9.dp, vertical = 5.dp)) {
                        Text(if (pages.size > 1) "${pager.currentPage + 1} / ${pages.size} · swipe · tap to zoom" else "Tap to zoom", style = fdText(11f, color = Color.White))
                    }
                }
                if (pages.size > 1) PageDots(pages.size, pager.currentPage, Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            // Download / Share the file itself (every page, as it was added)
            if (d.attachments.isNotEmpty()) Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FdButton("Download", ::download, Modifier.weight(1f), BtnKind.Ghost, icon = "download", enabled = !exporting)
                FdButton("Share", ::share, Modifier.weight(1f), BtnKind.Ghost, icon = "share", enabled = !exporting)
            }
            FdSectionTitle("Details") { Text("${filled.size} field${if (filled.size == 1) "" else "s"}", style = fdText(11f, FontWeight.Medium, c.muted)) }
            if (filled.isEmpty()) FdEmpty("No details saved. Tap edit to add them.")
            else Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))) {
                filled.forEachIndexed { i, f ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                    Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(f.label, style = fdText(11f, color = c.muted))
                            // Shown in full: the app itself is behind the passcode / fingerprint.
                            Text(f.value, style = fdText(14.5f, FontWeight.SemiBold, c.fg))
                        }
                        FdIconButton("copy", { copySensitive(context, f.label, f.value); toast.show("Copied") }, size = 36.dp, iconSize = 18.dp, tint = c.muted, description = "Copy ${f.label}")
                    }
                }
            }
            // `.meta`
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FdIcon(if (sharedBy != null) "people" else if (d.readByOcr) "sparkle" else "edit", 14.dp, tint = c.muted)
                Text(if (sharedBy != null && admin) "Shared by $sharedBy · as a group admin you can change it; their phone takes it over"
                     else if (sharedBy != null) "Shared by $sharedBy · read-only here, they keep it up to date"
                     else "Added ${fmtDate(d.createdAt)} · ${if (d.readByOcr) "details read from the file" else "entered by hand"}", style = fdText(11.5f, color = c.muted))
            }
            // ---- supplementary docs: each one's pages as you scroll, newest first ----
            if (supplements.isNotEmpty()) {
                FdSectionTitle("Supplementary docs") { Text("${supplements.size}", style = fdText(11f, FontWeight.Medium, c.muted)) }
                supplements.forEach { s -> Supplement(s, onOpen = { onOpen(s.id) }) }
            }
            // put away (an old one, replaced by a renewed document)
            if (d.archived) FdBanner("archive", "Archived ${fmtDate(d.archivedAt ?: 0L)} · kept for reference, out of the wallet and expiry reminders",
                Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
                if (canEdit) com.familydocs.app.ui.ds.FdTextButton("Unarchive", { scope.launch { runCatching { app.repo.setArchived(d.id, false) }.onSuccess { toast.show("Back in the wallet") } } })
            }
            // `.actions`
            // `.actions`: a 2 × 2 grid of tiles (icon over the word, so every label fits on a phone)
            if (canEdit) {
                val tiles = buildList<ActionTileSpec> {
                    add(ActionTileSpec("edit", "Edit") { onEdit(d.memberId) })
                    if (d.eventId == null) add(if (d.archived) ActionTileSpec("archive", "Unarchive") {
                        scope.launch { runCatching { app.repo.setArchived(d.id, false) }.onSuccess { toast.show("Back in the wallet") } }
                    } else ActionTileSpec("archive", "Archive") {
                        scope.launch { runCatching { app.repo.setArchived(d.id, true) }.onSuccess { toast.show("Archived"); onBack() }.onFailure { toast.show(it.message ?: "Couldn't archive") } }
                    })
                    add(ActionTileSpec("trash", "Delete", danger = true) { confirmDelete = true })
                    // more papers that belong with this one: a later report, a renewal page, an invoice, a letter…
                    if (!d.supplement && d.eventId == null) add(ActionTileSpec("plus", "Add supplementary docs") { onExtend(d.memberId) })
                }
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    tiles.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { t -> ActionTile(t, Modifier.weight(1f)) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    // `.lightbox`
    if (zoomed && pages.isNotEmpty()) Dialog({ zoomed = false }, DialogProperties(usePlatformDefaultWidth = false)) {
        // All pages (and the gaps between them) zoom and move together, around your fingers; one finger scrolls.
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var viewport by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        var contentH by remember { mutableIntStateOf(0) }
        fun clamp(o: Offset, sc: Float): Offset {
            val w = viewport.width.toFloat(); val h = viewport.height.toFloat(); val ch = contentH * sc
            val x = o.x.coerceIn(minOf(0f, w - w * sc), 0f)
            val y = if (ch <= h) (h - ch) / 2 else o.y.coerceIn(h - ch, 0f)
            return Offset(x, y)
        }
        fun zoomAround(focus: Offset, newScale: Float, pan: Offset = Offset.Zero) {
            val ns = newScale.coerceIn(1f, 6f)
            offset = clamp((offset - focus) * (ns / scale) + focus + pan, ns)
            scale = ns
        }
        Box(Modifier.fillMaxSize().background(Color(0xFF05070C)).onSizeChanged { viewport = it; offset = clamp(offset, scale) }
            .pointerInput(Unit) { detectTransformGestures { centroid, pan, z, _ -> zoomAround(centroid, scale * z, pan) } }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { p -> zoomAround(p, if (scale > 1.2f) 1f else 2.5f) }) }
        ) {
            Column(
                Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)
                    .onSizeChanged { contentH = it.height; offset = clamp(offset, scale) }
                    .graphicsLayer { transformOrigin = TransformOrigin(0f, 0f); scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                    .padding(horizontal = 10.dp, vertical = 64.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                pages.forEach { p -> Image(p, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
            }
            Row(Modifier.statusBarsPadding().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FdIconButton("close", { zoomed = false }, tint = Color.White, description = "Close")
                Text(d.name, style = fdText(17f, FontWeight.SemiBold, Color.White))
            }
        }
    }

    if (confirmDelete) FdConfirmSheet(
        "Delete this document?", if (sharedBy != null) "It's removed from $sharedBy's vault too, the next time their phone syncs (they'll see a note that you removed it)."
            else "The file and its details are removed from this phone. Copies in older Drive backups are kept until they expire.", "Delete",
        onConfirm = { scope.launch {
            confirmDelete = false
            runCatching { app.repo.deleteDocument(d.id) }.onSuccess { toast.show("Deleted"); onBack() }.onFailure { toast.show(it.message ?: "Couldn't delete") }
        } },
        onDismiss = { confirmDelete = false },
    )
}

/** One report added later: its name and date, then its pages (tap to open it). */
@Composable
private fun Supplement(d: com.familydocs.app.data.DocumentEntity, onOpen: () -> Unit) {
    val app = LocalContainer.current
    val c = Fd.c
    val context = LocalContext.current
    val pages by produceState<List<ImageBitmap>>(emptyList(), d.fileId, d.moreFiles) {
        value = withContext(Dispatchers.IO) {
            d.attachments.take(3).flatMap { a ->
                app.files.get(a.id)?.let { bytes ->
                    if (a.mime == "application/pdf") Images.renderPdf(context, bytes, pages = 2).map { it.asImageBitmap() }
                    else listOfNotNull(Images.decode(bytes, 1400)?.asImageBitmap())
                }.orEmpty()
            }
        }
    }
    val date = d.fields.firstOrNull { com.familydocs.app.ocr.FieldKeys.canonical(it.label) == "date" || it.label.equals("Date", true) }?.value?.takeIf { it.isNotBlank() }
        ?: fmtDate(d.createdAt)
    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
        .border(1.dp, c.line, RoundedCornerShape(18.dp)).clickable(onClick = onOpen).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FdDocIcon(d, 36.dp)
            Column(Modifier.weight(1f)) {
                Text(d.name, style = fdText(14.5f, FontWeight.SemiBold))
                Text(listOfNotNull(date, d.attachments.size.takeIf { it > 0 }?.let { "$it page${if (it == 1) "" else "s"}" }).joinToString(" · "), style = fdText(12f, color = c.muted))
            }
            FdIcon("chev", 16.dp, tint = c.muted)
        }
        pages.forEach { p ->
            Image(p, d.name, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp)), contentScale = ContentScale.FillWidth)
        }
        d.fields.firstOrNull { it.label.equals("Notes", true) && it.value.isNotBlank() }?.let { Text(it.value, style = fdText(13f, color = c.fg)) }
    }
}

/** One of the document's action tiles. */
private class ActionTileSpec(val icon: String, val label: String, val danger: Boolean = false, val onClick: () -> Unit)

@Composable
private fun ActionTile(t: ActionTileSpec, modifier: Modifier) {
    val c = Fd.c
    val tint = if (t.danger) c.danger else c.fg
    Column(modifier.height(84.dp).clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))
        .clickable(onClick = t.onClick).padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
        FdIcon(t.icon, 20.dp, tint = tint)
        Text(t.label, style = fdText(13.5f, FontWeight.SemiBold, tint), maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
