package com.familydocs.app.ui.events

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familydocs.app.data.DocumentEntity
import com.familydocs.app.data.EventEntity
import com.familydocs.app.data.MemberEntity
import com.familydocs.app.domain.Events
import com.familydocs.app.events.EventReminders
import com.familydocs.app.ocr.Images
import com.familydocs.app.ui.components.LocalContainer
import com.familydocs.app.ui.ds.BtnKind
import com.familydocs.app.ui.ds.EVENT_ARTS
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdAvatar
import com.familydocs.app.ui.ds.FdBanner
import com.familydocs.app.ui.ds.FdButton
import com.familydocs.app.ui.ds.FdChip
import com.familydocs.app.ui.ds.FdChips
import com.familydocs.app.ui.ds.FdConfirmSheet
import com.familydocs.app.ui.ds.FdDocIcon
import com.familydocs.app.ui.ds.FdEmpty
import com.familydocs.app.ui.ds.FdEventPic
import com.familydocs.app.ui.ds.FdField
import com.familydocs.app.ui.ds.FdIcon
import com.familydocs.app.ui.ds.FdIconButton
import com.familydocs.app.ui.ds.FdInput
import com.familydocs.app.ui.ds.FdList
import com.familydocs.app.ui.ds.FdListItem
import com.familydocs.app.ui.ds.FdSavebar
import com.familydocs.app.ui.ds.FdSectionTitle
import com.familydocs.app.ui.ds.FdSheet
import com.familydocs.app.ui.ds.FdTextButton
import com.familydocs.app.ui.ds.FdTopBar
import com.familydocs.app.ui.ds.LocalToast
import com.familydocs.app.ui.ds.SheetTitle
import com.familydocs.app.ui.ds.dashedBorder
import com.familydocs.app.ui.ds.fdText
import com.familydocs.app.ui.ds.tail
import com.familydocs.app.ui.ds.tap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Events this phone's owner may see: the ones made visible to them (older events with no list are visible to all). */
fun EventEntity.visibleTo(selfId: String?): Boolean = visibleIds.isEmpty() || selfId == null || selfId in visibleIds

// ---------------- home: the events strip under the family ----------------

/** `.evstrip`: upcoming events as round pictures with a countdown, ending with the dashed "+ New event". */
@Composable
fun EventsStrip(events: List<EventEntity>, onOpen: (String) -> Unit, onNew: () -> Unit) {
    val c = Fd.c
    Column {
        FdSectionTitle("Events", Modifier.padding(top = 0.dp)) {
            if (events.isNotEmpty()) Text("${events.size} coming up", style = fdText(11f, FontWeight.Medium, c.muted))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 28.dp)) {
            items(events, key = { it.id }) { e ->
                StripItem(e.name, Events.countdown(e.startDay, e.endDay), { onOpen(e.id) }) { FdEventPic(e.art, e.photoFile, 96.dp, lift = true) }
            }
            item {
                StripItem("New event", "Trip, birthday, appointment…", onNew, muted = true) {
                    Box(Modifier.size(96.dp).dashedBorder(c.line, 2.dp, 48.dp), contentAlignment = Alignment.Center) { FdIcon("plus", 32.dp, 1.5f, c.muted) }
                }
            }
        }
    }
}

@Composable
private fun StripItem(title: String, subtitle: String, onClick: () -> Unit, muted: Boolean = false, picture: @Composable () -> Unit) {
    val c = Fd.c
    Column(Modifier.width(104.dp).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally) {
        picture()
        Text(title, style = fdText(13.5f, if (muted) FontWeight.SemiBold else FontWeight.Bold, if (muted) c.muted else c.fg), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp))
        Text(subtitle, style = fdText(11.5f, color = c.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

// ---------------- event ----------------

@Composable
fun EventScreen(eventId: String, onBack: () -> Unit, onEdit: () -> Unit, onDocument: (String) -> Unit, onAddTicket: (selfId: String) -> Unit) {
    val app = LocalContainer.current
    val c = Fd.c
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val event by app.repo.event(eventId).collectAsStateWithLifecycle(null)
    val members by app.repo.members.collectAsStateWithLifecycle(emptyList())
    val allDocs by app.repo.allDocuments.collectAsStateWithLifecycle(emptyList())
    val ownDocs by app.repo.eventDocuments(eventId).collectAsStateWithLifecycle(emptyList())
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val e = event ?: return
    val self = members.firstOrNull { it.isSelf }
    val people = e.peopleIds.mapNotNull { id -> members.firstOrNull { it.id == id } }
    val pinned = e.pinIds.mapNotNull { id -> allDocs.firstOrNull { it.id == id && it.eventId == null } }
    val others = e.visibleIds.filter { it != self?.id }.mapNotNull { id -> members.firstOrNull { it.id == id }?.name }
    // Someone else's event, shared with you: you can change it too, but only they choose who sees it (or delete it).
    val organiser = e.organiserKey?.let { key -> members.firstOrNull { it.origin == key && !it.shared }?.name ?: "another member" }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        FdTopBar("", onBack) { FdIconButton("edit", onEdit, iconSize = 20.dp, description = "Edit event") }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            // `.mhero`
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FdEventPic(e.art, e.photoFile, 104.dp, lift = true)
                Text(e.name, style = fdText(28f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                Text("${Events.dates(e.startDay, e.endDay)} · ${Events.countdown(e.startDay, e.endDay)}", style = fdText(13f, color = c.muted), modifier = Modifier.padding(top = 4.dp))
                if (people.isNotEmpty()) Row(
                    Modifier.padding(top = 10.dp).clip(RoundedCornerShape(99.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(99.dp))
                        .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row { people.forEachIndexed { i, p -> FdAvatar(p.name, 26.dp, Modifier.offset(x = (-7 * i).dp).border(2.dp, c.surface, CircleShape), photoFile = p.photoFile, character = p.avatar) } }
                    Text(people.joinToString(", ") { it.name } + " going", style = fdText(12f, color = c.muted), modifier = Modifier.offset(x = (-7 * (people.size - 1)).dp))
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    FdIcon("lock", 13.dp, 2f, c.muted)
                    Text(if (e.visibleIds.isEmpty()) "Visible to everyone in the group" else if (others.isEmpty()) "Only you can see this" else "Visible to you, ${others.joinToString(", ")}", style = fdText(11.5f, color = c.muted))
                }
                if (organiser != null) Text("Organised by $organiser", style = fdText(11.5f, color = c.muted), modifier = Modifier.padding(top = 2.dp))
                // who changed it last (shared events: anyone it's shared with can)
                if (e.updatedAt > 0 && (organiser != null || others.isNotEmpty() || e.visibleIds.isEmpty())) {
                    val who = e.editedBy?.let { k -> members.firstOrNull { it.origin == k && !it.shared }?.name ?: "someone" } ?: "you"
                    Text("Changed by $who · " + android.text.format.DateUtils.getRelativeTimeSpanString(e.updatedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS),
                        style = fdText(11.5f, color = c.muted), modifier = Modifier.padding(top = 2.dp))
                }
            }
            // A passport of someone going that runs out during the trip or within 6 months after it
            val tripEnd = Events.parse(e.endDay) ?: Events.parse(e.startDay)
            if (tripEnd != null && Events.isTravel(e.art) && !tripEnd.isBefore(java.time.LocalDate.now())) people.forEach { p ->
                val passport = allDocs.filter { it.memberId == p.id && it.eventId == null && !it.archived && com.familydocs.app.domain.Expiry.isPassport(it.name) }
                    .mapNotNull { d -> com.familydocs.app.events.ExpiryReminders.expiryOf(d)?.let { d to it } }.maxByOrNull { it.second } ?: return@forEach
                val problem = com.familydocs.app.domain.Expiry.tripProblem(passport.second, tripEnd) ?: return@forEach
                FdBanner("passport", "${if (p.isSelf) "Your" else p.name + "'s"} passport $problem (${com.familydocs.app.domain.Expiry.format(passport.second)}). " +
                    "Many countries refuse entry with less than 6 months left.", Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp).clickable { onDocument(passport.first.id) })
            }
            FdList(Modifier.padding(top = 16.dp)) {
                FdListItem("Reminder", Events.reminderLabel(e.remindDays) + if (e.remindDays >= 0) " · a notification on this phone" else "", first = true, icon = "bell", onClick = onEdit)
            }
            FdSectionTitle("Documents") { Text("${pinned.size + ownDocs.size}", style = fdText(11f, FontWeight.Medium, c.muted)) }
            run {
                // By person: everyone going (then anyone else whose documents are pinned), and Common last: the Common
                // vault's documents plus the tickets and bookings added only for this event.
                val owners = (people + pinned.mapNotNull { d -> members.firstOrNull { it.id == d.memberId } }.filter { !it.shared })
                    .distinctBy { it.id }
                owners.forEach { m ->
                    val mine = pinned.filter { it.memberId == m.id }
                    if (mine.isEmpty()) return@forEach
                    EventDocGroup(m.name, mine.size) { FdAvatar(m.name, 24.dp, photoFile = m.photoFile, character = m.avatar) }
                    FdList { mine.forEachIndexed { i, d -> FdListItem(d.name, d.tail(), first = i == 0, onClick = { onDocument(d.id) }, leading = { FdDocIcon(d, 42.dp) }) } }
                }
                // Common is always there: the Common vault's pinned documents and everything uploaded for the event.
                val common = pinned.filter { d -> members.firstOrNull { it.id == d.memberId }?.shared == true }
                run {
                    EventDocGroup("Common", common.size + ownDocs.size) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(c.mix(c.accent, .16f, c.surface)), contentAlignment = Alignment.Center) { FdIcon("people", 15.dp, tint = c.accent) }
                    }
                    if (common.isEmpty() && ownDocs.isEmpty()) Text("Tickets, hotel bookings, passes… for everyone going.", style = fdText(13.5f, color = c.muted),
                        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().dashedBorder(c.line, radius = 18.dp).padding(horizontal = 16.dp, vertical = 14.dp))
                    else FdList {
                        common.forEachIndexed { i, d -> FdListItem(d.name, d.tail(), first = i == 0, onClick = { onDocument(d.id) }, leading = { FdDocIcon(d, 42.dp) }) }
                        ownDocs.forEachIndexed { i, d ->
                            FdListItem(d.name, "For everyone going", first = common.isEmpty() && i == 0, onClick = { onDocument(d.id) }, leading = { FdDocIcon(d, 42.dp) })
                        }
                    }
                    // Uploads for the event belong to the Common vault (you, if there's none) and show only here.
                    FdButton("Upload document", { (members.firstOrNull { it.shared } ?: self)?.let { onAddTicket(it.id) } },
                        Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp), BtnKind.Ghost, icon = "upload")
                }
            }
            FdButton("Pin from people's vaults", { picking = true }, Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), BtnKind.Ghost, icon = "people")
            // Notes: always here, written right on the page and saved as you type (a moment after you stop).
            FdSectionTitle("Notes")
            var note by remember(e.id) { mutableStateOf(e.notes) }
            // what's saved, as far as this page knows: notes changed on another phone show up unless you're mid-edit
            var saved by remember(e.id) { mutableStateOf(e.notes) }
            LaunchedEffect(e.notes) { if (e.notes != saved) { if (note == saved) note = e.notes; saved = e.notes } }
            LaunchedEffect(note) {
                if (note == e.notes) return@LaunchedEffect
                kotlinx.coroutines.delay(600)
                saved = note
                app.repo.saveEvent(e.copy(notes = note))
            }
            // *bold*, _italic_, ~strike~, ```mono``` like WhatsApp: styled as you type, clean when you're done
            com.familydocs.app.ui.ds.FdNotesField(note, { note = it }, Modifier.padding(horizontal = 20.dp), placeholder = "Hotel, train times, what to carry… (saved as you type)")
            if (organiser == null) FdButton("Delete event", { confirmDelete = true }, Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 30.dp), BtnKind.Ghost, icon = "trash", contentColor = c.danger)
            else Spacer(Modifier.height(30.dp))
        }
    }

    if (picking) PinSheet(e, members, allDocs.filter { it.eventId == null && !it.archived }, onApply = { ids ->
        val added = ids.count { it !in e.pinIds }
        scope.launch { app.repo.setEventPins(e.id, ids); if (added > 0) toast.show("Added $added document${if (added == 1) "" else "s"}") }
        picking = false
    }, onDismiss = { picking = false })

    if (confirmDelete) FdConfirmSheet(
        "Delete this event?", "Documents pinned from the vaults stay where they are. Tickets and bookings added only for this event are deleted.", "Delete",
        onConfirm = {
            scope.launch {
                EventReminders.cancel(context, e.id)
                app.repo.deleteEvent(e.id); confirmDelete = false; toast.show("Event deleted"); onBack()
            }
        },
        onDismiss = { confirmDelete = false },
    )
}

@Composable
private fun PinSheet(e: EventEntity, members: List<MemberEntity>, docs: List<DocumentEntity>, onApply: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val c = Fd.c
    // A checklist: what's pinned is ticked; one button applies it.
    var sel by remember { mutableStateOf(e.pinIds.toSet()) }
    val added = sel.count { it !in e.pinIds }
    val removed = e.pinIds.count { it !in sel }
    FdSheet(onDismiss) {
        SheetTitle("Documents for ${e.name}", "Pinned documents stay in their owner's vault.")
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            members.forEach { m ->
                val mine = docs.filter { it.memberId == m.id }
                if (mine.isNotEmpty()) {
                Text(m.name.uppercase(), style = fdText(11f, FontWeight.SemiBold, c.muted, spacing = .09f), modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))) {
                    mine.forEachIndexed { i, d ->
                        val on = d.id in sel
                        FdListItem(d.name, d.tail(), first = i == 0, onClick = { sel = if (on) sel - d.id else sel + d.id },
                            leading = { FdDocIcon(d, 42.dp) }, trailing = {
                            Box(Modifier.size(26.dp).clip(CircleShape).background(if (on) c.primary else androidx.compose.ui.graphics.Color.Transparent)
                                .border(1.5.dp, if (on) c.primary else c.line, CircleShape), contentAlignment = Alignment.Center) {
                                if (on) FdIcon("check", 16.dp, 2.6f, c.onPrimary)
                            }
                        })
                    }
                }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        FdButton(when {
            added > 0 -> "Add $added document${if (added == 1) "" else "s"}" + if (removed > 0) " · remove $removed" else ""
            removed > 0 -> "Remove $removed"
            else -> "Done"
        }, { onApply(docs.filter { it.id in sel }.map { it.id }) })
    }
}

// ---------------- new / edit ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEventScreen(eventId: String?, onBack: () -> Unit, onSaved: (id: String, isNew: Boolean) -> Unit) {
    val app = LocalContainer.current
    val c = Fd.c
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val members by app.repo.members.collectAsStateWithLifecycle(emptyList())
    val family = members.filter { !it.shared }
    val self = members.firstOrNull { it.isSelf }

    var loaded by remember { mutableStateOf(eventId == null) }
    var old by remember { mutableStateOf<EventEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var art by remember { mutableStateOf("trip") }
    var artTouched by remember { mutableStateOf(false) }
    // a picked (or re-edited) photo, cropped in the circle
    var photo by remember { mutableStateOf<com.familydocs.app.ui.home.CroppedPhoto?>(null) }
    var cropping by remember { mutableStateOf<Bitmap?>(null) }
    var cropStart by remember { mutableStateOf<com.familydocs.app.data.PhotoSources.Source?>(null) }
    var removePhoto by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf(LocalDate.now().plusDays(14)) }
    var end by remember { mutableStateOf<LocalDate?>(null) }
    var people by remember { mutableStateOf(setOf<String>()) }
    var visible by remember { mutableStateOf(setOf<String>()) }
    var remind by remember { mutableIntStateOf(7) }
    var notes by remember { mutableStateOf("") }
    var pickDate by remember { mutableStateOf<String?>(null) } // "start" | "end"

    LaunchedEffect(eventId, self?.id) {
        val me = self?.id ?: return@LaunchedEffect
        if (eventId == null) { if (people.isEmpty()) { people = setOf(me); visible = setOf(me) }; return@LaunchedEffect }
        if (loaded) return@LaunchedEffect
        app.repo.eventNow(eventId)?.let { e ->
            old = e; name = e.name; art = e.art; artTouched = true
            start = Events.parse(e.startDay) ?: start; end = Events.parse(e.endDay)
            people = e.peopleIds.toSet(); visible = (e.visibleIds + me).toSet(); remind = e.remindDays; notes = e.notes
        }
        loaded = true
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            cropStart = null
            cropping = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { Images.decode(it, 1600) } }
        }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) toast.show("Reminders need notifications. You can allow them later in the phone's settings.")
    }
    val hasPhoto = photo != null || (old?.photoFile != null && !removePhoto)

    fun save() {
        if (name.isBlank()) { toast.show("Give the event a name"); return }
        val me = self?.id ?: return
        scope.launch {
            val photoId = withContext(Dispatchers.IO) {
                when {
                    photo != null -> { app.files.delete(old?.photoFile); app.files.put(photo!!.bytes) }
                    removePhoto -> { app.files.delete(old?.photoFile); null }
                    else -> old?.photoFile
                }
            }
            val e = (old ?: EventEntity(id = UUID.randomUUID().toString(), name = "", art = art, startDay = start.toString())).copy(
                name = name.trim(), art = art, photoFile = photoId, startDay = start.toString(),
                endDay = end?.takeIf { it.isAfter(start) }?.toString(),
                people = people.joinToString(","), visibleTo = if (old?.received == true) old!!.visibleTo else (visible + me).joinToString(","), remindDays = remind, notes = notes.trim(),
            )
            app.repo.saveEvent(e)
            photo?.let { p -> app.appScope.launch { p.keep(app) } }
            EventReminders.schedule(context, e)
            toast.show(if (old == null) "Event added" else "Saved")
            onSaved(e.id, old == null)
        }
    }

    cropping?.let { bmp -> com.familydocs.app.ui.home.PhotoCropSheet(bmp, "Event picture", start = cropStart, out = 700,
        onDone = { p -> photo = p; removePhoto = false; cropping = null }, onDismiss = { cropping = null }) }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().imePadding()) {
        FdTopBar(if (eventId == null) "New event" else "Edit event", onBack) { FdTextButton("Save", ::save) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val p = photo
                if (p != null) Image(p.image, null, Modifier.size(132.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                else FdEventPic(art, old?.photoFile?.takeIf { !removePhoto }, 132.dp, lift = true)
            }
            FdField("Picture") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    items(EVENT_ARTS) { (key, label, res) ->
                        val on = !hasPhoto && key == art
                        Box(Modifier.size(58.dp).border(2.5.dp, if (on) c.primary else androidx.compose.ui.graphics.Color.Transparent, CircleShape).padding(4.dp).clip(CircleShape)
                            .tap { art = key; artTouched = true; photo = null; removePhoto = true }) {
                            Image(com.familydocs.app.ui.ds.fdPainter(res), label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                }
                FdChips(Modifier.padding(top = 4.dp)) {
                    if (old?.photoFile != null && photo == null && !removePhoto) FdChip("Edit photo", false, {
                        scope.launch { com.familydocs.app.ui.home.loadPhotoSource(app, old?.photoFile)?.let { s -> cropStart = s; cropping = s.bitmap } ?: toast.show("Couldn't open the photo") }
                    }, leading = { FdIcon("edit", 16.dp, tint = c.fg) })
                    FdChip(if (hasPhoto) "Change photo" else "Use a photo", false, {
                        app.appLock.expectExternalActivity(); photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, leading = { FdIcon("image", 16.dp, tint = c.fg) })
                    if (hasPhoto) FdChip("Remove photo", false, { photo = null; removePhoto = true }, leading = { FdIcon("trash", 16.dp, tint = c.fg) })
                }
            }
            FdField("Name") {
                FdInput(name, { v -> name = v; if (!artTouched) art = Events.artFor(v) }, placeholder = "e.g. Jaipur trip, Visa appointment", icon = "sparkle")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FdField("Starts", Modifier.weight(1f)) { DateBox(Events.dates(start.toString(), null)) { pickDate = "start" } }
                FdField("Ends (optional)", Modifier.weight(1f)) { DateBox(end?.let { Events.dates(it.toString(), null) } ?: "—", placeholder = end == null) { pickDate = "end" } }
            }
            FdField("Who's going") {
                FdChips {
                    family.forEach { m ->
                        FdChip(m.name, m.id in people, { people = if (m.id in people) people - m.id else people + m.id },
                            leading = { FdAvatar(m.name, 22.dp, photoFile = m.photoFile, character = m.avatar) })
                    }
                }
            }
            if (old?.received == true) FdField("Visible to", hint = "Only the person who made the event chooses who sees it.") {
                Text(family.filter { it.id in visible || it.id == self?.id }.joinToString(", ") { if (it.id == self?.id) "You" else it.name }, style = fdText(15f, color = c.fg))
            } else FdField("Visible to", hint = "Only these people see this event, its documents and its tickets. Keep a surprise party hidden from the birthday person.") {
                FdChips {
                    family.forEach { m ->
                        val isMe = m.id == self?.id
                        FdChip(if (isMe) "You" else m.name, isMe || m.id in visible, { if (!isMe) visible = if (m.id in visible) visible - m.id else visible + m.id },
                            leading = { FdAvatar(m.name, 22.dp, photoFile = m.photoFile, character = m.avatar) })
                    }
                }
            }
            FdField("Remind me") {
                FdChips {
                    Events.REMINDERS.forEach { (days, label) ->
                        FdChip(label, remind == days, {
                            remind = days
                            if (days >= 0 && Build.VERSION.SDK_INT >= 33 && !EventReminders.canNotify(context)) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        })
                    }
                }
            }
            FdField("Notes") { com.familydocs.app.ui.ds.FdNotesField(notes, { notes = it }, placeholder = "Hotel, train times, what to carry…") }
            Spacer(Modifier.height(8.dp))
        }
        FdSavebar { FdButton("Save event", ::save, icon = "check") }
    }

    pickDate?.let { which ->
        val initial = (if (which == "start") start else end ?: start).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { pickDate = null },
            confirmButton = {
                FdTextButton("OK", {
                    state.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        if (which == "start") { start = d; if (end != null && end!!.isBefore(d)) end = null } else end = if (d.isBefore(start)) start else d
                    }
                    pickDate = null
                })
            },
            dismissButton = {
                if (which == "end" && end != null) FdTextButton("No end date", { end = null; pickDate = null }) else FdTextButton("Cancel", { pickDate = null })
            },
        ) { DatePicker(state) }
    }
}

/** Looks like an FdInput, opens the date picker. */
@Composable
private fun DateBox(text: String, placeholder: Boolean = false, onClick: () -> Unit) {
    val c = Fd.c
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.5.dp, c.line, RoundedCornerShape(14.dp))
            .tap(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = fdText(14.5f, color = if (placeholder) c.muted else c.fg), modifier = Modifier.weight(1f), maxLines = 1)
    }
}

/** Heading of one person's (or Common's) documents on an event: picture, name and how many. */
@Composable
private fun EventDocGroup(name: String, count: Int, picture: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        picture()
        Text(name, style = fdText(13.5f, FontWeight.SemiBold), modifier = Modifier.weight(1f))
        Text("$count", style = fdText(12.5f, color = Fd.c.muted))
    }
}
