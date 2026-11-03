package com.familydocs.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.familydocs.app.data.Attachment
import com.familydocs.app.data.DocumentEntity
import com.familydocs.app.ui.components.decodeScaled
import com.familydocs.app.ui.ds.BtnKind
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdBanner
import com.familydocs.app.ui.ds.FdButton
import com.familydocs.app.ui.ds.FdDocIcon
import com.familydocs.app.ui.ds.FdEmpty
import com.familydocs.app.ui.ds.FdIcon
import com.familydocs.app.ui.ds.FdIconButton
import com.familydocs.app.ui.ds.FdProgress
import com.familydocs.app.ui.ds.FdSectionTitle
import com.familydocs.app.ui.ds.FdWalletCard
import com.familydocs.app.ui.ds.LocalToast
import com.familydocs.app.ui.ds.fdText
import com.familydocs.app.ui.ds.fmtDate
import com.familydocs.desktop.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One page to show: a photo, or a page of a PDF rendered to an image. */
private fun pagesOf(a: Attachment, bytes: ByteArray): List<ImageBitmap> =
    if (a.mime == "application/pdf") runCatching {
        org.apache.pdfbox.Loader.loadPDF(bytes).use { pdf ->
            val r = org.apache.pdfbox.rendering.PDFRenderer(pdf)
            (0 until minOf(pdf.numberOfPages, 12)).map { r.renderImageWithDPI(it, 130f).toComposeImageBitmap() }
        }
    }.getOrDefault(emptyList())
    else listOfNotNull(decodeScaled(bytes, 2200))

private fun extension(mime: String) = when (mime) { "application/pdf" -> "pdf"; "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }

/** A document: its card, its scans (every page), and its details with copy buttons. Save a copy or open it in Preview. */
@Composable
fun DocumentPage(view: GroupView, id: String) {
    val c = Fd.c
    val toast = LocalToast.current
    val clipboard = LocalClipboardManager.current
    val d = view.doc(id) ?: run { FdEmpty("This document isn't here any more."); return }
    val member = view.member(d.memberId)
    val sharedBy = d.origin?.let { o -> view.data.members.firstOrNull { it.origin == o && !it.shared }?.name ?: "another member" }
    val pages by produceState<List<ImageBitmap>?>(null, d.id, d.fileId, d.moreFiles) {
        value = withContext(Dispatchers.IO) { d.attachments.flatMap { a -> Files.get(a.id)?.let { pagesOf(a, it) }.orEmpty() } }
    }
    var zoom by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf(false) }
    var extending by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val supplements = view.supplements(d.id)
    val parent = d.parentId?.let { view.doc(it) }
    val app = com.familydocs.desktop.rememberApp()
    val editable = view.canEdit(d)
    // (the notes have their own section)
    val filled = d.fields.filter { it.value.isNotBlank() && !isNotes(it.label) }
    val scroll = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 1100.dp).fillMaxWidth()) {
                PageBar(d.name) {
                    if (d.attachments.isNotEmpty()) {
                        FdIconButton("eye", { open(d) }, size = 38.dp, iconSize = 19.dp, description = com.familydocs.desktop.Platform.openLabel)
                        FdIconButton("download", { save(d)?.let { toast.show(it) } }, size = 38.dp, iconSize = 19.dp, description = "Save a copy…")
                    }
                }
                // the document itself when there's a scan or PDF; its card only when there isn't (event tickets: never a card)
                val card = d.eventId == null && (d.attachments.isEmpty() || pages?.isEmpty() == true)
                val left = card || d.attachments.isNotEmpty()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    if (left) Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (card) Box(Modifier.fillMaxWidth().aspectRatio(1.586f).shared("card-${d.id}")) { FdWalletCard(d, member?.name ?: "", open = false, Modifier.fillMaxSize()) }
                        when {
                            d.attachments.isEmpty() -> {}
                            pages == null -> Column(Modifier.fillMaxWidth().aspectRatio(1.3f).shared("card-${d.id}").clip(RoundedCornerShape(18.dp)).background(c.surface2).padding(24.dp),
                                verticalArrangement = Arrangement.Center) {
                                Text("Opening the file…", style = fdText(12.5f, color = c.muted)); FdProgress(Modifier.padding(top = 10.dp))
                            }
                            pages!!.isEmpty() -> FdEmpty("The file couldn't be opened here. Try Save a copy.")
                            else -> pages!!.forEachIndexed { i, p ->
                                Box(Modifier.fillMaxWidth().then(if (i == 0) Modifier.shared("card-${d.id}") else Modifier.appear(i)).clip(RoundedCornerShape(18.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp)).background(c.surface2)
                                    .clickable { zoom = i }) {
                                    Image(p, "${d.name}, page ${i + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                                    if (pages!!.size > 1) Box(Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xB30A0E18))
                                        .padding(horizontal = 9.dp, vertical = 5.dp)) { Text("${i + 1} / ${pages!!.size}", style = fdText(11f, color = Color.White)) }
                                }
                            }
                        }
                    }
                    // ---- details ----
                    Column(if (left) Modifier.weight(1f) else Modifier.weight(1f).widthIn(max = 680.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            FdDocIcon(d)
                            Column {
                                Text(d.name, style = fdText(20f, FontWeight.SemiBold))
                                Text(listOfNotNull(member?.name, member?.let { if (it.shared) "Shared" else if (it.isSelf) "You" else it.relation }).joinToString(" · "), style = fdText(12f, color = c.muted))
                            }
                        }
                        if (d.archived) FdBanner("archive", "Archived ${fmtDate(d.archivedAt ?: 0L)} · kept for reference", Modifier.padding(top = 14.dp))
                        parent?.let { p -> FdBanner("file", "Added to ${p.name}", Modifier.padding(top = 14.dp)) {
                            com.familydocs.app.ui.ds.FdTextButton("Open", { app.go(com.familydocs.desktop.Page.Doc(p.id)) })
                        } }
                        FdSectionTitle("Details", Modifier.padding(start = 0.dp)) { Text("${filled.size} field${if (filled.size == 1) "" else "s"}", style = fdText(11f, FontWeight.Medium, c.muted)) }
                        if (filled.isEmpty()) FdEmpty("No details saved.")
                        else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))) {
                            filled.forEachIndexed { i, f ->
                                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                                Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(f.label, style = fdText(11f, color = c.muted))
                                        androidx.compose.foundation.text.selection.SelectionContainer { Text(f.value, style = fdText(14.5f, FontWeight.SemiBold, c.fg)) }
                                    }
                                    FdIconButton("copy", { clipboard.setText(AnnotatedString(f.value)); toast.show("Copied ${f.label}") }, size = 34.dp, iconSize = 17.dp,
                                        tint = c.muted, description = "Copy ${f.label}")
                                }
                            }
                        }
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FdIcon(if (sharedBy != null) "people" else if (d.readByOcr) "sparkle" else "edit", 14.dp, tint = c.muted)
                            Text(if (sharedBy != null) "Shared by $sharedBy" else "Added ${fmtDate(d.createdAt)} · ${if (d.readByOcr) "details read from the file" else "entered by hand"}",
                                style = fdText(11.5f, color = c.muted))
                        }
                        // ---- notes: on every document, written right here with formatting, saved a moment after you stop ----
                        val docNotes = d.fields.firstOrNull { isNotes(it.label) }?.value.orEmpty()
                        if (editable || docNotes.isNotBlank()) {
                            var note by remember(d.id) { mutableStateOf(docNotes) }
                            var seen by remember(d.id) { mutableStateOf(docNotes) }
                            if (docNotes != seen) { if (note.trim() == seen.trim()) note = docNotes; seen = docNotes }
                            fun saveNotes() {
                                val n = note.trim()
                                if (n == docNotes.trim()) return
                                val rest = d.fields.filterNot { isNotes(it.label) }
                                val all = rest + listOfNotNull(n.takeIf { it.isNotEmpty() }?.let { com.familydocs.app.domain.DocField("Notes", it, sensitive = false) })
                                app.edit({ com.familydocs.app.domain.DeskOps.op(com.familydocs.app.domain.DeskOps.DOC)
                                    .put("doc", com.familydocs.app.domain.DeskOps.docJson(d.copy(fieldsJson = com.familydocs.app.data.encodeFields(all)))) }) { err -> err?.let { toast.show(it) } }
                            }
                            FdSectionTitle("Notes", Modifier.padding(start = 0.dp)) {
                                if (note.trim() != docNotes.trim()) Text("Saving soon…", style = fdText(11f, FontWeight.Medium, c.muted))
                            }
                            if (editable) {
                                androidx.compose.runtime.LaunchedEffect(note) { kotlinx.coroutines.delay(2000); saveNotes() }
                                com.familydocs.app.ui.ds.FdNotesField(note, { note = it }, placeholder = "Anything worth remembering about it", onDone = { saveNotes() })
                            } else Text(com.familydocs.app.ui.ds.NoteFormat.reading(docNotes).text, style = fdText(14.5f, color = c.fg),
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(12.dp))
                        }
                        // the document's actions as a 2 x 2 grid of tiles
                        if (editable) {
                            val tiles = buildList<Tile> {
                                add(Tile("edit", "Edit") { editing = true })
                                if (d.eventId == null) add(Tile("archive", if (d.archived) "Unarchive" else "Archive") { app.setArchived(toast, d, !d.archived) })
                                add(Tile("trash", "Delete", danger = true) { deleting = true })
                                // more papers that belong with this one: a later report, a renewal page, an invoice, a letter...
                                if (d.parentId == null && d.eventId == null) add(Tile("plus", "Add supplementary docs") { extending = true })
                            }
                            Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                tiles.chunked(2).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        row.forEach { t -> ActionTile(t, Modifier.weight(1f), enabled = !app.saving) }
                                        if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        if (d.attachments.isNotEmpty()) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FdButton(com.familydocs.desktop.Platform.openLabel, { open(d) }, Modifier.weight(1f), BtnKind.Ghost, icon = "eye")
                            FdButton("Save a copy…", { save(d)?.let { toast.show(it) } }, Modifier.weight(1f), BtnKind.Ghost, icon = "download")
                        }
                    }
                }
                // ---- supplementary docs: each one's pages as you scroll, newest first ----
                if (supplements.isNotEmpty()) {
                    FdSectionTitle("Supplementary docs", Modifier.padding(start = 0.dp, top = 18.dp)) { Text("${supplements.size}", style = fdText(11f, FontWeight.Medium, c.muted)) }
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) { supplements.forEach { s -> Supplement(s) { app.go(com.familydocs.desktop.Page.Doc(s.id)) } } }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp))
    }

    if (editing) DocEditSheet(view, d, d.memberId, d.eventId, onDismiss = { editing = false })
    if (extending) DocEditSheet(view, null, d.memberId, parentId = d.id, onDismiss = { extending = false })
    if (deleting) com.familydocs.app.ui.ds.FdConfirmSheet("Delete ${d.name}?",
        "It's removed from this computer and from your phone, with its files${if (supplements.isNotEmpty()) " and its ${supplements.size} supplementary doc${if (supplements.size == 1) "" else "s"}" else ""}. This can't be undone.",
        "Delete", onConfirm = {
            deleting = false
            app.save(toast, "Deleted", build = { com.familydocs.app.domain.DeskOps.op(com.familydocs.app.domain.DeskOps.DEL_DOC).put("doc", d.id) }, close = { app.back() })
        }, onDismiss = { deleting = false })

    // the page, as big as the window allows
    zoom?.let { i ->
        val list = pages.orEmpty()
        Dialog(onDismissRequest = { zoom = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val s = rememberScrollState()
            Box(Modifier.fillMaxSize().background(Color(0xF205070C)).clickable { zoom = null }) {
                Column(Modifier.fillMaxSize().verticalScroll(s).padding(horizontal = 60.dp, vertical = 50.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    list.drop(i).plus(list.take(i)).forEach { p -> Image(p, null, Modifier.widthIn(max = 1400.dp).fillMaxWidth(), contentScale = ContentScale.FillWidth) }
                }
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FdIconButton("close", { zoom = null }, tint = Color.White, description = "Close")
                    Text(d.name, style = fdText(16f, FontWeight.SemiBold, Color.White))
                }
            }
        }
    }
}

/** Every file of the document, as a temporary copy that Preview (or the PDF viewer) opens. Removed when Lararium quits. */
private fun open(d: DocumentEntity) {
    Thread {
        d.attachments.forEachIndexed { i, a ->
            val bytes = Files.get(a.id) ?: return@forEachIndexed
            val f = java.io.File.createTempFile("${d.name.replace(Regex("[^A-Za-z0-9 _-]"), "")}${if (d.attachments.size > 1) " - ${i + 1}" else ""}-", "." + extension(a.mime))
            f.deleteOnExit(); f.writeBytes(bytes)
            runCatching { java.awt.Desktop.getDesktop().open(f) }
        }
    }.start()
}

/** Saves the files where you choose (a Save dialog for each file). Returns what to say. */
private fun save(d: DocumentEntity): String? {
    var n = 0
    d.attachments.forEachIndexed { i, a ->
        val dialog = java.awt.FileDialog(com.familydocs.desktop.MainWindow.frame, "Save ${d.name}", java.awt.FileDialog.SAVE)
        dialog.file = "${d.name}${if (d.attachments.size > 1) " - ${i + 1}" else ""}.${extension(a.mime)}"
        dialog.isVisible = true
        val dir = dialog.directory ?: return@forEachIndexed; val name = dialog.file ?: return@forEachIndexed
        val bytes = Files.get(a.id) ?: return "Couldn't fetch the file from Drive"
        java.io.File(dir, name).writeBytes(bytes); n++
    }
    return if (n == 0) null else "Saved ${if (n == 1) "the file" else "$n files"}"
}

/** One report added later: its name, date and notes, then its pages; click to open it on its own. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Supplement(d: DocumentEntity, onOpen: () -> Unit) {
    val c = Fd.c
    val pages by produceState<List<ImageBitmap>?>(null, d.id, d.fileId, d.moreFiles) {
        value = withContext(Dispatchers.IO) { d.attachments.take(4).flatMap { a -> Files.get(a.id)?.let { pagesOf(a, it).take(3) }.orEmpty() } }
    }
    val date = d.fields.firstOrNull { it.label.equals("Date", true) }?.value?.takeIf { it.isNotBlank() } ?: fmtDate(d.createdAt)
    val notes = d.fields.firstOrNull { isNotes(it.label) }?.value?.takeIf { it.isNotBlank() }
    Column(Modifier.fillMaxWidth().appear(0, "supp-${d.id}").clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))
        .clickable(onClick = onOpen).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FdDocIcon(d, 40.dp)
            Column(Modifier.weight(1f)) {
                Text(d.name, style = fdText(15f, FontWeight.SemiBold))
                Text(listOfNotNull(date, d.attachments.size.takeIf { it > 0 }?.let { "$it file${if (it == 1) "" else "s"}" }).joinToString(" · "), style = fdText(12f, color = c.muted))
            }
            FdIcon("chev", 16.dp, tint = c.muted)
        }
        notes?.let { Text(com.familydocs.app.ui.ds.NoteFormat.reading(it).text, style = fdText(13.5f, color = c.fg)) }
        when {
            d.attachments.isEmpty() -> {}
            pages == null -> FdProgress()
            else -> androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                pages!!.forEach { p ->
                    Image(p, d.name, Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp)), contentScale = ContentScale.FillWidth)
                }
            }
        }
    }
}

/** One of the document's action tiles. */
private class Tile(val icon: String, val label: String, val danger: Boolean = false, val onClick: () -> Unit)

@Composable
private fun ActionTile(t: Tile, modifier: Modifier, enabled: Boolean) {
    val c = Fd.c
    val tint = if (t.danger) c.danger else c.fg
    Row(modifier.height(58.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp))
        .clickable(enabled = enabled, onClick = t.onClick).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        FdIcon(t.icon, 18.dp, tint = tint)
        Text(t.label, style = fdText(14f, FontWeight.SemiBold, tint), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** The "Notes" detail: shown as its own section, not among the details. */
internal fun isNotes(label: String) = label.trim().equals("Notes", ignoreCase = true) || label.trim().equals("Note", ignoreCase = true)
