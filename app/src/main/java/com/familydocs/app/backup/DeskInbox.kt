package com.familydocs.app.backup

import android.content.Context
import com.familydocs.app.data.Attachment
import com.familydocs.app.data.FileStore
import com.familydocs.app.data.MemberEntity
import com.familydocs.app.data.VaultDao
import com.familydocs.app.data.VaultRepository
import com.familydocs.app.domain.DeskOps
import com.familydocs.app.domain.jsonObjects
import com.familydocs.app.domain.strOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Changes made in the desktop app ([DeskOps]), taken over by this phone: looked at every few seconds while the app is
 * open (a 64-byte peek, like the group shares), and in the background with the other Drive work. Each op is applied
 * once; this phone's backup then carries it, and the op is listed as done for the desktop.
 */
class DeskInbox(private val context: Context, private val repo: VaultRepository, private val dao: VaultDao,
                private val files: FileStore, private val keys: BackupKeyStore) {
    private val prefs = context.getSharedPreferences("desk_inbox", Context.MODE_PRIVATE)
    private val running = Mutex()

    /** The changes file's Drive id, once a desktop has made one. */
    private val fileId: String? get() = prefs.getString("id", null)
    /** Whether a desktop's changes file is known here yet (until then it's looked for now and then). */
    val known: Boolean get() = fileId != null

    /** Looks for the desktop's changes file (with Google access); quick when it's already known. */
    suspend fun find(drive: DriveClient, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!force && fileId != null) return@withContext
        if (!force && System.currentTimeMillis() - prefs.getLong("looked", 0L) < 2 * 60_000L) return@withContext
        prefs.edit().putLong("looked", System.currentTimeMillis()).apply()
        drive.findByProp(DriveBackup.P_DOC_FILE, DeskOps.TAG)?.let { prefs.edit().putString("id", it.id).apply() }
    }

    /** Takes over new desktop changes. Returns how many were applied. Needs no Google access. */
    suspend fun poll(): Int = running.withLock { withContext(Dispatchers.IO) {
        val id = fileId ?: return@withContext 0
        val key = keys.current()?.key ?: return@withContext 0
        val (head, complete) = runCatching { DriveClient.publicPeek(id) }.getOrElse {
            // removed (signed out on the desktop, or a new file): look for it again next time
            if (it.message?.contains("isn't available") == true) prefs.edit().remove("id").apply()
            return@withContext 0
        }
        val mark = head.joinToString("") { "%02x".format(it) }
        if (prefs.getString("mark", null) == mark) return@withContext 0
        val sealed = if (complete) head else DriveClient.publicDownload(id)
        val ops = DeskOps.unpack(String(BackupCrypto.open(sealed, key)))
        val done = applied()
        var n = 0; var retry = false
        ops.sortedBy { it.optLong("at") }.forEach { op ->
            val opId = op.optString("id").ifEmpty { return@forEach }
            if (opId in done) return@forEach
            // one that fails (a person since removed, a file gone) is still marked, so it can't block the others;
            // only a lost connection leaves it for the next look
            val r = runCatching { apply(op, key) }
            if (r.exceptionOrNull() is java.io.IOException) { retry = true; return@forEach }
            if (r.getOrNull() == true) n++
            done[opId] = System.currentTimeMillis()
        }
        saveApplied(done)
        if (!retry) prefs.edit().putString("mark", mark).apply()
        if (n > 0) BackupWorker.afterDesk(context)
        n
    } }

    /** After a backup that started at [backupStarted]: everything applied before it is in Drive, so it's listed as done. */
    suspend fun ack(drive: DriveClient, backupStarted: Long) = withContext(Dispatchers.IO) {
        val done = applied()
        val ready = done.filterValues { it < backupStarted }.keys
        if (ready.isEmpty() || prefs.getString("acked", null) == ready.sorted().joinToString(",")) return@withContext
        drive.appDataWrite(DeskOps.ACK_FILE, DeskOps.ackJson(ready).toByteArray())
        prefs.edit().putString("acked", ready.sorted().joinToString(",")).apply()
    }

    /** Signed out: a new account starts from nothing. */
    fun forget() = prefs.edit().clear().apply()

    // ---------------- applying ----------------

    /** One op from the desktop. False when it was older than what's here already (nothing changed). */
    private suspend fun apply(op: JSONObject, key: ByteArray): Boolean {
        val at = op.optLong("at")
        fun fetch(driveId: String): ByteArray = BackupCrypto.open(DriveClient.publicDownload(driveId), key)
        when (op.optString("kind")) {
            DeskOps.DOC -> {
                val j = op.getJSONObject("doc")
                val old = dao.documentNow(j.getString("id"))
                if (old != null && old.updatedAt >= at) return false
                dao.memberNow(j.getString("member")) ?: return false
                var row = DeskOps.docFrom(j, old)
                val gone = mutableListOf<String>()
                if (op.has("files")) {
                    val atts = op.optJSONArray("files").jsonObjects().map { f -> Attachment(files.put(fetch(f.getString("f"))), f.optString("mime", "image/jpeg")) }
                    gone += old?.attachments?.map { it.id }.orEmpty()
                    row = row.copy(fileId = atts.firstOrNull()?.id, fileMime = atts.firstOrNull()?.mime, moreFiles = Attachment.encode(atts.drop(1)))
                }
                if (op.has("picture")) {
                    val p = op.strOrNull("picture")
                    old?.pictureFile?.let { gone += it }
                    row = row.copy(pictureFile = p?.let { files.put(fetch(it)) })
                }
                repo.saveDocument(row, row.fields)
                gone.forEach { files.delete(it) }
            }
            DeskOps.DEL_DOC -> {
                val d = dao.documentNow(op.getString("doc")) ?: return false
                if (d.updatedAt > at) return false
                repo.deleteDocument(d.id)
            }
            DeskOps.ORDER -> {
                val ids = op.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                if (ids.isEmpty()) return false
                repo.reorderDocuments(ids)
            }
            DeskOps.EVENT -> {
                val j = op.getJSONObject("event")
                val old = repo.eventNow(j.getString("id"))
                if (old != null && old.updatedAt >= at) return false
                var row = DeskOps.eventFrom(j, old)
                if (op.has("photo")) {
                    val p = op.strOrNull("photo")
                    old?.photoFile?.let { files.delete(it) }
                    row = row.copy(photoFile = p?.let { files.put(fetch(it)) })
                }
                repo.saveEvent(row)
                runCatching { com.familydocs.app.events.EventReminders.schedule(context, row) }
            }
            DeskOps.DEL_EVENT -> {
                val e = repo.eventNow(op.getString("event")) ?: return false
                if (e.received) return false // only the organiser deletes an event
                repo.deleteEvent(e.id)
            }
            DeskOps.MEMBER -> {
                val j = op.getJSONObject("member")
                val id = j.getString("id")
                val old = dao.memberNow(id)
                val photo = if (op.has("photo")) op.strOrNull("photo")?.let { fetch(it) } else null
                if (old == null) {
                    // someone new, added on the desktop: same id as there, so both show the same person
                    val g = j.strOrNull("group")?.takeIf { dao.groupNow(it) != null } ?: return false
                    dao.upsertMember(MemberEntity(id, j.getString("name"), j.optString("relation"), photo?.let { files.put(it) },
                        avatar = j.strOrNull("avatar") ?: repo.nextCharacter(), groupId = g))
                    repo.onChanged()
                } else repo.saveMember(id, j.optString("name", old.name).ifBlank { old.name }, j.optString("relation", old.relation), photo,
                    keepPhoto = !op.has("photo"), avatar = j.strOrNull("avatar") ?: old.avatar)
            }
            DeskOps.DEL_MEMBER -> repo.deleteMember(op.getString("member"))
            DeskOps.GROUP -> {
                val j = op.getJSONObject("group")
                val gid = j.getString("id")
                dao.groupNow(gid) ?: return false
                j.strOrNull("name")?.let { repo.renameGroup(gid, it) }
                if (op.has("photo")) {
                    val p = op.strOrNull("photo")
                    if (p != null) repo.setGroupPicture(gid, null, fetch(p)) else repo.setGroupPicture(gid, "default", null)
                }
            }
            else -> return false
        }
        return true
    }

    private fun applied(): MutableMap<String, Long> = runCatching {
        val o = JSONObject(prefs.getString("applied", null) ?: return mutableMapOf())
        o.keys().asSequence().associateWith { o.optLong(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun saveApplied(m: Map<String, Long>) {
        // the newest few hundred are plenty: the desktop drops ops once they're listed as done
        val keep = m.entries.sortedByDescending { it.value }.take(400)
        prefs.edit().putString("applied", JSONObject().apply { keep.forEach { (k, v) -> put(k, v) } }.toString()).apply()
    }
}
