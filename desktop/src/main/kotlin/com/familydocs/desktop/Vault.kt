package com.familydocs.desktop

import com.familydocs.app.backup.BackupCrypto
import com.familydocs.app.backup.DriveClient
import com.familydocs.app.data.AccountEntity
import com.familydocs.app.data.Attachment
import com.familydocs.app.data.DisplayMode
import com.familydocs.app.data.DocumentEntity
import com.familydocs.app.data.EventEntity
import com.familydocs.app.data.GroupEntity
import com.familydocs.app.data.MemberEntity
import com.familydocs.app.domain.GroupCodes
import com.familydocs.app.domain.GroupInvite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Everything the Mac shows: read from your encrypted backup and your group members' shares, as the phone would. */
data class VaultData(
    val account: AccountEntity?,
    /** Your own key in groups (your "you" member id). */
    val me: String?,
    val members: List<MemberEntity>,
    val docs: List<DocumentEntity>,
    val events: List<EventEntity>,
    val groups: List<GroupEntity>,
    /** The phone's passcode check (salt + hash), so the Mac unlocks with the same passcode. */
    val passcode: String?,
    val loadedAt: Long,
    /** Shares that couldn't be read just now ("Jane: …"). */
    val problems: List<String>,
)

/**
 * Reads the vault: the backup in your Drive (you, Common, your events; made by the phone's Drive backup) and every group
 * member's encrypted share (their documents, the Common documents they added, events shared with you). Only small
 * files are read here (details, lists); scans and photos are fetched when they're shown ([Files]).
 */
object Vault {
    private const val P_ROOT = "fd_root"; private const val P_KIND = "fd_kind"; private const val P_MEMBER = "fd_member"
    private const val P_DOC = "fd_doc"; private const val P_DOC_FILE = "fd_file"
    private const val KEY_FILE = "family-docs-backup-key.json"; private const val PASSCODE_FILE = "lararium-passcode.fdenc"

    class NoBackup : Exception("This Google account has no Lararium backup yet. Turn on Google Drive backup in the phone app (Settings › Backup), then try again.")

    suspend fun load(onProgress: (String) -> Unit = {}): VaultData = withContext(Dispatchers.IO) {
        val token = Google.token()
        val drive = DriveClient(token)
        Files.drive = drive
        onProgress("Opening your backup…")
        val root = drive.findByProp(P_ROOT, "1") ?: throw NoBackup()
        val keyJson = JSONObject(String(drive.appDataRead(KEY_FILE) ?: throw NoBackup()))
        val key = Base64.getDecoder().decode(keyJson.getString("key"))
        val salt = Base64.getDecoder().decode(keyJson.getString("id"))
        Files.backupKey = key
        fun open(b: ByteArray) = BackupCrypto.open(b, key)
        val passcode = runCatching { drive.appDataRead(PASSCODE_FILE)?.let { String(open(it)) } }.getOrNull()
        val rootChildren = drive.children(root.id)
        val indexFile = rootChildren.firstOrNull { it.props[P_KIND] == "index" } ?: throw NoBackup()
        val index = JSONObject(String(open(drive.download(indexFile.id))))

        val account = index.optJSONObject("account")?.let { a ->
            AccountEntity(name = a.getString("name"), countryCode = a.optString("cc"), phone = a.optString("phone"), backupEmail = a.optString("email"),
                photoFile = rootChildren.firstOrNull { it.props[P_KIND] == "account-photo" }?.let { ref(it) }, googleLinked = true,
                countries = a.optString("countries", "IN,GR"))
        }
        val groups = index.optJSONArray("groups").objs().map { g ->
            GroupEntity(g.getString("id"), g.getString("name"), g.getString("code"), g.optLong("created"), backupAll = g.optBoolean("all"),
                shareFolder = g.str("shareFolder"), shareIndex = g.str("shareIndex"), peers = g.str("peers"), pic = g.str("pic"),
                photoFile = if (g.optBoolean("photo")) drive.findByProp(P_DOC_FILE, "group:${g.getString("id")}:photo")?.let { ref(it) } else null,
                creator = g.str("creator"), admins = g.str("admins"), adminsAt = g.optLong("adminsAt"))
        }
        val order = index.optJSONArray("documents").objs().filter { !it.isNull("order") }.associate { it.getString("id") to it.getInt("order") }

        // ---- you, the Common vaults (and people made on the phone), with their documents ----
        // (other members come fresh from their own shares below, not from this backup's copies)
        val memberJson = index.getJSONArray("members").objs().filter { it.str("origin") == null }
        val gate = Semaphore(8)
        val members = mutableListOf<MemberEntity>(); val docs = java.util.Collections.synchronizedList(mutableListOf<DocumentEntity>())
        onProgress("Reading your documents…")
        coroutineScope {
            memberJson.map { mj ->
                async(Dispatchers.IO) {
                    val id = mj.getString("id")
                    val folder = gate.withPermit { rootChildren.firstOrNull { it.props[P_MEMBER] == id } ?: drive.findByProp(P_MEMBER, id) }
                    val inside = folder?.let { f -> gate.withPermit { drive.children(f.id) } }.orEmpty()
                    val m = MemberEntity(id, mj.getString("name"), mj.optString("relation"), inside.firstOrNull { it.props[P_KIND] == "photo" }?.let { ref(it) },
                        mj.optBoolean("self"), mj.optLong("created"), mj.optBoolean("shared"), mj.str("avatar"), groupId = mj.str("group"))
                    synchronized(members) { members += m }
                    inside.filter { it.mime == DriveClient.FOLDER && it.props[P_DOC] != null }.map { df ->
                        async(Dispatchers.IO) {
                            gate.withPermit {
                                runCatching {
                                    val parts = drive.children(df.id)
                                    val details = parts.firstOrNull { it.props[P_KIND] == "details" } ?: return@runCatching
                                    val dj = JSONObject(String(open(drive.download(details.id))))
                                    if (dj.str("origin") != null) return@runCatching // a member's document: read from their share
                                    val mimes = dj.optJSONArray("more")
                                    val more = parts.filter { it.props[P_KIND] == "extra" }.sortedBy { it.props["n"]?.toIntOrNull() ?: 0 }.mapIndexed { n, f ->
                                        Attachment(ref(f), mimes?.optString(n)?.takeIf { it.isNotEmpty() } ?: if (f.props["ext"] == "pdf") "application/pdf" else "image/jpeg")
                                    }
                                    docs += DocumentEntity(
                                        id = dj.getString("id"), memberId = dj.getString("member"), name = dj.getString("name"),
                                        fileId = parts.firstOrNull { it.props[P_KIND] == "original" }?.let { ref(it) }, fileMime = dj.str("mime"),
                                        display = runCatching { DisplayMode.valueOf(dj.getString("display")) }.getOrDefault(DisplayMode.ICON),
                                        pictureFile = parts.firstOrNull { it.props[P_KIND] == "picture" }?.let { ref(it) },
                                        fieldsJson = dj.optString("fields", "[]"), rawText = dj.optString("text"), readByOcr = dj.optBoolean("ocr"),
                                        country = dj.str("country"), createdAt = dj.optLong("created"), updatedAt = dj.optLong("updated"),
                                        eventId = dj.str("event"), showFlag = if (!dj.has("flag") || dj.isNull("flag")) null else dj.getBoolean("flag"),
                                        moreFiles = Attachment.encode(more), sortOrder = order[dj.getString("id")],
                                        archivedAt = if (!dj.has("archived") || dj.isNull("archived")) null else dj.getLong("archived"),
                                    )
                                }
                            }
                        }
                    }.awaitAll()
                }
            }.awaitAll()
        }
        val me = members.firstOrNull { it.isSelf }?.id

        // ---- your events (from the backup) ----
        val events = mutableListOf<EventEntity>()
        rootChildren.firstOrNull { it.props[P_KIND] == "events" }?.let { f ->
            JSONArray(String(open(drive.download(f.id)))).objs().filter { !it.getString("id").startsWith("s:") }.forEach { j ->
                // (named by its contents: a new picture is a new name, so it shows instead of the remembered one)
                val photo = if (j.isNull("photo")) null else Base64.getDecoder().decode(j.getString("photo")).let { b ->
                    ("e-" + j.getString("id") + "-" + Integer.toHexString(b.contentHashCode())).also { Files.putInline(it, b) }
                }
                events += EventEntity(id = j.getString("id"), name = j.getString("name"), art = j.optString("art", "trip"), photoFile = photo?.let { "m:$it" },
                    startDay = j.getString("start"), endDay = j.str("end"), people = j.optString("people"), visibleTo = j.optString("visible"),
                    pins = j.optString("pins"), remindDays = j.optInt("remind", -1), notes = j.optString("notes"), createdAt = j.optLong("created"), groupId = j.str("group"),
                    updatedAt = j.optLong("at"))
            }
        }

        // ---- everyone else: their shares ----
        val problems = mutableListOf<String>()
        groups.forEach { g ->
            val peers = g.peerList
            if (me == null || (peers.isEmpty() && g.shareIndex == null)) return@forEach
            val gkey = BackupCrypto.deriveKey(GroupCodes.normalize(g.code).toCharArray(), GroupInvite.salt(g.id))
            Files.groupKeys[g.id] = gkey
            val common = members.firstOrNull { it.shared && it.groupId == g.id }
            // (your own share too: the phone puts your changes there within seconds, the backup follows a few minutes later)
            val (shares, mine) = coroutineScope {
                val own = async(Dispatchers.IO) { g.shareIndex?.let { runCatching { JSONObject(String(BackupCrypto.open(DriveClient.publicDownload(it), gkey))) }.getOrNull() } }
                peers.map { p -> async(Dispatchers.IO) { p to runCatching { JSONObject(String(BackupCrypto.open(DriveClient.publicDownload(p.index), gkey))) } } }.awaitAll() to own.await()
            }
            onProgress("Reading ${g.name}…")
            val edits = mutableListOf<JSONObject>()
            mine?.let { j ->
                // your events as the phone last shared them: newer than the backup's copy (a new photo, a change just made)
                j.optJSONArray("events").objs().forEach { ev ->
                    val i = events.indexOfFirst { it.id == ev.getString("id") }.takeIf { it >= 0 } ?: return@forEach
                    val old = events[i]
                    if (ev.optLong("at") < old.updatedAt) return@forEach
                    fun person(o: JSONObject): String? = personRef(o, me, common?.id, g.id)
                    val fresh = eventFrom(ev, old.id, g.id, ::person, me)
                    events[i] = fresh.copy(visibleTo = old.visibleTo, remindDays = old.remindDays, editedBy = null,
                        photoFile = fresh.photoFile ?: old.photoFile.takeIf { ev.isNull("photo").not() })
                }
                // what you changed last in other people's events
                edits += j.optJSONArray("eventEdits").objs().map { it.put("_peer", me) }
            }
            shares.forEach { (p, r) ->
                val j = r.getOrElse { problems += "${p.name.ifBlank { "Someone" }}: ${it.message ?: "couldn't read their share"}"; return@forEach }
                val prefix = "s:${g.id}:${p.key}:"
                fun file(driveId: String?) = driveId?.let { "s:${g.id}:$it" }
                var selfRow: String? = null
                val memberIds = mutableMapOf<String, String>()
                j.optJSONArray("members").objs().forEach { m ->
                    when (m.optString("kind")) {
                        "common" -> common?.let { memberIds[m.getString("id")] = it.id }
                        "self" -> {
                            val id = prefix + m.getString("id")
                            memberIds[m.getString("id")] = id; selfRow = id
                            members += MemberEntity(id, m.getString("name"), "Group member", file(m.str("photo")), createdAt = 1L, avatar = m.str("avatar"), groupId = g.id, origin = p.key)
                        }
                    }
                }
                j.optJSONArray("documents").objs().forEach { d ->
                    val member = memberIds[d.getString("member")] ?: return@forEach
                    val atts = d.optJSONArray("files").objs().map { Attachment(file(it.getString("f"))!!, it.optString("mime", "image/jpeg")) }
                    docs += sharedDoc(d, prefix + d.getString("id"), member, atts, file(d.str("picture")), p.key, eventId = null)
                        .copy(sortOrder = if (d.isNull("order")) null else d.optInt("order"),
                            archivedAt = if (!d.has("archived") || d.isNull("archived")) null else d.getLong("archived"))
                }
                // their events that you can see
                fun person(o: JSONObject): String? = personRef(o, me, common?.id, g.id)
                j.optJSONArray("events").objs().forEach { ev ->
                    val visible = ev.optJSONArray("visible").objs()
                    if (visible.isNotEmpty() && visible.none { it.optString("k") == me }) return@forEach
                    events += eventFrom(ev, prefix + ev.getString("id"), g.id, ::person, me).copy(visibleTo = visible.mapNotNull(::person).joinToString(","))
                }
                edits += j.optJSONArray("eventEdits").objs().map { it.put("_peer", p.key) }
                j.optJSONArray("eventDocs").objs().forEach { d ->
                    val owner = d.optString("owner").ifEmpty { p.key }
                    val ev = if (owner == me) d.getString("event") else "s:${g.id}:$owner:${d.getString("event")}"
                    val member = (if (d.optString("kind") == "common") common?.id else selfRow) ?: return@forEach
                    val atts = d.optJSONArray("files").objs().map { Attachment(file(it.getString("f"))!!, it.optString("mime", "image/jpeg")) }
                    docs += sharedDoc(d, prefix + d.getString("id"), member, atts, file(d.str("picture")), p.key, eventId = ev)
                }
            }
            // changes someone made to an event (newest wins), as the phone does
            edits.forEach { ev ->
                val owner = ev.optString("owner").ifEmpty { return@forEach }
                val id = if (owner == me) ev.getString("id") else "s:${g.id}:$owner:${ev.getString("id")}"
                val i = events.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return@forEach
                if (ev.optLong("at") <= events[i].updatedAt) return@forEach
                fun person(o: JSONObject): String? = personRef(o, me, common?.id, g.id)
                events[i] = eventFrom(ev, id, g.id, ::person, me).copy(visibleTo = events[i].visibleTo, remindDays = events[i].remindDays)
            }
        }
        // documents of events you can't see (anymore) are left out
        val eventIds = events.map { it.id }.toSet()
        runCatching { Desk.load(drive, root.id, key, salt) }
        VaultData(account, me, members.sortedWith(compareBy({ !it.isSelf }, { it.shared }, { it.createdAt })),
            docs.filter { it.eventId == null || it.eventId in eventIds }, events.sortedBy { it.startDay }, groups, passcode, System.currentTimeMillis(), problems)
    }

    private fun sharedDoc(d: JSONObject, id: String, member: String, atts: List<Attachment>, picture: String?, origin: String, eventId: String?) = DocumentEntity(
        id = id, memberId = member, name = d.getString("name"), fileId = atts.firstOrNull()?.id, fileMime = atts.firstOrNull()?.mime,
        display = runCatching { DisplayMode.valueOf(d.getString("display")) }.getOrDefault(DisplayMode.ICON), pictureFile = picture,
        fieldsJson = d.optString("fields", "[]"), rawText = d.optString("text"), readByOcr = d.optBoolean("ocr"), country = d.str("country"),
        createdAt = d.optLong("created"), updatedAt = d.optLong("updated"), showFlag = if (!d.has("flag") || d.isNull("flag")) null else d.optBoolean("flag"),
        moreFiles = Attachment.encode(atts.drop(1)), origin = origin, eventId = eventId,
    )

    /** Someone in an event (going, can see it), as the share names them: you, Common, or another member. */
    private fun personRef(o: JSONObject, me: String?, common: String?, gid: String): String? = when (val k = o.optString("k")) {
        "" -> null; "common" -> common; else -> if (k == me) me else "s:$gid:$k:${o.optString("id")}"
    }

    private fun eventFrom(ev: JSONObject, id: String, gid: String, person: (JSONObject) -> String?, me: String?) = EventEntity(
        id = id, name = ev.getString("name"), art = ev.optString("art", "trip"), photoFile = ev.str("photo")?.let { "s:$gid:$it" },
        startDay = ev.getString("start"), endDay = ev.str("end"),
        people = ev.optJSONArray("people").objs().mapNotNull(person).joinToString(","),
        // pinned documents: yours by their own id, everyone else's as this Mac names their shared documents
        pins = ev.optJSONArray("pins").objs().mapNotNull { r -> val k = r.optString("k"); if (k.isEmpty()) null else if (k == me) r.optString("id") else "s:$gid:$k:${r.optString("id")}" }.joinToString(","),
        notes = ev.optString("notes"), createdAt = ev.optLong("created"), groupId = gid, updatedAt = ev.optLong("at"), editedBy = ev.str("by"),
    )

    /**
     * How a file in your backup is named here: its Drive id and a bit of its checksum, so a picture changed on the
     * phone (written over the same Drive file) is a new name, and the old one isn't shown from memory.
     */
    private fun ref(f: DriveClient.Item) = "b:" + f.id + if (f.md5.isNotEmpty()) "#" + f.md5.take(10) else ""

    private fun JSONArray?.objs(): List<JSONObject> = this?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    private fun JSONObject.str(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifEmpty { null }
}
