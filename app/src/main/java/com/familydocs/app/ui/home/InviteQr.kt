package com.familydocs.app.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.familydocs.app.domain.GroupInvite
import com.familydocs.app.ui.ds.Fd
import com.familydocs.app.ui.ds.FdButton
import com.familydocs.app.ui.ds.FdSheet
import com.familydocs.app.ui.ds.SheetTitle
import com.familydocs.app.ui.ds.fdText
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** [text] as a QR code: black on white (whatever the theme), so every camera reads it. */
fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

/**
 * Your invite as a QR code, for someone right next to you: their phone's camera opens the link, and Lararium joins
 * the group (or, if they're already in it, adds you on their phone).
 */
@Composable
fun InviteQrSheet(invite: GroupInvite, groupName: String, onDismiss: () -> Unit) {
    val c = Fd.c
    val image = remember(invite) { qrBitmap(invite.link()).asImageBitmap() }
    FdSheet(onDismiss) {
        SheetTitle("Scan to join $groupName", "Point their phone's camera at this. The link opens Lararium on their phone.")
        Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
            Image(image, "Invite to $groupName as a QR code", Modifier.size(264.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(14.dp))
        }
        Text("Already in $groupName? Scanning it adds you on their phone, so you're synced.", style = fdText(12.5f, color = c.muted),
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
        FdButton("Done", onDismiss, Modifier.padding(top = 16.dp))
        Spacer(Modifier.height(10.dp))
    }
}
