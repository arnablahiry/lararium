package com.familydocs.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familydocs.app.data.MemberEntity
import com.familydocs.app.domain.DeskOps
import com.familydocs.app.domain.GroupInvite
import com.familydocs.app.ui.ds.BtnKind
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdAvatar
import com.familydocs.app.ui.ds.FdButton
import com.familydocs.app.ui.ds.FdChip
import com.familydocs.app.ui.ds.FdIcon
import com.familydocs.app.ui.ds.FdIconButton
import com.familydocs.app.ui.ds.FdInput
import com.familydocs.app.ui.ds.FdSheet
import com.familydocs.app.ui.ds.LocalToast
import com.familydocs.app.ui.ds.SheetTitle
import com.familydocs.app.ui.ds.fdText
import com.familydocs.desktop.Desk
import com.familydocs.desktop.Page
import com.familydocs.desktop.rememberApp
import org.json.JSONObject

/** Your invite as a QR code: a phone's camera opens the link and Lararium joins the group. */
fun qrImage(text: String, size: Int = 640): ImageBitmap {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 1, com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M))
    val img = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_RGB)
    for (y in 0 until size) for (x in 0 until size) img.setRGB(x, y, if (m[x, y]) 0x000000 else 0xFFFFFF)
    return img.toComposeImageBitmap()
}

/**
 * The family bar's preferences (the phone's Group settings): name and picture, the code, the people (tap one to open
 * their vault), adding someone, and inviting a member with their own phone.
 */
@Composable
fun FamilySheet(view: GroupView, onDismiss: () -> Unit) {
    val app = rememberApp()
    val c = Fd.c
    val toast = LocalToast.current
    val clipboard = LocalClipboardManager.current
    val g = view.group ?: return
    val me = view.data.me
    var name by remember(g.id) { mutableStateOf(g.name) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MemberEntity?>(null) }
    var qr by remember { mutableStateOf<String?>(null) }
    val invite = remember(g, me) {
        val share = g.shareIndex
        if (share == null || me == null) null
        else GroupInvite(g.id, g.code, share, g.name, me, view.data.account?.name ?: view.member(me)?.name ?: "")
    }
    fun open(id: String) { onDismiss(); app.go(Page.Person(id)) }
    fun role(key: String?) = when { key == null -> null; key == g.creator -> "Admin · made the group"; key in g.adminKeys -> "Admin"; else -> null }

    FdSheet(onDismiss) {
        Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                GroupPic(g, 64)
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = fdText(18f, FontWeight.Bold))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Code ${g.code}", style = fdText(13f, color = c.fg.copy(alpha = .55f), spacing = .06f))
                        FdIconButton("copy", { clipboard.setText(AnnotatedString(g.code)); toast.show("Code copied") }, size = 30.dp, iconSize = 15.dp,
                            tint = c.muted, description = "Copy code")
                    }
                }
            }

            // ---- name and picture ----
            Label("Name and picture")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FdInput(name, { name = it }, Modifier.weight(1f), placeholder = "Group name")
                FdButton("Save", {
                    app.save(toast, "Saved", build = { DeskOps.op(DeskOps.GROUP).put("group", JSONObject().put("id", g.id).put("name", name.trim())) }, close = {})
                }, Modifier.width(92.dp), kind = BtnKind.Ghost, enabled = name.isNotBlank() && name.trim() != g.name && !app.saving)
            }
            Row(Modifier.padding(top = 10.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FdChip("Choose a photo…", false, {
                    pickPhoto("Choose the group's picture")?.let { bytes ->
                        app.save(toast, "Picture changed", build = {
                            DeskOps.op(DeskOps.GROUP).put("group", JSONObject().put("id", g.id)).put("photo", Desk.upload(bytes))
                        }, close = {})
                    }
                }, leading = { FdIcon("image", 15.dp, tint = c.fg) })
                if (g.photoFile != null) FdChip("Use the illustration", false, {
                    app.save(toast, "Picture changed", build = { DeskOps.op(DeskOps.GROUP).put("group", JSONObject().put("id", g.id)).put("photo", JSONObject.NULL) }, close = {})
                })
            }

            // ---- people ----
            Label("People")
            val peers = g.peerList
            if (peers.isNotEmpty()) {
                val notYet = peers.filter { !it.synced }
                Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FdIcon(if (notYet.isEmpty()) "check" else "refresh", 16.dp, tint = if (notYet.isEmpty()) c.accent else c.muted)
                    Text(if (notYet.isEmpty()) "Everyone is synced." else "${notYet.joinToString(", ") { it.name.ifBlank { "Someone" } }} ${if (notYet.size == 1) "isn't" else "aren't"} synced yet.",
                        style = fdText(13f, FontWeight.Medium))
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp))) {
                var first = true
                fun docs(id: String?) = view.count(id ?: "").let { "$it document${if (it == 1) "" else "s"}" }
                @Composable fun row(m: MemberEntity, sub: String, status: String? = null) {
                    PersonLine(m, sub, status, first, onClick = { open(m.id) }, onEdit = if (view.canEdit(m)) ({ editing = m }) else null); first = false
                }
                view.members.firstOrNull { it.shared }?.let { row(it, "Shared by everyone · ${docs(it.id)}") }
                view.members.firstOrNull { it.isSelf }?.let { row(it, listOfNotNull("You", role(it.id), docs(it.id)).joinToString(" · ")) }
                view.members.filter { !it.isSelf && !it.shared && it.origin == null }.forEach { row(it, listOfNotNull(it.relation.ifBlank { null }, docs(it.id)).joinToString(" · ")) }
                peers.forEach { p ->
                    val m = view.members.firstOrNull { it.origin == p.key && !it.shared } ?: return@forEach
                    row(m, listOfNotNull(role(p.key), docs(m.id)).joinToString(" · "), when {
                        p.at == 0L -> "Not synced yet"
                        p.sees == false -> "Hasn't added you yet"
                        else -> "Synced"
                    })
                }
            }
            Spacer(Modifier.height(10.dp))
            FdButton("Add a person", { adding = true }, kind = BtnKind.Ghost, icon = "plus")

            // ---- inviting ----
            Label("Invite someone with their own phone", Modifier.padding(top = 18.dp))
            if (invite == null) Text("Invite the first person from the phone (Group settings › Invite): it sets up the group's sharing. After that you can invite from here too.",
                style = fdText(12.5f, color = c.muted))
            else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FdButton("Copy invite", { clipboard.setText(AnnotatedString(invite.message())); toast.show("Invite copied: send it privately") }, Modifier.weight(1f), icon = "copy")
                    FdButton("Show QR code", { qr = invite.link() }, Modifier.weight(1f), kind = BtnKind.Ghost, icon = "scan")
                }
                Text("They open it on their phone (or scan the code) and send theirs back. Send it privately: it carries the group's code.",
                    style = fdText(12f, color = c.muted), modifier = Modifier.padding(top = 8.dp))
            }
            Row(Modifier.padding(top = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FdIcon("lock", 14.dp, tint = c.muted)
                Text("Admins, removing members and the backup choice are on the phone. Changes made here reach your phone within seconds while Lararium is open there.",
                    style = fdText(11.5f, color = c.muted))
            }
        }
    }
    if (adding) PersonEditSheet(view, null, g.id) { adding = false }
    editing?.let { m -> PersonEditSheet(view, m, m.groupId) { editing = null } }
    qr?.let { link ->
        FdSheet({ qr = null }) {
            SheetTitle("Scan to join ${g.name}", "Point their phone's camera at it. Only for someone right next to you: it carries the group's code.")
            val img = remember(link) { qrImage(link) }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(img, "Invite QR code", Modifier.size(280.dp).clip(RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color.White).padding(10.dp))
            }
            Spacer(Modifier.height(16.dp))
            FdButton("Done", { qr = null }, kind = BtnKind.Ghost)
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = fdText(11f, FontWeight.SemiBold, Fd.c.muted, spacing = .08f), modifier = modifier.padding(bottom = 8.dp))
}

@Composable
private fun PersonLine(m: MemberEntity, subtitle: String, status: String?, first: Boolean, onClick: () -> Unit, onEdit: (() -> Unit)?) {
    val c = Fd.c
    Column {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FdAvatar(m.name, 40.dp, photoFile = m.photoFile, character = m.avatar, shared = m.shared)
            Column(Modifier.weight(1f)) {
                Text(m.name, style = fdText(14f, FontWeight.SemiBold))
                Text(subtitle, style = fdText(12f, color = c.muted))
                status?.let { Text(it, style = fdText(11.5f, color = if (it == "Synced") c.accent else c.muted)) }
            }
            onEdit?.let { FdIconButton("edit", it, size = 34.dp, iconSize = 16.dp, tint = c.muted, description = "Edit ${m.name}") }
            FdIcon("chev", 16.dp, tint = c.muted)
        }
    }
}
