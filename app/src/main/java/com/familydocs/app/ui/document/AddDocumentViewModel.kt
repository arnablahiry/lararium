package com.familydocs.app.ui.document

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familydocs.app.AppContainer
import com.familydocs.app.data.Attachment
import com.familydocs.app.data.DisplayMode
import com.familydocs.app.data.DocumentEntity
import com.familydocs.app.domain.Countries
import com.familydocs.app.domain.DocField
import com.familydocs.app.domain.DocTypes
import com.familydocs.app.ocr.FieldMerge
import com.familydocs.app.ocr.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** A file on the edit screen: already stored in the vault ([id]) or just picked ([bytes]). */
class EditAttachment(val id: String?, val bytes: ByteArray?, val mime: String, val preview: Bitmap?, val name: String? = null)

/** Editable copy of a field; [id] keeps Compose keys stable while labels change. */
data class EditField(val id: String = UUID.randomUUID().toString(), val label: String, val value: String, val auto: Boolean = false, val verified: Boolean? = null)

sealed interface OcrState {
    data object Idle : OcrState
    data object Reading : OcrState
    data class Done(val label: String?, val count: Int) : OcrState
    data class Failed(val message: String) : OcrState
}

class AddDocumentViewModel(private val c: AppContainer, private val appContext: Context, private val memberId: String, private val docId: String?, private val eventId: String? = null) : ViewModel() {
    var name by mutableStateOf("")
    /** Added to an event (tickets, hotel bookings, passes…): offers those document types. */
    val forEvent: Boolean get() = eventId != null || existing?.eventId != null
    var display by mutableStateOf(DisplayMode.ICON)
    /** Front, back, more pages… in order; swipe through them on the edit and view screens. */
    val attachments = mutableStateListOf<EditAttachment>()
    /** The first file's first page: the "From file" display picture. */
    val preview: Bitmap? get() = attachments.firstOrNull()?.preview
    var customPicture by mutableStateOf<Bitmap?>(null); private set
    var ocr by mutableStateOf<OcrState>(OcrState.Idle); private set
    var rawText by mutableStateOf(""); private set
    val fields = mutableStateListOf<EditField>()
    var saving by mutableStateOf(false); private set
    /** Issuing country picked by the user; null = follow what the scan / name suggests. */
    var countryChoice by mutableStateOf<String?>(null)
    /** "Show flag on card": null = the type's default (on for national ID cards). */
    var showFlagChoice by mutableStateOf<Boolean?>(null)
    val showFlag: Boolean get() = showFlagChoice ?: DocTypes.flagByDefault(name)

    val country: String
        get() = countryChoice ?: Countries.infer(name, fields.map { DocField(it.label, it.value) }, rawText)

    private var existing: DocumentEntity? = null
    private var fileChanged = false
    private var pictureChanged = false
    private var fieldsTouched = false
    private var ocrJob: Job? = null

    init {
        if (docId != null) viewModelScope.launch {
            val d = c.repo.documentNow(docId) ?: return@launch
            existing = d
            name = d.name; display = d.display; rawText = d.rawText; countryChoice = d.country; showFlagChoice = d.showFlag
            fields.addAll(d.fields.map { EditField(label = it.label, value = it.value, auto = it.auto, verified = it.verified) })
            // An AFM card also holds the AMKA: give older ones the empty field to fill in
            if (DocTypes.forName(d.name).key == "afmamka" && fields.none { Regex("\\bamka\\b|ΑΜΚΑ|social", RegexOption.IGNORE_CASE).containsMatchIn(it.label) })
                fields.add(EditField(label = "AMKA", value = ""))
            fieldsTouched = true
            if (d.readByOcr) ocr = OcrState.Done(null, d.fields.count { it.auto })
            withContext(Dispatchers.IO) {
                val loaded = d.attachments.map { a -> EditAttachment(a.id, null, a.mime, c.files.get(a.id)?.let { Images.pages(appContext, it, a.mime).firstOrNull() }) }
                withContext(Dispatchers.Main) { attachments.addAll(loaded) }
                if (d.display == DisplayMode.CUSTOM) d.pictureFile?.let { c.files.get(it) }?.let { customPicture = Images.decode(it, 600) }
            }
        }
    }

    /**
     * Image or PDF from the picker / camera → on-device OCR → fields.
     * [replace]: index of the file it replaces; null adds it after the others (back side, more pages).
     */
    fun onFile(context: Context, uri: Uri, replace: Int? = null) {
        ocrJob?.cancel()
        ocrJob = viewModelScope.launch {
            ocr = OcrState.Reading
            runCatching {
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.also {
                        // Camera shots land in a temp file first; remove the unencrypted copy straight away.
                        if (uri.authority == context.packageName + ".files") runCatching { context.contentResolver.delete(uri, null, null) }
                    }
                }
                val pages = withContext(Dispatchers.Default) { Images.pages(context, bytes, mime) }
                require(pages.isNotEmpty()) { "Couldn't open that file" }
                val item = EditAttachment(null, bytes, mime, pages.first(), uri.lastPathSegment?.substringAfterLast('/'))
                val first = attachments.isEmpty() || replace == 0
                if (replace != null && replace in attachments.indices) attachments[replace] = item else attachments.add(item)
                fileChanged = true
                val (text, result) = c.textReader.readAndParse(pages, name.ifBlank { null })
                rawText = if (first) text else listOf(rawText, text).filter { it.isNotBlank() }.joinToString("\n")
                if (name.isBlank() && result.suggestedName != null) name = result.suggestedName
                // Fill the preset fields (Passport No., Surname…) with what was read; values typed by hand stay,
                // and a second page (the back of a card) only adds to what the first one filled.
                val merged = FieldMerge.merge(
                    fields.map { DocField(it.label, it.value, auto = it.auto, verified = it.verified) },
                    DocTypes.template(name), result.fields, ensureTemplate = !fieldsTouched, keepEarlierScan = !first,
                )
                fields.clear()
                fields.addAll(merged.map { EditField(label = it.label, value = it.value, auto = it.auto, verified = it.verified) })
                ocr = OcrState.Done(result.suggestedName, merged.count { it.auto && it.value.isNotBlank() })
            }.onFailure { ocr = OcrState.Failed(it.message ?: "Couldn't read this file"); if (fields.isEmpty()) applyTemplate() }
        }
    }

    /** Removes one file; removing the last one also clears the values its scan filled in. */
    fun removeAttachment(i: Int) {
        if (i !in attachments.indices) return
        ocrJob?.cancel()
        attachments.removeAt(i); fileChanged = true
        if (attachments.isEmpty()) {
            rawText = ""
            fields.removeAll { it.auto }
            if (display == DisplayMode.FROM_FILE) display = DisplayMode.ICON
            ocr = OcrState.Idle
        }
    }

    fun onCustomPicture(context: Context, uri: Uri) = viewModelScope.launch {
        val bmp = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { Images.decode(it, 800) } }
        if (bmp != null) { customPicture = bmp; pictureChanged = true; display = DisplayMode.CUSTOM }
    }

    fun onNameChange(v: String) {
        name = v
        // Untouched blank template follows the document type as the user types.
        if (!fieldsTouched && fields.none { it.value.isNotBlank() }) { fields.clear(); applyTemplate() }
    }

    private fun applyTemplate() { fields.addAll(DocTypes.template(name).map { EditField(label = it, value = "") }) }

    fun updateField(i: Int, f: EditField) { fields[i] = f; fieldsTouched = true }
    fun removeField(i: Int) { fields.removeAt(i); fieldsTouched = true }
    fun addField() { fields.add(EditField(label = "New field", value = "")); fieldsTouched = true }

    fun save(onSaved: () -> Unit) {
        if (name.isBlank() || saving) return
        saving = true
        viewModelScope.launch {
            val old = existing
            // Files: keep the ones still listed, store the new ones, delete the ones removed.
            val stored = withContext(Dispatchers.IO) {
                val keep = attachments.mapNotNull { it.id }.toSet()
                old?.attachments?.filter { it.id !in keep }?.forEach { c.files.delete(it.id) }
                attachments.map { a -> Attachment(a.id ?: c.files.put(a.bytes!!), a.mime) }
            }
            val fileId = stored.firstOrNull()?.id
            val picture = withContext(Dispatchers.Default) {
                when (display) {
                    DisplayMode.ICON -> null
                    DisplayMode.FROM_FILE -> preview?.let { Images.centerSquareJpeg(it, 480) }
                    DisplayMode.CUSTOM -> customPicture?.let { Images.centerSquareJpeg(it, 480) }
                }
            }
            val pictureId = withContext(Dispatchers.IO) {
                when {
                    display == DisplayMode.ICON -> { c.files.delete(old?.pictureFile); null }
                    picture != null && (fileChanged || pictureChanged || old?.pictureFile == null || old?.display != display) -> { c.files.delete(old?.pictureFile); c.files.put(picture) }
                    else -> old?.pictureFile
                }
            }
            val doc = (old ?: DocumentEntity(id = UUID.randomUUID().toString(), memberId = memberId, name = name, fileId = null, fileMime = null, eventId = eventId)).copy(
                name = name.trim(), fileId = fileId, fileMime = stored.firstOrNull()?.mime, moreFiles = Attachment.encode(stored.drop(1)), display = display, pictureFile = pictureId,
                rawText = rawText, readByOcr = ocr is OcrState.Done, country = country, showFlag = showFlagChoice,
            )
            val r = runCatching { c.repo.saveDocument(doc, fields.filter { it.label.isNotBlank() || it.value.isNotBlank() }.map {
                DocField(it.label.trim(), it.value.trim(), DocTypes.isSensitive(it.label), it.auto, it.verified)
            }) }
            saving = false
            // (another member's document and you're no longer an admin)
            r.exceptionOrNull()?.let { android.widget.Toast.makeText(appContext, it.message ?: "Couldn't save", android.widget.Toast.LENGTH_LONG).show(); return@launch }
            // a renewed passport, licence…: offer to put the old one away
            if (old == null) c.repo.replacedBy(doc).takeIf { it.isNotEmpty() }?.let { c.archiveOffer.value = doc.name to it.map { d -> d.id } }
            onSaved()
        }
    }
}
