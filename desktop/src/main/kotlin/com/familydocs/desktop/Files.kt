package com.familydocs.desktop

import androidx.compose.ui.graphics.ImageBitmap
import com.familydocs.app.backup.BackupCrypto
import com.familydocs.app.backup.DriveClient
import com.familydocs.app.ui.components.decodeScaled

/**
 * The vault's files (scans, PDFs, photos), fetched from Drive only when something shows them, decrypted in memory and
 * kept there for the session: nothing decrypted is ever written to the Mac's disk (except a file you choose to save).
 *
 * File ids name where a file lives:
 *  - "b:<drive id>": in your own encrypted backup (opened with your backup key)
 *  - "s:<group id>:<drive id>": in a group member's share (public link, opened with the group's code)
 *  - "m:<name>": already here (e.g. an event photo that came inside the backup's events file)
 */
object Files {
    @Volatile var drive: DriveClient? = null
    @Volatile var backupKey: ByteArray? = null
    val groupKeys = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val inline = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    // decrypted bytes, most recently used last; about 400 MB at most
    private val bytes = object : LinkedHashMap<String, ByteArray>(64, .75f, true) {}
    private var total = 0L
    private const val MAX = 400L * 1024 * 1024
    private val images = object : LinkedHashMap<String, ImageBitmap>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > 300
    }

    fun putInline(id: String, data: ByteArray) { inline[id] = data }

    /** Everything forgotten (signing out, or before loading again). */
    fun clear() {
        synchronized(bytes) { bytes.clear(); total = 0 }
        synchronized(images) { images.clear() }
        inline.clear(); groupKeys.clear(); backupKey = null; drive = null
    }

    /** The decrypted file, downloading it the first time. Slow: off the main thread. Null if it can't be had. */
    fun get(id: String?): ByteArray? {
        id ?: return null
        inline[id.removePrefix("m:")]?.let { if (id.startsWith("m:")) return it }
        synchronized(bytes) { bytes[id] }?.let { return it }
        val data = runCatching {
            when {
                id.startsWith("b:") -> BackupCrypto.open(drive!!.download(id.removePrefix("b:")), backupKey!!)
                id.startsWith("s:") -> {
                    val (gid, driveId) = id.removePrefix("s:").split(':', limit = 2)
                    BackupCrypto.open(DriveClient.publicDownload(driveId), groupKeys.getValue(gid))
                }
                else -> null
            }
        }.getOrNull() ?: return null
        synchronized(bytes) {
            bytes[id] = data; total += data.size
            val it = bytes.entries.iterator()
            while (total > MAX && it.hasNext()) { val e = it.next(); if (e.key == id) continue; total -= e.value.size; it.remove() }
        }
        return data
    }

    fun cachedImage(id: String?, maxPx: Int): ImageBitmap? = id?.let { synchronized(images) { images["$it@$maxPx"] } }

    fun image(id: String, maxPx: Int): ImageBitmap? {
        cachedImage(id, maxPx)?.let { return it }
        val img = get(id)?.let { decodeScaled(it, maxPx) } ?: return null
        synchronized(images) { images["$id@$maxPx"] = img }
        return img
    }
}
