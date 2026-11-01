package com.familydocs.app.ui.security

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familydocs.app.data.DocumentEntity
import com.familydocs.app.data.MemberEntity
import com.familydocs.app.domain.DocTypes
import com.familydocs.app.ui.components.LocalContainer
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdAvatar
import com.familydocs.app.ui.ds.FdIcon
import com.familydocs.app.ui.ds.FdIconButton
import com.familydocs.app.ui.ds.FdSheet
import com.familydocs.app.ui.ds.SheetTitle
import com.familydocs.app.ui.ds.fdText

/** Everyone's emergency cards in the group shown on Home (yours first), with something filled in. */
fun emergencyCards(members: List<MemberEntity>, docs: List<DocumentEntity>): List<Pair<MemberEntity, DocumentEntity>> =
    docs.filter { d -> d.eventId == null && !d.archived && DocTypes.forName(d.name).key == "emergency" && d.fields.any { it.value.isNotBlank() } }
        .mapNotNull { d -> members.firstOrNull { it.id == d.memberId }?.let { it to d } }
        .sortedBy { (m, _) -> if (m.isSelf) 0 else 1 }

/**
 * The lock screen's "Emergency info": blood group, allergies, medication, who to call… readable by whoever is helping,
 * without the passcode. Only the emergency cards: everything else stays locked. Off in Settings › Security.
 */
@Composable
fun EmergencyButton(modifier: Modifier = Modifier) {
    val app = LocalContainer.current
    val c = Fd.c
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val members by app.repo.members.collectAsStateWithLifecycle(emptyList())
    val docs by app.repo.allDocuments.collectAsStateWithLifecycle(emptyList())
    val cards = remember(members, docs) { emergencyCards(members, docs) }
    var open by remember { mutableStateOf(false) }
    if (!settings.emergencyOnLock || cards.isEmpty()) return
    Row(modifier.clip(RoundedCornerShape(99.dp)).background(c.danger.copy(alpha = .12f)).clickable { open = true }.padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        FdIcon("medical", 16.dp, tint = c.danger)
        Text("Emergency info", style = fdText(13f, FontWeight.SemiBold, c.danger))
    }
    if (open) EmergencySheet(cards) { open = false }
}

@Composable
private fun EmergencySheet(cards: List<Pair<MemberEntity, DocumentEntity>>, onDismiss: () -> Unit) {
    val app = LocalContainer.current
    val c = Fd.c
    val context = LocalContext.current
    FdSheet(onDismiss) {
        SheetTitle("Emergency info", "For anyone helping. Everything else in Lararium stays locked.")
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            cards.forEach { (m, d) ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FdAvatar(m.name, 38.dp, photoFile = m.photoFile, character = m.avatar)
                        Text(m.name, style = fdText(17f, FontWeight.Bold))
                    }
                    d.fields.filter { it.value.isNotBlank() }.forEach { f ->
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.label, style = fdText(11.5f, color = c.muted))
                                Text(f.value, style = fdText(16f, FontWeight.SemiBold))
                            }
                            // a phone number: call it straight away
                            val number = f.value.filter { it.isDigit() || it == '+' }
                            if (Regex("phone|mobile|contact|τηλ", RegexOption.IGNORE_CASE).containsMatchIn(f.label) && number.count { it.isDigit() } >= 6)
                                FdIconButton("phone", {
                                    app.appLock.expectExternalActivity()
                                    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                }, size = 40.dp, iconSize = 19.dp, tint = c.primary, description = "Call ${f.value}")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
