package com.familydocs.desktop

import com.familydocs.app.backup.BackupCrypto
import com.familydocs.app.backup.DriveClient
import com.familydocs.app.data.Attachment
import com.familydocs.app.domain.DeskOps
import com.familydocs.app.domain.jsonObjects
import com.familydocs.app.domain.strOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Your edits on this computer, on their way to the phone ([DeskOps] explains how). Kept in Drive, not on this computer:
 * opening Lararium on another computer shows the same waiting changes, and nothing is lost if the app quits.
 */
object Desk {
    private const val P_KIND = "fd_kind"; private const val P_DOC_FILE = "fd_file"; private const val OCTET = "application/octet-stream"
    private val lock = Mutex()

    /** Changes the phone hasn't taken over yet, oldest first. */
    @Volatile var pending: List<JSONObject> = emptyList(); private set
    private var drive: DriveClient? = null
    private var key: ByteArray? = null
    private var salt: ByteArray? = null
    private var root: String? = null
    private var folder: String? = null
    private var fileId: String? = null

    fun forget() { pending = emptyList(); drive = null; key = null; salt = null; root = null; folder = null; fileId = null }

    /**
     * With the vault just read: the changes still waiting (those the phone has listed as done are dropped, with their
     * uploaded files).
     */
    suspend fun load(drive: DriveClient, root: String, key: ByteArray, salt: ByteArray) = lock.withLock { withContext(Dispatchers.IO) {
        this@Desk.drive = drive; this@Desk.key = key; this@Desk.salt = salt; this@Desk.root = root
        val file = drive.findByProp(P_DOC_FILE, DeskOps.TAG)
        fileId = file?.id
        folder = file?.parents?.firstOrNull()
        val ops = file?.let { runCatching { DeskOps.unpack(String(BackupCrypto.open(drive.download(it.id), key))) }.getOrNull() }.orEmpty()
        val done = DeskOps.acked(drive.appDataRead(DeskOps.ACK_FILE)?.let { String(it) })
        val left = ops.filter { it.optString("id") !in done }
        if (left.size != ops.size) {
            // the phone has them: their uploads can go (the phone keeps its own copies)
            ops.filter { it.optString("id") in done }.flatMap { uploadsOf(it) }.forEach { runCatching { drive.trash(it) } }
            write(left)
        }
        pending = left
    } }

    /** Whether the phone has taken over any of the waiting changes (one small read): then it's worth reading the vault again. */
    suspend fun anyDone(): Boolean = withContext(Dispatchers.IO) {
        if (drive == null) return@withContext false
        fresh()
        val d = drive ?: return@withContext false
        val ids = pending.map { it.optString("id") }.toSet()
        ids.isNotEmpty() && DeskOps.acked(d.appDataRead(DeskOps.ACK_FILE)?.let { String(it) }).any { it in ids }
    }

    /** Adds a change: shown here straight away, on the phone within seconds while Lararium is open there. */
    suspend fun push(op: JSONObject) = lock.withLock { withContext(Dispatchers.IO) {
        fresh()
        val next = pending + op
        write(next)
        pending = next
    } }

    /**
     * A new picture or scan for a change: sealed with your backup key and put next to the changes file. Returns its
     * Drive id (what the op names) and keeps the bytes here, so it shows before the phone has it.
     */
    suspend fun upload(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        fresh()
        val d = drive ?: error("Not signed in")
        val sealed = BackupCrypto.seal(bytes, key!!, salt!!)
        val tag = "${DeskOps.FILE_KIND}:${java.util.UUID.randomUUID()}"
        val id = d.upsertFile(folder(), "${tag.substringAfter(':')}.fdenc", sealed, OCTET, mapOf(P_KIND to DeskOps.FILE_KIND, P_DOC_FILE to tag), null, P_DOC_FILE)
        d.shareWithLink(id)
        Files.putInline(local(id), bytes)
        id
    }

    /** How an uploaded file is named in the vault shown here (its bytes are already in memory). */
    fun local(driveId: String) = "u-$driveId"
    fun localRef(driveId: String?) = driveId?.let { "m:" + local(it) }

    /** Drive access (it lasts an hour; Google hands out a new one when needed). */
    private suspend fun fresh() { if (drive != null) drive = DriveClient(Google.token()) }

    private fun uploadsOf(op: JSONObject): List<String> =
        op.optJSONArray("files").jsonObjects().mapNotNull { it.strOrNull("f") } + listOfNotNull(op.strOrNull("picture"), op.strOrNull("photo"))

    private fun folder(): String {
        folder?.let { return it }
        val d = drive!!
        return d.folder("Desktop changes (encrypted)", root!!, mapOf("fd_desk" to "1"),
            "Changes made in Lararium on a computer, waiting for your phone. Encrypted; the phone takes them over and they disappear.")
            .also { folder = it }
    }

    private fun write(ops: List<JSONObject>) {
        val d = drive ?: error("Not signed in")
        val sealed = BackupCrypto.seal(DeskOps.pack(ops, Platform.device).toByteArray(), key!!, salt!!)
        fileId?.let { id -> if (runCatching { d.updateContent(id, sealed, OCTET) }.isSuccess) return }
        val id = d.upsertFile(folder(), DeskOps.FILE_NAME, sealed, OCTET, mapOf(P_KIND to DeskOps.TAG, P_DOC_FILE to DeskOps.TAG),
            "Changes made in Lararium on a computer, encrypted", P_DOC_FILE)
        d.shareWithLink(id)
        fileId = id
    }

    // ---------------- showing the waiting changes ----------------

    /** [d] as it will be once the phone has taken over every waiting change. */
    fun overlay(d: VaultData, ops: List<JSONObject> = pending): VaultData {
        if (ops.isEmpty()) return d
        val docs = d.docs.associateBy { it.id }.toMutableMap()
        val events = d.events.associateBy { it.id }.toMutableMap()
        val members = d.members.associateBy { it.id }.toMutableMap()
        val groups = d.groups.associateBy { it.id }.toMutableMap()
        var account = d.account
        ops.sortedBy { it.optLong("at") }.forEach { op ->
            val at = op.optLong("at")
            runCatching {
                when (op.optString("kind")) {
                    DeskOps.DOC -> {
                        val j = op.getJSONObject("doc")
                        val old = docs[j.getString("id")]
                        var row = DeskOps.docFrom(j, old).copy(updatedAt = maxOf(at, old?.updatedAt ?: 0L))
                        if (op.has("files")) {
                            val atts = op.optJSONArray("files").jsonObjects().map { Attachment(localRef(it.getString("f"))!!, it.optString("mime", "image/jpeg")) }
                            row = row.copy(fileId = atts.firstOrNull()?.id, fileMime = atts.firstOrNull()?.mime, moreFiles = Attachment.encode(atts.drop(1)))
                        }
                        if (op.has("picture")) row = row.copy(pictureFile = localRef(op.strOrNull("picture")))
                        docs[row.id] = row
                    }
                    DeskOps.DEL_DOC -> docs.remove(op.getString("doc"))
                    DeskOps.ORDER -> op.optJSONArray("ids")?.let { a -> (0 until a.length()).forEach { i -> docs[a.getString(i)]?.let { docs[it.id] = it.copy(sortOrder = i) } } }
                    DeskOps.EVENT -> {
                        val j = op.getJSONObject("event")
                        val old = events[j.getString("id")]
                        var row = DeskOps.eventFrom(j, old).copy(updatedAt = maxOf(at, old?.updatedAt ?: 0L), editedBy = null)
                        if (op.has("photo")) row = row.copy(photoFile = localRef(op.strOrNull("photo")))
                        events[row.id] = row
                    }
                    DeskOps.DEL_EVENT -> { val id = op.getString("event"); events.remove(id); docs.values.removeAll { it.eventId == id } }
                    DeskOps.MEMBER -> {
                        val j = op.getJSONObject("member")
                        val old = members[j.getString("id")]
                        val photo = if (op.has("photo")) localRef(op.strOrNull("photo")) else old?.photoFile
                        val row = old?.copy(name = j.optString("name", old.name).ifBlank { old.name }, relation = j.optString("relation", old.relation),
                            avatar = j.strOrNull("avatar") ?: old.avatar, photoFile = photo)
                            ?: com.familydocs.app.data.MemberEntity(j.getString("id"), j.getString("name"), j.optString("relation"), photo,
                                avatar = j.strOrNull("avatar"), groupId = j.strOrNull("group"), createdAt = at)
                        members[row.id] = row
                        if (row.isSelf) account = account?.copy(name = row.name, photoFile = row.photoFile)
                    }
                    DeskOps.DEL_MEMBER -> { val id = op.getString("member"); members.remove(id); docs.values.removeAll { it.memberId == id } }
                    DeskOps.GROUP -> {
                        val j = op.getJSONObject("group")
                        val g = groups[j.getString("id")] ?: return@runCatching
                        var row = j.strOrNull("name")?.let { g.copy(name = it) } ?: g
                        if (op.has("photo")) row = row.copy(photoFile = localRef(op.strOrNull("photo")), pic = if (op.strOrNull("photo") == null) "default" else row.pic)
                        groups[row.id] = row
                    }
                }
            }
        }
        val eventIds = events.keys
        return d.copy(account = account, members = members.values.sortedWith(compareBy({ !it.isSelf }, { it.shared }, { it.createdAt })),
            docs = docs.values.filter { it.eventId == null || it.eventId in eventIds }, events = events.values.sortedBy { it.startDay },
            groups = d.groups.map { groups[it.id] ?: it })
    }
}
