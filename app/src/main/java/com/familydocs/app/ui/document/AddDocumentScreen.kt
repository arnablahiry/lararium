package com.familydocs.app.ui.document

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import com.familydocs.app.ui.ds.BtnKind
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familydocs.app.data.DisplayMode
import com.familydocs.app.ui.ds.FdSwitch
import com.familydocs.app.ui.ds.FdListItem
import com.familydocs.app.ui.ds.FdList
import com.familydocs.app.domain.Countries
import com.familydocs.app.domain.DocTypes
import com.familydocs.app.ui.components.LocalContainer
import com.familydocs.app.ui.components.appViewModel
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdBanner
import com.familydocs.app.ui.ds.FdButton
import com.familydocs.app.ui.ds.FdChip
import com.familydocs.app.ui.ds.FdChips
import com.familydocs.app.ui.ds.FdCountryPicker
import com.familydocs.app.ui.ds.FdDocTile
import com.familydocs.app.ui.ds.FdIcon
import com.familydocs.app.ui.ds.FdIconButton
import com.familydocs.app.ui.ds.FdInput
import com.familydocs.app.ui.ds.FdSavebar
import com.familydocs.app.ui.ds.FdSectionTitle
import com.familydocs.app.ui.ds.FdTextButton
import com.familydocs.app.ui.ds.FdTopBar
import com.familydocs.app.ui.ds.LocalToast
import com.familydocs.app.ui.ds.dashedBorder
import com.familydocs.app.ui.ds.fdText
import java.io.File

@Composable
fun AddDocumentScreen(memberId: String, docId: String?, onBack: () -> Unit, onSaved: () -> Unit, eventId: String? = null, parentId: String? = null) {
    val app = LocalContainer.current
    val c = Fd.c
    /** The field whose date is being picked on the calendar (its index). */
    var pickingDate by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val toast = LocalToast.current
    val vm = appViewModel(key = "doc-$memberId-$docId-$eventId-$parentId") { AddDocumentViewModel(it, context.applicationContext, memberId, docId, eventId, parentId) }
    val member by app.repo.member(memberId).collectAsStateWithLifecycle(null)
    val event by (eventId?.let { app.repo.event(it) } ?: kotlinx.coroutines.flow.flowOf(null)).collectAsStateWithLifecycle(null)
    val account by app.repo.account.collectAsStateWithLifecycle(null)
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var showRaw by remember { mutableStateOf(false) }

    // -1 = add the picked file after the others; otherwise the index of the file it replaces
    var target by rememberSaveable { mutableStateOf(-1) }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.onFile(context, it, target.takeIf { t -> t >= 0 }) } }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> val u = cameraUri?.let(Uri::parse); if (ok && u != null) vm.onFile(context, u, target.takeIf { t -> t >= 0 }) }
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.onCustomPicture(context, it) } }
    fun upload(replace: Int = -1) { target = replace; app.appLock.expectExternalActivity(); openDoc.launch(arrayOf("image/*", "application/pdf")) }
    fun camera(replace: Int = -1) {
        target = replace
        val f = File(context.cacheDir, "camera").apply { mkdirs() }.let { File(it, "scan.jpg") }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        cameraUri = uri.toString(); app.appLock.expectExternalActivity(); takePicture.launch(uri)
    }
    fun save() { if (vm.name.isBlank()) toast.show("Give the document a name") else vm.save(onSaved) }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().imePadding()) {
        FdTopBar(if (docId != null) "Edit document" else if (parentId != null) "Extend document" else "Add document", onBack) { FdTextButton("Save", ::save, enabled = !vm.saving) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(event?.let { "For ${it.name} · only shows in this event" } ?: "For ${member?.name.orEmpty()}", style = fdText(11.5f, color = c.muted), modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))

            // ---------- File ----------
            FdSectionTitle("File", Modifier.padding(top = 0.dp))
            val atts = vm.attachments
            if (atts.isNotEmpty()) {
                // Front, back, more pages: swipe between them; Replace / Remove act on the one showing.
                val pager = rememberPagerState { atts.size }
                var lastCount by remember { mutableStateOf(atts.size) }
                LaunchedEffect(atts.size) { if (atts.size > lastCount) pager.animateScrollToPage(atts.size - 1); lastCount = atts.size }
                Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface2).border(1.dp, c.line, RoundedCornerShape(18.dp))) {
                    HorizontalPager(pager, Modifier.fillMaxWidth()) { i ->
                        val p = atts.getOrNull(i)?.preview
                        if (p != null) Image(p.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                        else Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { FdIcon("file", 34.dp, tint = c.muted) }
                    }
                    if (vm.ocr is OcrState.Reading) ScanLine(Modifier.matchParentSize())
                    if (atts.size > 1) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xB80A0E18)).padding(horizontal = 9.dp, vertical = 4.dp)) {
                        Text("${pager.currentPage + 1} / ${atts.size}", style = fdText(11.5f, color = Color.White))
                    }
                    Row(Modifier.align(Alignment.BottomEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DarkPill("Replace", "refresh") { upload(pager.currentPage) }
                        DarkPill("Remove", "trash") { vm.removeAttachment(pager.currentPage) }
                    }
                }
                if (atts.size > 1) PageDots(atts.size, pager.currentPage, Modifier.fillMaxWidth().padding(top = 8.dp))
                Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FdButton("Scan another page", { camera() }, Modifier.weight(1f), BtnKind.Ghost, icon = "camera")
                    FdButton("Add file", { upload() }, Modifier.weight(1f), BtnKind.Ghost, icon = "plus")
                }
            } else {
                Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).dashedBorder(c.line, radius = 20.dp).padding(18.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PickOption("Scan", "Use camera", "camera", Modifier.weight(1f)) { camera() }
                        PickOption("Upload", "Image or PDF", "upload", Modifier.weight(1f)) { upload() }
                    }
                }
            }

            // ---------- Name ----------
            FdSectionTitle("Document name")
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FdDocTile(vm.name)
                FdInput(vm.name, vm::onNameChange, Modifier.weight(1f), placeholder = "e.g. Passport, Birth certificate")
            }
            val type = DocTypes.forName(vm.name)
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (vm.name.isNotBlank() && type.matched) FdIcon("sparkle", 12.dp, tint = c.muted)
                Text(when { vm.name.isBlank() -> "The tile icon is picked from the name you type."; type.matched -> "Icon matched to ${type.label}"; else -> "No match. Using a file icon with initials." },
                    style = fdText(11.5f, color = c.muted))
            }
            FdChips(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)) {
                // for an event: tickets, bookings, passes…; otherwise the usual ID documents
                (if (vm.forEvent) DocTypes.EVENT_SUGGESTIONS else DocTypes.SUGGESTIONS).forEach { s -> FdChip(s, vm.name.equals(s, true), { vm.onNameChange(s) }) }
            }

            // ---------- Issued by ----------
            FdSectionTitle("Issued by")
            Box(Modifier.padding(horizontal = 20.dp)) {
                FdCountryPicker(listOf(vm.country), { vm.countryChoice = it }, multi = false, pinned = Countries.decode(account?.countries), allowNone = true)
            }
            // Faint flag behind the card art; on by default for national ID cards, any document can turn it on or off.
            FdList(Modifier.padding(top = 10.dp)) {
                FdListItem("Show flag on card", if (vm.country.isEmpty()) "Pick a country first" else "Faint and tilted behind the card art", first = true, trailing = {
                    FdSwitch(vm.showFlag && vm.country.isNotEmpty(), { v -> vm.showFlagChoice = v }, "Show flag on card")
                })
            }

            // ---------- Display picture ----------
            FdSectionTitle("Display picture")
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DisplayOption("Auto icon", vm.display == DisplayMode.ICON, true, Modifier.weight(1f), { vm.display = DisplayMode.ICON }) { FdDocTile(vm.name) }
                val preview = vm.preview
                DisplayOption("From file", vm.display == DisplayMode.FROM_FILE, preview != null, Modifier.weight(1f), { vm.display = DisplayMode.FROM_FILE }) {
                    FdDocTile(vm.name, picture = preview?.asImageBitmap(), iconOverride = if (preview == null) "image" else null, hueOverride = 0xFF888888)
                }
                DisplayOption("Own picture", vm.display == DisplayMode.CUSTOM, true, Modifier.weight(1f), {
                    if (vm.customPicture == null) { app.appLock.expectExternalActivity(); pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    else vm.display = DisplayMode.CUSTOM
                }) { FdDocTile(vm.name, picture = vm.customPicture?.asImageBitmap(), iconOverride = if (vm.customPicture == null) "upload" else null, hueOverride = 0xFF888888) }
            }

            // ---------- Digitised details ----------
            FdSectionTitle("Digitised details") { if (vm.ocr is OcrState.Done) FdIcon("sparkle", 14.dp, tint = c.muted) }
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val o = vm.ocr) {
                    OcrState.Reading -> {
                        FdBanner("scan", "Reading the document…")
                        repeat(4) { Shimmer() }
                    }
                    is OcrState.Done -> FdBanner("sparkle", (o.label?.let { "Looks like a $it. " } ?: "") + "Filled ${o.count} field${if (o.count == 1) "" else "s"}. Check and edit below.")
                    is OcrState.Failed -> FdBanner("info", "${o.message}. Type the details below.", accent = false)
                    OcrState.Idle -> if (vm.fields.isEmpty()) FdBanner("scan", "Add a file and the card number, names and dates will be read from it automatically.", accent = false)
                }
                if (vm.ocr !is OcrState.Reading && vm.fields.isNotEmpty()) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp))) {
                    vm.fields.forEachIndexed { i, f ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    BasicTextField(f.label, { vm.updateField(i, f.copy(label = it)) }, singleLine = true, textStyle = fdText(11f, FontWeight.Medium, c.muted),
                                        cursorBrush = SolidColor(c.primary), modifier = Modifier.weight(1f, fill = false))
                                }
                                val isDate = com.familydocs.app.domain.DocTypes.isDate(f.label, f.value)
                                BasicTextField(f.value, { vm.updateField(i, f.copy(value = it)) }, singleLine = true, textStyle = fdText(14.5f, color = c.fg),
                                    cursorBrush = SolidColor(c.primary), modifier = Modifier.fillMaxWidth(),
                                    decorationBox = { inner -> Box { if (f.value.isEmpty()) Text(if (isDate) "DD/MM/YYYY" else "—", style = fdText(14.5f, color = c.muted)); inner() } })
                            }
                            // dates: type DD/MM/YYYY, or pick it on a calendar
                            if (com.familydocs.app.domain.DocTypes.isDate(f.label, f.value))
                                FdIconButton("calendar", { pickingDate = i }, size = 32.dp, iconSize = 17.dp, tint = c.muted, description = "Pick ${f.label} on a calendar")
                            FdIconButton("close", { vm.removeField(i) }, size = 32.dp, iconSize = 16.dp, tint = c.muted, description = "Remove field")
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).dashedBorder(c.line, radius = 14.dp).clickable(onClick = vm::addField).padding(11.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) { FdIcon("plus", 16.dp, tint = c.muted); Text("  Add field", style = fdText(13f, color = c.muted)) }
                if (vm.rawText.isNotBlank()) {
                    Text((if (showRaw) "▾ " else "▸ ") + "Text read from the file", style = fdText(12f, color = c.muted), modifier = Modifier.clickable { showRaw = !showRaw }.padding(top = 2.dp))
                    if (showRaw) Text(vm.rawText, style = fdText(11f), modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).clip(RoundedCornerShape(12.dp))
                        .background(c.surface).border(1.dp, c.line, RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState()).padding(10.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        FdSavebar { FdButton(if (vm.saving) "Saving…" else "Save document", ::save, icon = "check", enabled = !vm.saving) }
    }
    pickingDate?.let { at ->
        val f = vm.fields.getOrNull(at) ?: run { pickingDate = null; return@let }
        FdDatePickerDialog(f.value, onPick = { v -> vm.updateField(at, f.copy(value = v)) }, onDismiss = { pickingDate = null })
    }
}

/** `.drop .opt` */
@Composable
private fun PickOption(title: String, sub: String, icon: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Fd.c
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(c.bg).border(1.dp, c.line, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FdIcon(icon, 26.dp)
        Text(title, style = fdText(12.5f, FontWeight.SemiBold))
        Text(sub, style = fdText(11f, color = c.muted))
    }
}

/** `.dispopt` */
@Composable
private fun DisplayOption(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit, preview: @Composable () -> Unit) {
    val c = Fd.c
    Column(
        modifier.alpha(if (enabled) 1f else .45f).clip(RoundedCornerShape(16.dp)).background(if (selected) c.mix(c.primary, .07f, c.surface) else c.surface)
            .border(1.5.dp, if (selected) c.primary else c.line, RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { preview(); Text(label, style = fdText(11.5f)) }
}

/** `.preview .bar button` */
/** Small dots under a swipeable set of pages. */
@Composable
fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val c = Fd.c
    Row(modifier, horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(Modifier.padding(horizontal = 3.dp).size(if (i == current) 7.dp else 6.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (i == current) c.fg.copy(alpha = .7f) else c.muted.copy(alpha = .35f)))
        }
    }
}

@Composable
private fun DarkPill(label: String, icon: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xB80A0E18)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FdIcon(icon, 14.dp, tint = Color.White); Text(label, style = fdText(12f, color = Color.White))
    }
}

/** `.scanline`: glowing accent line sweeping up and down while the file is read. */
@Composable
private fun ScanLine(modifier: Modifier) {
    val c = Fd.c
    val t by rememberInfiniteTransition(label = "scan").animateFloat(.04f, .94f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scanY")
    BoxWithConstraints(modifier) {
        val y = maxHeight * t
        Box(Modifier.offset(y = y - 10.dp).fillMaxWidth().height(23.dp).background(Brush.verticalGradient(listOf(Color.Transparent, c.accent.copy(alpha = .45f), Color.Transparent))))
        Box(Modifier.offset(y = y).fillMaxWidth().height(3.dp).shadow(6.dp).background(c.accent))
    }
}

/** `.shimmer`: placeholder row while reading. */
@Composable
private fun Shimmer() {
    val c = Fd.c
    val x by rememberInfiniteTransition(label = "shim").animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "shimX")
    BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp))) {
        val w = with(LocalDensity.current) { maxWidth.toPx() }
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.surface, c.surface2, c.surface), Offset(-w + 2 * w * x, 0f), Offset(w * x * 2, 0f))))
    }
}

/** A calendar for a DD/MM/YYYY field: opens on the date already there (or today) and writes the picked day back. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FdDatePickerDialog(value: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val fmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu")
    val current = remember(value) {
        Regex("(\\d{1,2})/(\\d{1,2})/(\\d{4})").find(value)?.destructured?.let { (d, m, y) -> runCatching { java.time.LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull() }
    }
    val state = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = current?.atStartOfDay(java.time.ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        initialDisplayedMonthMillis = (current ?: java.time.LocalDate.now()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = 1900..2100,
    )
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            com.familydocs.app.ui.ds.FdTextButton("OK", {
                state.selectedDateMillis?.let { ms -> onPick(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate().format(fmt)) }
                onDismiss()
            })
        },
        dismissButton = { com.familydocs.app.ui.ds.FdTextButton("Cancel", onDismiss) },
    ) { androidx.compose.material3.DatePicker(state) }
}
