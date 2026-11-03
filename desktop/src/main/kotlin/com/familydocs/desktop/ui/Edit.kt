package com.familydocs.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familydocs.app.data.DocumentEntity
import com.familydocs.app.data.EventEntity
import com.familydocs.app.data.MemberEntity
import com.familydocs.app.data.encodeFields
import com.familydocs.app.domain.Countries
import com.familydocs.app.domain.DeskOps
import com.familydocs.app.domain.DocField
import com.familydocs.app.domain.DocTypes
import com.familydocs.app.domain.Events
import com.familydocs.app.ui.components.decodeScaled
import com.familydocs.app.ui.ds.BtnKind
import com.familydocs.app.ui.ds.CHARACTERS
import com.familydocs.app.ui.ds.EVENT_ARTS
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdAvatar
import com.familydocs.app.ui.ds.FdButton
import com.familydocs.app.ui.ds.FdChip
import com.familydocs.app.ui.ds.FdChips
import com.familydocs.app.ui.ds.FdConfirmSheet
import com.familydocs.app.ui.ds.FdEventPic
import com.familydocs.app.ui.ds.FdField
import com.familydocs.app.ui.ds.FdFlag
import com.familydocs.app.ui.ds.FdIcon
import com.familydocs.app.ui.ds.FdIconButton
import com.familydocs.app.ui.ds.FdInput
import com.familydocs.app.ui.ds.FdSheet
import com.familydocs.app.ui.ds.FdSwitch
import com.familydocs.app.ui.ds.LocalToast
import com.familydocs.app.ui.ds.SheetTitle
import com.familydocs.app.ui.ds.fdPainter
import com.familydocs.app.ui.ds.fdText
import com.familydocs.desktop.AppModel
import com.familydocs.desktop.Desk
import com.familydocs.desktop.rememberApp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

// ---------------- files from this computer ----------------

/** A file picked to go into the vault: its bytes and type. */
class Picked(val bytes: ByteArray, val mime: String, val name: String)

private fun mimeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; else -> null
}

/**
 * The system's Open dialog: scans and PDFs ([images] only: pictures). Several at once when [many]. Anything that can't
 * be used is said through [onProblem] (never silently dropped).
 */
fun pickFiles(title: String, images: Boolean = false, many: Boolean = false, onProblem: (String) -> Unit = {}): List<Picked> {
    val d = java.awt.FileDialog(com.familydocs.desktop.MainWindow.frame, title, java.awt.FileDialog.LOAD)
    d.isMultipleMode = many
    d.isVisible = true
    val problems = mutableListOf<String>()
    val out = d.files.orEmpty().mapNotNull { f ->
        val mime = mimeOf(f.name)
        when {
            mime == null -> { problems += if (f.name.lowercase().endsWith(".heic")) "${f.name}: HEIC photos can't be read here yet (export it as JPEG)" else "${f.name}: not a photo or PDF"; null }
            images && !mime.startsWith("image/") -> { problems += "${f.name}: not a photo"; null }
            else -> runCatching { Picked(f.readBytes(), mime, f.name) }
                .onFailure { problems += "${f.name}: couldn't be read (${it.message ?: "no access"})" }.getOrNull()
        }
    }
    if (problems.isNotEmpty()) onProblem(problems.joinToString("\n"))
    return out
}

/** What a picked file looks like: the photo, or a PDF's first page. */
fun previewOf(p: Picked, maxPx: Int = 500): ImageBitmap? = runCatching {
    if (p.mime == "application/pdf") org.apache.pdfbox.Loader.loadPDF(p.bytes).use { pdf ->
        org.apache.pdfbox.rendering.PDFRenderer(pdf).renderImageWithDPI(0, 60f).toComposeImageBitmap()
    } else decodeScaled(p.bytes, maxPx)
}.getOrNull()

/** A round picture (people, groups, events): the middle square, [out] pixels, as JPEG, like the phone's cropper makes. */
fun squarePhoto(bytes: ByteArray, out: Int = 700): ByteArray? = runCatching {
    val img = org.jetbrains.skia.Image.makeFromEncoded(bytes)
    val side = minOf(img.width, img.height).toFloat()
    val size = minOf(out, side.toInt()).coerceAtLeast(1)
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(size, size)
    surface.canvas.drawImageRect(img, org.jetbrains.skia.Rect.makeXYWH((img.width - side) / 2f, (img.height - side) / 2f, side, side),
        org.jetbrains.skia.Rect.makeWH(size.toFloat(), size.toFloat()))
    surface.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 88)!!.bytes
}.getOrNull()

/** Picks a picture and makes it round-ready; null when cancelled or unreadable. */
fun pickPhoto(title: String, onProblem: (String) -> Unit = {}): ByteArray? =
    pickFiles(title, images = true, onProblem = onProblem).firstOrNull()?.let { squarePhoto(it.bytes) ?: run { onProblem("${it.name}: couldn't be opened as a photo"); null } }

/** A picture chosen here, shown before it's saved. */
class NewPhoto(val bytes: ByteArray) { val image: ImageBitmap? = decodeScaled(bytes, 400) }

// ---------------- the frame every editor shares ----------------

/** A sheet with a title, a scrolling form and Save / Cancel; Delete too when [onDelete]. */
@Composable
fun EditSheet(title: String, text: String?, onDismiss: () -> Unit, onSave: () -> Unit, saveLabel: String = "Save", canSave: Boolean = true,
              onDelete: (() -> Unit)? = null, deleteLabel: String = "Delete", missing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val app = rememberApp()
    FdSheet(onDismiss) {
        SheetTitle(title, text)
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        Spacer(Modifier.height(18.dp))
        if (!canSave && missing != null) Text(missing, style = fdText(12.5f, FontWeight.Medium, Fd.c.danger), modifier = Modifier.padding(bottom = 8.dp))
        FdButton(if (app.saving) "Saving…" else saveLabel, onSave, enabled = canSave && !app.saving)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FdButton("Cancel", onDismiss, Modifier.weight(1f), kind = BtnKind.Ghost)
            onDelete?.let { FdButton(deleteLabel, it, Modifier.weight(1f), kind = BtnKind.Ghost, icon = "trash", contentColor = Fd.c.danger) }
        }
    }
}

/** What a saved change says, and closing the editor when it went through. */
fun AppModel.save(toast: com.familydocs.app.ui.ds.FdToastState, said: String, build: suspend () -> JSONObject, close: () -> Unit) =
    edit(build) { err -> toast.show(err ?: said); if (err == null) close() }

// ---------------- documents ----------------

/**
 * Adds a document ([doc] null) to [memberId]'s vault (or to event [eventId]), or changes one: its name, country, details
 * and files. Saved here at once and taken over by the phone.
 */
@Composable
fun DocEditSheet(view: GroupView, doc: DocumentEntity?, memberId: String, eventId: String? = null, onDismiss: () -> Unit, onSaved: (String) -> Unit = {},
                 /** Extending that document: this one is a later report added to it (same kind, today's date, notes). */
                 parentId: String? = null) {
    val parent = parentId?.let { view.doc(it) }
    val app = rememberApp()
    val c = Fd.c
    val toast = LocalToast.current
    // a supplementary doc gets its own name (a report, an invoice, a renewal page...)
    var name by remember { mutableStateOf(doc?.name ?: "") }
    val fields = remember {
        mutableStateListOf<DocField>().apply {
            when {
                doc != null -> addAll(doc.fields)
                parent != null -> { add(DocField("Date", LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")))); add(DocField("Notes", "")) }
                else -> DocTypes.template("").forEach { add(DocField(it, "")) }
            }
        }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // read from the scan, as on the phone
    var rawText by remember { mutableStateOf(doc?.rawText ?: "") }
    var readByOcr by remember { mutableStateOf(doc?.readByOcr ?: false) }
    var reading by remember { mutableStateOf<String?>(null) }
    var busyReading by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(doc != null || parent != null) }
    var nameFromFile by remember { mutableStateOf(false) }
    var country by remember { mutableStateOf(doc?.country ?: doc?.issuer?.takeIf { it.isNotEmpty() } ?: parent?.country) }
    var files by remember { mutableStateOf<List<Picked>?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var otherCountry by remember { mutableStateOf(false) }
    val newId = remember { doc?.id ?: UUID.randomUUID().toString() }
    var countryQuery by remember { mutableStateOf("") }
    // a new document starts from its type's usual fields (until you type in them)
    fun template(n: String) {
        if (fields.any { it.value.isNotBlank() }) return
        fields.clear(); DocTypes.template(n).forEach { fields.add(DocField(it, "")) }
    }

    /** The file just picked: its text read here, turned into details by the phone's own reader, and merged in. */
    fun readFile(f: Picked) {
        busyReading = true; reading = "Reading the document…"
        scope.launch {
            val text = com.familydocs.desktop.TextRead.read(f.bytes, f.mime)
            busyReading = false
            if (text.isBlank()) { reading = "No text could be read from it: fill in the details yourself"; return@launch }
            val r = com.familydocs.app.ocr.DocumentParser.parse(text, name.takeIf { it.isNotBlank() && !nameFromFile })
            r.suggestedName?.takeIf { name.isBlank() || nameFromFile }?.let { name = it; nameFromFile = false }
            val merged = com.familydocs.app.ocr.FieldMerge.merge(fields.toList(), DocTypes.template(name), r.fields, ensureTemplate = !touched)
            fields.clear(); fields.addAll(merged)
            rawText = text; readByOcr = true
            if (country == null) country = Countries.infer(name, merged, text).takeIf { it.isNotEmpty() }
            val n = merged.count { it.auto && it.value.isNotBlank() }
            reading = if (n == 0) "Read the document, but found no details to fill in" else "Filled in $n detail${if (n == 1) "" else "s"} from the scan · check them"
        }
    }

    EditSheet(if (doc != null) "Edit ${doc.name}" else if (parent != null) "Supplementary doc for ${parent.name}" else if (eventId != null) "Add to the event" else "Add a document",
        if (parent != null) "More papers that belong with it: a later report, a renewal page, an invoice, a letter. It shows under ${parent.name}, newest first."
        else if (doc == null) "Pick the scan or PDF and fill in what matters. Your phone takes it over (and backs it up) within seconds while Lararium is open there." else null,
        onDismiss, canSave = name.isNotBlank() && (doc != null || files?.isNotEmpty() == true || fields.any { it.value.isNotBlank() }) && !busyReading,
        missing = when {
            busyReading -> "Reading the document…"
            name.isBlank() -> "Give it a name (or pick one of the suggestions) to save it"
            else -> "Choose the scan or PDF, or fill in a detail, to save it"
        },
        onDelete = doc?.let { { deleting = true } },
        onSave = {
            app.save(toast, if (doc == null) "Added" else "Saved", build = {
                val uploaded = files?.map { f -> JSONObject().put("f", Desk.upload(f.bytes)).put("mime", f.mime) }
                val now = System.currentTimeMillis()
                val row = (doc ?: DocumentEntity(id = newId, memberId = memberId, name = name, fileId = null, fileMime = null,
                    eventId = eventId, createdAt = now, parentId = parentId)).copy(
                    name = name.trim(), fieldsJson = encodeFields(fields.filter { it.label.isNotBlank() }.map { it.copy(label = it.label.trim(), value = it.value.trim()) }),
                    country = country, rawText = rawText, readByOcr = readByOcr,
                )
                DeskOps.op(DeskOps.DOC).put("doc", DeskOps.docJson(row)).apply { uploaded?.let { put("files", JSONArray(it)) } }
            }, close = { onDismiss(); onSaved(newId) })
        }) {
        FdField("Name") { FdInput(name, { name = it; nameFromFile = false; if (doc == null) template(it) }, placeholder = "Passport, Visa, Flight ticket…") }
        if (doc == null || name.isBlank()) FdChips {
            (if (eventId != null) DocTypes.EVENT_SUGGESTIONS else if (parent != null || doc?.parentId != null) DocTypes.SUPPLEMENT_SUGGESTIONS else DocTypes.SUGGESTIONS).forEach { s -> FdChip(s, name == s, { name = s; template(s) }) }
        }
        // ---- the file ----
        FdField(if (doc == null) "Scan or PDF" else "File", hint = when {
            files != null -> files!!.joinToString(", ") { it.name } + if (doc?.attachments?.isNotEmpty() == true) " · replaces what's there now" else ""
            doc?.attachments?.isNotEmpty() == true -> "${doc.attachments.size} page${if (doc.attachments.size == 1) "" else "s"} saved"
            else -> "A photo (JPG, PNG) or a PDF: its details are read and filled in, as on the phone"
        }) {
            // what you picked, so it's clear it worked
            files?.takeIf { it.isNotEmpty() }?.let { list ->
                Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.take(4).forEach { f ->
                        val img = remember(f) { previewOf(f) }
                        Box(Modifier.size(width = 96.dp, height = 120.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .background(c.surface2).border(1.dp, c.line, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            if (img != null) Image(img, f.name, Modifier.size(width = 96.dp, height = 120.dp), contentScale = ContentScale.Crop)
                            else FdIcon("file", 28.dp, tint = c.muted)
                        }
                    }
                    if (list.size > 4) Text("+${list.size - 4}", style = fdText(13f, FontWeight.SemiBold, c.muted), modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
            reading?.let { com.familydocs.app.ui.ds.FdBanner(if (busyReading) "sparkle" else if (readByOcr) "check" else "info", it, Modifier.padding(bottom = 4.dp)) }
            FdButton(if (files == null && doc?.attachments.isNullOrEmpty()) "Choose the file…" else "Choose another file…", {
                // one document at a time (a PDF can have several pages)
                pickFiles("Choose the scan or PDF", onProblem = { toast.show(it) }).firstOrNull()?.let { picked ->
                    files = listOf(picked)
                    // no name yet: the type it looks like, else the file's own name (the scan's own reading may replace it)
                    if (name.isBlank()) {
                        val base = picked.name.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
                        val t = DocTypes.forName(base)
                        name = if (t.matched) t.label else base.replaceFirstChar { it.uppercase() }
                        nameFromFile = !t.matched
                        template(name)
                    }
                    readFile(picked)
                }
            }, kind = BtnKind.Ghost, icon = "upload", enabled = !busyReading)
        }
        // ---- country ----
        val mine = Countries.decode(view.data.account?.countries)
        FdField("Country") {
            FdChips {
                (mine + listOfNotNull(country)).distinct().forEach { code ->
                    FdChip(Countries.short(code), country == code, { country = if (country == code) null else code }, leading = { FdFlag(code, 18.dp) })
                }
                FdChip("Other…", otherCountry, { otherCountry = !otherCountry })
            }
            if (otherCountry) {
                FdInput(countryQuery, { countryQuery = it }, placeholder = "Search countries", icon = "search")
                FdChips {
                    Countries.search(countryQuery, exclude = mine, limit = 12).forEach { code ->
                        FdChip(Countries.name(code), country == code, { country = code; otherCountry = false }, leading = { FdFlag(code, 18.dp) })
                    }
                }
            }
        }
        // ---- details ----
        Text("Details", style = fdText(12f, FontWeight.Medium, c.muted))
        fields.forEachIndexed { i, f ->
            val label = f.label; val value = f.value
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FdInput(label, { v -> fields[i] = fields[i].copy(label = v); touched = true }, Modifier.weight(.8f), placeholder = "Label")
                var picking by remember { mutableStateOf(false) }
                val calendar: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = if (!DocTypes.isDate(label, value)) null else {
                    {
                        Box {
                            FdIconButton("calendar", { picking = true }, size = 30.dp, iconSize = 17.dp, tint = c.muted, description = "Pick the date")
                            if (picking) CalendarPopup(com.familydocs.app.domain.Expiry.parse(value), { d ->
                                fields[i] = fields[i].copy(value = d.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")), auto = false, verified = null); touched = true; picking = false
                            }, { picking = false })
                        }
                    }
                }
                FdInput(value, { v -> fields[i] = fields[i].copy(value = v, auto = false, verified = null); touched = true }, Modifier.weight(1.2f), placeholder = if (DocTypes.isDate(label)) "dd/mm/yyyy" else "Value",
                    trailing = calendar)
                FdIconButton("minus", { fields.removeAt(i) }, size = 34.dp, iconSize = 16.dp, tint = c.muted, description = "Remove $label")
            }
        }
        FdChip("Add a field", false, { fields.add(DocField("", "")); touched = true }, leading = { FdIcon("plus", 15.dp, tint = c.fg) })
    }
    if (deleting && doc != null) FdConfirmSheet("Delete ${doc.name}?", "It's removed from this computer and from your phone, with its files. This can't be undone.", "Delete",
        onConfirm = {
            deleting = false
            app.save(toast, "Deleted", build = { DeskOps.op(DeskOps.DEL_DOC).put("doc", doc.id) }, close = { onDismiss(); if (app.page is com.familydocs.desktop.Page.Doc) app.back() })
        }, onDismiss = { deleting = false })
}

/** Archive or bring back: kept and searchable, but out of the wallet, counts and reminders (as on the phone). */
fun AppModel.setArchived(toast: com.familydocs.app.ui.ds.FdToastState, d: DocumentEntity, archived: Boolean) =
    save(toast, if (archived) "Archived" else "Back in the wallet", build = {
        DeskOps.op(DeskOps.DOC).put("doc", DeskOps.docJson(d.copy(archivedAt = if (archived) System.currentTimeMillis() else null)))
    }, close = {})

// ---------------- people ----------------

/** Someone's profile (yours included): name, relation, picture. [member] null adds a person to group [groupId]. */
@Composable
fun PersonEditSheet(view: GroupView, member: MemberEntity?, groupId: String?, onDismiss: () -> Unit) {
    val app = rememberApp()
    val c = Fd.c
    val toast = LocalToast.current
    var name by remember { mutableStateOf(member?.name ?: "") }
    var relation by remember { mutableStateOf(member?.relation ?: "") }
    var avatar by remember { mutableStateOf(member?.avatar ?: CHARACTERS.map { it.first }.firstOrNull { k -> view.data.members.none { it.avatar == k } } ?: CHARACTERS.first().first) }
    var photo by remember { mutableStateOf<NewPhoto?>(null) }
    var removePhoto by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val canDelete = member != null && !member.isSelf && !member.shared && member.origin == null
    EditSheet(when { member == null -> "Add a person"; member.isSelf -> "Your profile"; else -> "Edit ${member.name}" },
        if (member == null) "Someone in ${view.group?.name ?: "the group"} whose documents you keep (a child, a parent…). Group members with their own phone join with an invite instead." else null,
        onDismiss, canSave = name.isNotBlank(), onDelete = if (canDelete) ({ deleting = true }) else null, deleteLabel = "Remove",
        onSave = {
            app.save(toast, if (member == null) "${name.trim()} added" else "Saved", build = {
                val id = member?.id ?: UUID.randomUUID().toString()
                val j = JSONObject().put("id", id).put("name", name.trim()).put("relation", relation.trim()).put("avatar", avatar)
                    .put("group", member?.groupId ?: groupId ?: JSONObject.NULL)
                DeskOps.op(DeskOps.MEMBER).put("member", j).apply {
                    when {
                        photo != null -> put("photo", Desk.upload(photo!!.bytes))
                        removePhoto -> put("photo", JSONObject.NULL)
                    }
                }
            }, close = onDismiss)
        }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val img = photo?.image
            if (img != null) Image(img, null, Modifier.size(84.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            else FdAvatar(name, 84.dp, photoFile = member?.photoFile?.takeIf { !removePhoto }, character = avatar, shared = member?.shared == true)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PhotoActions("Their photo", member?.photoFile?.takeIf { !removePhoto }, hasPhoto = photo != null || (member?.photoFile != null && !removePhoto),
                    onPhoto = { photo = NewPhoto(it); removePhoto = false }, onRemove = { photo = null; removePhoto = true })
            }
        }
        if (member?.shared != true) FdField("Or a character") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CHARACTERS.forEach { (key, res) ->
                    Image(fdPainter(res), key, Modifier.size(40.dp).clip(CircleShape).border(2.dp, if (avatar == key) c.primary else c.line, CircleShape)
                        .clickable { avatar = key }, contentScale = ContentScale.Crop)
                }
            }
        }
        FdField("Name") { FdInput(name, { name = it }, placeholder = "Name") }
        if (member?.isSelf != true && member?.shared != true && member?.origin == null) FdField("Relation") {
            FdInput(relation, { relation = it }, placeholder = "Daughter, Father, Partner…")
            FdChips { listOf("Partner", "Child", "Parent", "Sibling", "Grandparent", "Friend").forEach { r -> FdChip(r, relation == r, { relation = r }) } }
        }
    }
    if (deleting && member != null) FdConfirmSheet("Remove ${member.name}?", "${member.name} and their documents are removed from this computer and your phone. This can't be undone.", "Remove",
        onConfirm = {
            deleting = false
            app.save(toast, "Removed", build = { DeskOps.op(DeskOps.DEL_MEMBER).put("member", member.id) }, close = { onDismiss(); app.home() })
        }, onDismiss = { deleting = false })
}

// ---------------- events ----------------

/** A trip, birthday or appointment: new ([event] null) or changed. Everyone it's shared with can change it; only you choose who sees yours. */
@Composable
fun EventEditSheet(view: GroupView, event: EventEntity?, onDismiss: () -> Unit, onSaved: (String) -> Unit = {}) {
    val app = rememberApp()
    val c = Fd.c
    val toast = LocalToast.current
    val me = view.data.me
    var name by remember { mutableStateOf(event?.name ?: "") }
    var art by remember { mutableStateOf(event?.art ?: "trip") }
    var artTouched by remember { mutableStateOf(event != null) }
    var start by remember { mutableStateOf(event?.startDay ?: LocalDate.now().plusDays(7).toString()) }
    var end by remember { mutableStateOf(event?.endDay ?: "") }
    val people = remember { mutableStateListOf<String>().apply { addAll(event?.peopleIds ?: listOfNotNull(me)) } }
    val visible = remember { mutableStateListOf<String>().apply { addAll(event?.visibleIds ?: listOfNotNull(me)) } }
    val pins = remember { mutableStateListOf<String>().apply { addAll(event?.pinIds.orEmpty()) } }
    var remind by remember { mutableStateOf(event?.remindDays ?: 7) }
    var notes by remember { mutableStateOf(event?.notes ?: "") }
    var photo by remember { mutableStateOf<NewPhoto?>(null) }
    var removePhoto by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val newId = remember { event?.id ?: UUID.randomUUID().toString() }
    val startDay = Events.parse(start)
    val endDay = Events.parse(end)
    val organiser = event == null || !event.received
    val everyone = view.members.filter { !it.shared }
    EditSheet(if (event == null) "New event" else "Edit ${event.name}", null, onDismiss,
        canSave = name.isNotBlank() && startDay != null && (end.isBlank() || endDay != null),
        onDelete = if (event != null && organiser) ({ deleting = true }) else null,
        onSave = {
            app.save(toast, if (event == null) "Event added" else "Saved", build = {
                val row = (event ?: EventEntity(id = newId, name = "", art = art, startDay = start, groupId = view.group?.id)).copy(
                    name = name.trim(), art = art, startDay = startDay.toString(), endDay = endDay?.takeIf { startDay != null && it.isAfter(startDay) }?.toString(),
                    people = people.joinToString(","), visibleTo = if (organiser) (visible + listOfNotNull(me)).distinct().joinToString(",") else event!!.visibleTo,
                    pins = pins.joinToString(","), remindDays = remind, notes = notes.trim(),
                )
                DeskOps.op(DeskOps.EVENT).put("event", DeskOps.eventJson(row)).apply {
                    when {
                        photo != null -> put("photo", Desk.upload(photo!!.bytes))
                        removePhoto -> put("photo", JSONObject.NULL)
                    }
                }
            }, close = { onDismiss(); onSaved(newId) })
        }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val img = photo?.image
            if (img != null) Image(img, null, Modifier.size(84.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            else FdEventPic(art, event?.photoFile?.takeIf { !removePhoto }, 84.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PhotoActions("The event's picture", event?.photoFile?.takeIf { !removePhoto }, hasPhoto = photo != null || (event?.photoFile != null && !removePhoto),
                    onPhoto = { photo = NewPhoto(it); removePhoto = false }, onRemove = { photo = null; removePhoto = true })
            }
        }
        FdField("Name") { FdInput(name, { name = it; if (!artTouched) art = Events.artFor(it) }, placeholder = "Lisbon trip, Lily's birthday…") }
        FdField("Picture") {
            FdChips { EVENT_ARTS.forEach { (key, label, res) ->
                FdChip(label, art == key, { art = key; artTouched = true }, leading = { Image(fdPainter(res), null, Modifier.size(20.dp).clip(CircleShape), contentScale = ContentScale.Crop) })
            } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FdField("From", Modifier.weight(1f)) {
                DateButton(startDay, { d -> d?.let { start = it.toString(); if (endDay != null && !endDay.isAfter(it)) end = "" } })
            }
            FdField("Until (optional)", Modifier.weight(1f)) {
                DateButton(endDay, { end = it?.toString() ?: "" }, placeholder = "One day", min = startDay?.plusDays(1), clearable = true)
            }
        }
        FdField("Who's going") {
            FdChips { everyone.forEach { m -> FdChip(if (m.id == me) "You" else m.name, m.id in people, { if (m.id in people) people.remove(m.id) else people.add(m.id) },
                leading = { FdAvatar(m.name, 20.dp, photoFile = m.photoFile, character = m.avatar) }) } }
        }
        if (organiser) FdField("Who can see it", hint = "You always can. Everyone who can see it can change it; only you choose who sees it.") {
            FdChips { everyone.filter { it.id != me }.forEach { m -> FdChip(m.name, m.id in visible, { if (m.id in visible) visible.remove(m.id) else visible.add(m.id) },
                leading = { FdAvatar(m.name, 20.dp, photoFile = m.photoFile, character = m.avatar) }) } }
        }
        FdField("Reminder") { FdChips { Events.REMINDERS.forEach { (d, label) -> FdChip(label, remind == d, { remind = d }) } } }
        val pinnable = view.docs.filter { it.memberId in people || it.memberId in view.members.filter { m -> m.shared }.map { m -> m.id } }
        if (pinnable.isNotEmpty()) FdField("Pinned documents", hint = "Documents from the vault to have at hand for this event") {
            FdChips { pinnable.forEach { d ->
                FdChip("${d.name} · ${view.member(d.memberId)?.let { if (it.id == me) "you" else it.name } ?: ""}", d.id in pins,
                    { if (d.id in pins) pins.remove(d.id) else pins.add(d.id) })
            } }
        }
        FdField("Notes") { com.familydocs.app.ui.ds.FdNotesField(notes, { notes = it }, placeholder = "Plans, addresses, what to pack…") }
    }
    if (deleting && event != null) FdConfirmSheet("Delete ${event.name}?", "The event and the tickets added only to it are removed from this computer, your phone and everyone it's shared with. Pinned vault documents stay.",
        "Delete", onConfirm = {
            deleting = false
            app.save(toast, "Deleted", build = { DeskOps.op(DeskOps.DEL_EVENT).put("event", event.id) }, close = { onDismiss(); app.go(com.familydocs.desktop.Page.Events) })
        }, onDismiss = { deleting = false })
}

/** Whether you can change [d] here: yours, Common's, your people's; another member's only as a group admin. */
fun GroupView.canEdit(d: DocumentEntity): Boolean {
    val m = member(d.memberId) ?: return false
    if (d.origin == null && m.origin == null) return true
    return group?.isAdmin(data.me) == true && d.eventId == null
}

/** Same for a person's profile. */
fun GroupView.canEdit(m: MemberEntity): Boolean = m.origin == null || group?.isAdmin(data.me) == true

@Composable
fun SwitchRow(title: String, text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = fdText(14f, FontWeight.SemiBold))
            Text(text, style = fdText(12f, color = Fd.c.muted))
        }
        FdSwitch(on, onChange, title)
        Spacer(Modifier.width(2.dp))
    }
}
