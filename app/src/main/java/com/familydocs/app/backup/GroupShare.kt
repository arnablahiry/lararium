package com.familydocs.app.backup

import android.content.Context
import com.familydocs.app.data.AdminEdits
import com.familydocs.app.data.Attachment
import com.familydocs.app.data.DisplayMode
import com.familydocs.app.data.DocumentEntity
import com.familydocs.app.data.EventEntity
import com.familydocs.app.data.FileStore
import com.familydocs.app.data.GroupEntity
import com.familydocs.app.data.MemberEntity
import com.familydocs.app.data.PasswordEntity
import com.familydocs.app.data.Peer
import com.familydocs.app.data.VaultDao
import com.familydocs.app.domain.GroupCodes
import com.familydocs.app.domain.GroupInvite
import com.familydocs.app.domain.NotesMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sharing a group between accounts, with no server: every member's app keeps an encrypted "share" in its own Drive
 * and reads the others'.
 *
 *   My Drive › Lararium › <Group> › Shared with <Group> (encrypted)/
 *       index.fdx          ← people, documents, Common logins (JSON), sealed
 *       <file id>.fdx      ← each scan / page / picture, sealed
 *
 * What goes in: you, the Common vault and the people you added to the group, with the documents added on this phone.
 * Your own logins never do; Common logins do. Things other members shared with you are never passed on.
 * Sealing: AES-256-GCM ([BackupCrypto]) with a key derived from the group code (PBKDF2, salt from the group id), so
 * only someone with the code can open it. The files are "anyone with the link" because the app's Drive access
 * (drive.file) can't open files other accounts share by email; the file ids travel only inside invites.
 * What others share shows up read-only here, marked with their key ([MemberEntity.origin] / [DocumentEntity.origin]).
 *
 * Admins: whoever made the group is its admin and can make others admins. An admin can change another member's documents
 * and profile, or remove them from the group. Each member's things live in their own vault, so an admin's change is an
 * "op" in the admin's share addressed to that member; their phone applies it to its own vault (telling them who changed
 * what), lists it as applied, and the admin's phone then drops it. Until then the admin sees the change on their phone.
 */
class GroupShare(private val context: Context, private val dao: VaultDao, private val files: FileStore,
                 private val crypto: com.familydocs.app.security.CryptoManager) : AdminEdits {
    private val prefs = context.getSharedPreferences("group_share", Context.MODE_PRIVATE)
    private val running = Mutex()

    /**
     * Quick look at everyone's shares while the app is open: no Google sign-in needed (shares are public links), and a
     * share that hasn't changed costs one tiny request. Only changed ones are downloaded and read. Returns how much changed.
     */
    suspend fun poll(): Polled = running.withLock { withContext(Dispatchers.IO) {
        val me = dao.selfNow() ?: return@withContext Polled(0, true)
        var n = 0; var tried = 0; var failed = 0
        dao.groupsNow().filter { it.peerList.isNotEmpty() }.forEach { g ->
            g.peerList.forEach { p ->
                if (dao.groupNow(g.id)?.peerList?.any { it.key == p.key } != true) return@forEach
                tried++
                runCatching { n += read(dao.groupNow(g.id) ?: g, p, me.id) }.onFailure { failed++ } // quiet: the full sync reports problems
            }
        }
        Polled(n, tried == 0 || failed < tried)
    } }

    /** What [poll] found: how much changed, and whether Drive could be reached at all. */
    data class Polled(val changed: Int, val reachable: Boolean)

    /** Someone changed one of your events (read by [poll]): it should be shared on. */
    val wantsPublish: Boolean get() = synchronized(ownEventsChanged) { ownEventsChanged.isNotEmpty() }

    /** Shares your latest changes right away, without reading the others (that's [poll]'s job). */
    suspend fun publishNow(accessToken: String) = running.withLock { withContext(Dispatchers.IO) {
        val drive = DriveClient(accessToken)
        val me = dao.selfNow() ?: return@withContext
        dao.groupsNow().filter { it.shareIndex != null || it.peerList.isNotEmpty() }.forEach { g ->
            synchronized(ownEventsChanged) { ownEventsChanged.remove(g.id) }
            publish(drive, g, me)
        }
    } }

    /** Publishes your share of every shared group and reads everyone else's. Returns what changed, for the UI. */
    suspend fun sync(accessToken: String, only: String? = null, onProgress: (String) -> Unit = {}): String = running.withLock { withContext(Dispatchers.IO) {
        val drive = DriveClient(accessToken)
        val me = dao.selfNow() ?: error("No account on this phone")
        val groups = dao.groupsNow().filter { (only == null || it.id == only) && (it.shareIndex != null || it.peerList.isNotEmpty() || it.id == only) }
        var received = 0; val problems = mutableListOf<String>()
        groups.forEach { g0 ->
            onProgress("Sharing ${g0.name}…")
            val g = publish(drive, g0, me)
            g.peerList.forEach { p ->
                // removed from the group meanwhile (by an admin, read from an earlier share)
                if (dao.groupNow(g.id)?.peerList?.any { it.key == p.key } != true) return@forEach
                onProgress("Reading ${p.name}'s share…")
                runCatching { received += read(g, p, me.id) }.onFailure { e ->
                    problems += "${p.name}: ${e.message}"
                    // remember why, so Group settings can show it next to their name
                    dao.groupNow(g.id)?.let { now -> dao.upsertGroup(now.copy(peers = Peer.encode(now.peerList.map { if (it.key == p.key) it.copy(err = e.message ?: "Couldn't read") else it }))) }
                }
            }
            settleCreator(g.id, me)
            // someone changed one of your events: share it on straight away, so everyone else sees it too
            if (synchronized(ownEventsChanged) { ownEventsChanged.remove(g.id) }) runCatching { publish(drive, dao.groupNow(g.id) ?: g, me) }
        }
        if (problems.isEmpty()) "Group up to date" + if (received > 0) " · $received new or changed" else ""
        else "Some shares couldn't be read (${problems.joinToString("; ")})"
    } }

    /**
     * The invite for group [groupId]. Quick: the first time it only puts a small index in Drive (your name, no files yet)
     * so the invite can be sent straight away; your documents follow in the background.
     */
    suspend fun invite(accessToken: String, groupId: String): GroupInvite = withContext(Dispatchers.IO) {
        val me = dao.selfNow() ?: error("No account on this phone")
        val g0 = dao.groupNow(groupId) ?: error("Group not found")
        val g = if (g0.shareIndex != null) g0 else {
            val quick = running.withLock { dao.groupNow(groupId)?.takeIf { it.shareIndex != null } ?: firstIndex(DriveClient(accessToken), g0, me) }
            BackupWorker.syncShares(context) // the full share (documents, files) uploads in the background
            quick
        }
        GroupInvite(g.id, g.code, g.shareIndex!!, g.name, me.id, dao.accountNow()?.name ?: me.name)
    }

    /**
     * A short invite carries only the group, its code and where the sender's share is: this reads the share (with the
     * code) for the group's name and who sent it. Also checks the invite works. Needs a connection.
     */
    suspend fun open(inv: GroupInvite): GroupInvite = withContext(Dispatchers.IO) {
        if (inv.complete) return@withContext inv
        val sealed = runCatching { DriveClient.publicDownload(inv.index) }
            .getOrElse { throw IllegalStateException("Couldn't reach the share this invite points to. Check your connection and try again.", it) }
        val j = runCatching { JSONObject(String(BackupCrypto.open(sealed, key(GroupEntity(inv.groupId, "", inv.code))))) }
            .getOrElse { throw IllegalStateException("This invite doesn't open. Make sure you pasted all of it, or ask for a new one.", it) }
        require(j.optString("group") == inv.groupId) { "This invite doesn't match its share. Ask for a new one." }
        val from = j.getJSONObject("from")
        inv.copy(name = j.optString("name").ifBlank { "Group" }, from = from.getString("key"), fromName = from.optString("name").ifBlank { "Someone" })
    }

    /** A first, tiny share index (just you), link-shared, so an invite exists before the files are uploaded. */
    private suspend fun firstIndex(drive: DriveClient, g: GroupEntity, me: MemberEntity): GroupEntity {
        val key = key(g)
        val folder = g.shareFolder ?: run {
            val root = drive.folder("Lararium", null, mapOf(DriveBackup.P_ROOT to "1"))
            val groupFolder = drive.folder(g.name, root, mapOf(DriveBackup.P_GROUP to g.id), "Lararium group · its people, Common vault and events")
            drive.folder("Shared with ${g.name} (encrypted)", groupFolder, mapOf(P_SHARE to g.id),
                "Encrypted copy for the other members of ${g.name}. Only readable in Lararium with the group's code.")
        }
        val name = dao.accountNow()?.name ?: me.name
        val text = JSONObject().put("v", 1).put("group", g.id).put("name", g.name).put("from", JSONObject().put("key", me.id).put("name", name))
            .put("members", JSONArray().put(JSONObject().put("id", me.id).put("name", name).put("relation", me.relation).put("kind", "self").put("avatar", me.avatar ?: JSONObject.NULL)))
            .put("documents", JSONArray()).put("passwords", JSONArray())
            .put("peers", JSONArray(g.peerList.map { JSONObject().put("key", it.key).put("name", it.name).put("index", it.index) }))
            .let { meta(it, g, me, null) }.toString()
        val id = drive.upsertFile(folder, "index.fdx", BackupCrypto.seal(text.toByteArray(), key, GroupInvite.salt(g.id)), OCTET, mapOf(P_SHARE_FILE to "${g.id}:index"), null, P_SHARE_FILE)
        drive.shareWithLink(id)
        val out = g.copy(shareFolder = folder, shareIndex = id)
        dao.upsertGroup(out)
        return out
    }

    /**
     * Pasted invite: joins the group if it's new on this phone, or adds the sender's share to it (someone sending their
     * link back). Returns the group, and a problem to show if their share couldn't be read yet.
     */
    suspend fun accept(accessToken: String?, invite: GroupInvite): Accepted = withContext(Dispatchers.IO) {
        val me = dao.selfNow() ?: error("No account on this phone")
        val inv = open(invite)
        require(inv.from != me.id) { "That's your own invite. Send it to the person you want to add." }
        val existing = dao.groupNow(inv.groupId)
        if (existing != null) require(GroupCodes.normalize(existing.code) == GroupCodes.normalize(inv.code)) { "This invite has a different code from your copy of ${existing.name}" }
        val base: GroupEntity = existing ?: GroupEntity(inv.groupId, inv.name, inv.code)
        if (dao.sharedIn(base.id) == null)
            dao.upsertMember(MemberEntity(java.util.UUID.randomUUID().toString(), "Common", "Shared", null, createdAt = 0L, shared = true, groupId = base.id))
        val peer = Peer(inv.from, inv.fromName, inv.index)
        readmit(base.id, peer.key)
        // (re)joining: every share in the group is read in full again
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith("seen:${base.id}:") }.forEach { remove(it) } }.apply()
        val g: GroupEntity = base.copy(peers = Peer.encode(base.peerList.filter { it.key != peer.key } + peer))
        dao.upsertGroup(g)
        // Read it now (this also checks the invite works); if the connection fails, the next sync tries again.
        val problem = running.withLock {
            val err = runCatching { read(g, peer, me.id, quick = false) }.exceptionOrNull()?.let { it.message ?: "Couldn't read the share yet" }
            // Publish yours too, so the invite you send back works right away.
            if (accessToken != null) runCatching { publish(DriveClient(accessToken), dao.groupNow(g.id)!!, me) }
            err
        }
        if (accessToken == null) BackupWorker.syncShares(context)
        val now = dao.groupNow(g.id)!!
        Accepted(now, problem, inv.fromName, inv.from, joined = existing == null, synced = now.peerList.firstOrNull { it.key == peer.key }?.sees == true)
    }

    /** A joined (or updated) group, a problem reading the sender's share if there was one, and who sent the invite. */
    data class Accepted(val group: GroupEntity, val problem: String?, val fromName: String, val fromKey: String, val joined: Boolean,
                        /** Their share already lists you: the two of you are synced both ways. */
                        val synced: Boolean)

    /** Stops reading [peerKey]'s share and removes what they shared from this phone. */
    suspend fun forget(groupId: String, peerKey: String) = withContext(Dispatchers.IO) {
        val g = dao.groupNow(groupId) ?: return@withContext
        removeFrom(g, peerKey, keepDocs = emptySet(), keepMembers = emptySet(), keepPws = emptySet())
        dao.upsertGroup(g.copy(peers = Peer.encode(g.peerList.filter { it.key != peerKey })))
    }

    // ---------------- publishing ----------------

    // The key is slow to derive on purpose (it protects the code), so it's worked out once per group and code.
    private val keyCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private fun key(g: GroupEntity) = keyCache.getOrPut("${g.id}|${GroupCodes.normalize(g.code)}") {
        BackupCrypto.deriveKey(GroupCodes.normalize(g.code).toCharArray(), GroupInvite.salt(g.id))
    }

    /** Groups where another member's change to one of your events was just taken over: shared on again right away. */
    private val ownEventsChanged = mutableSetOf<String>()

    private suspend fun publish(drive: DriveClient, g: GroupEntity, me: MemberEntity): GroupEntity {
        // The share's folder is looked up (and renamed with the group) only now and then, not on every change.
        val cached = prefs.getString("folder:${g.id}", null)?.split('\n')
        val fresh = cached != null && cached.size == 3 && cached[0] == g.name && cached[1] == g.shareFolder &&
            System.currentTimeMillis() - (cached[2].toLongOrNull() ?: 0L) < FOLDER_CHECK
        val folder = if (fresh) cached!![1] else {
            val root = drive.folder("Lararium", null, mapOf(DriveBackup.P_ROOT to "1"))
            val groupFolder = drive.folder(g.name, root, mapOf(DriveBackup.P_GROUP to g.id), "Lararium group · its people, Common vault and events")
            drive.folder("Shared with ${g.name} (encrypted)", groupFolder, mapOf(P_SHARE to g.id),
                "Encrypted copy for the other members of ${g.name}. Only readable in Lararium with the group's code.")
                .also { prefs.edit().putString("folder:${g.id}", "${g.name}\n$it\n${System.currentTimeMillis()}").apply() }
        }
        try { return publishInto(drive, g, me, folder, tidy = !fresh) }
        catch (e: Exception) { prefs.edit().remove("folder:${g.id}").apply(); throw e } // look it up again next time
    }

    private suspend fun publishInto(drive: DriveClient, g: GroupEntity, me: MemberEntity, folder: String, tidy: Boolean): GroupEntity {
        val key = key(g); val salt = GroupInvite.salt(g.id)
        fun seal(b: ByteArray) = BackupCrypto.seal(b, key, salt)

        val all = dao.membersNow()
        val common = all.firstOrNull { it.shared && it.groupId == g.id }
        // Only you and the Common vault: everyone else in a group has their own account and shares their own.
        val people = listOf(me) + listOfNotNull(common)
        val ids = people.map { it.id }.toSet()
        val docs = dao.allDocumentsNow().filter { it.memberId in ids && it.origin == null && it.eventId == null }
        val pws = common?.let { c -> dao.allPasswordsNow().filter { it.memberId == c.id && it.origin == null } }.orEmpty()

        // Files are uploaded once (their ids never change: an edited scan is a new file), then just referenced.
        val live = mutableSetOf<String>()
        fun upload(fileId: String?): String? {
            fileId ?: return null
            val tag = "${g.id}:$fileId"
            prefs.getString(tag, null)?.let { synchronized(live) { live += tag }; return it }
            val bytes = files.get(fileId) ?: return null
            val id = drive.upsertFile(folder, "$fileId.fdx", seal(bytes), OCTET, mapOf(P_SHARE_FILE to tag), null, P_SHARE_FILE)
            drive.shareWithLink(id)
            prefs.edit().putString(tag, id).apply(); synchronized(live) { live += tag }
            return id
        }

        // Built twice: first only to see which files are new, so they upload side by side instead of one after another.
        suspend fun build(upload: (String?) -> String?): JSONObject {
        val index = JSONObject().put("v", 1).put("group", g.id).put("name", g.name)
            .put("from", JSONObject().put("key", me.id).put("name", dao.accountNow()?.name ?: me.name))
            .put("members", JSONArray(people.map { m ->
                JSONObject().put("id", m.id).put("name", if (m.isSelf) (dao.accountNow()?.name ?: m.name) else m.name).put("relation", m.relation)
                    .put("kind", when { m.isSelf -> "self"; m.shared -> "common"; else -> "person" })
                    .put("avatar", m.avatar ?: JSONObject.NULL).put("photo", upload(m.photoFile) ?: JSONObject.NULL)
            }))
            .put("documents", JSONArray(docs.map { d ->
                JSONObject().put("id", d.id).put("member", d.memberId).put("name", d.name).put("fields", d.fieldsJson).put("text", d.rawText)
                    .put("ocr", d.readByOcr).put("country", d.country ?: JSONObject.NULL).put("display", d.display.name)
                    .put("flag", d.showFlag ?: JSONObject.NULL).put("created", d.createdAt).put("updated", d.updatedAt).put("order", d.sortOrder ?: JSONObject.NULL)
                    .put("archived", d.archivedAt ?: JSONObject.NULL).put("parent", d.parentId ?: JSONObject.NULL)
                    .put("files", JSONArray(d.attachments.mapNotNull { a -> upload(a.id)?.let { JSONObject().put("f", it).put("mime", a.mime) } }))
                    .put("picture", upload(d.pictureFile) ?: JSONObject.NULL)
            }))
            // Common logins don't travel under the group code: they're sealed to each member's phone key ("logins" below).
            .put("passwords", JSONArray())
            .put("logins", sealedLogins(g, me, pws))
            // Who else you read: someone who joins through you finds the rest of the group from here.
            .put("peers", JSONArray(g.peerList.map { JSONObject().put("key", it.key).put("name", it.name).put("index", it.index) }))
        // The Common vault's name and picture are everyone's: the newest change wins on every phone.
        common?.let { c -> index.put("commonAt", commonStamp(g.id, c)) }
        // Events: yours that someone else can see (only you choose who), and the changes you made last to other people's
        // events (everyone they're shared with can change them; the newest change wins), plus the documents you added to any.
        val evs = dao.eventsNow().filter { it.groupId == g.id }
        val own = evs.filter { !it.received && (it.visibleIds.isEmpty() || it.visibleIds.any { v -> v != me.id }) }
        val theirs = evs.filter { it.received && it.id.startsWith("s:${g.id}:") }
        fun person(id: String): JSONObject? = when {
            id == me.id -> JSONObject().put("k", me.id).put("id", me.id)
            id == common?.id -> JSONObject().put("k", "common")
            id.startsWith("s:${g.id}:") -> id.removePrefix("s:${g.id}:").split(':', limit = 2).takeIf { it.size == 2 }?.let { (k, rest) -> JSONObject().put("k", k).put("id", rest) }
            else -> null
        }
        fun docRef(id: String): JSONObject? = if (id.startsWith("s:${g.id}:")) person(id) else JSONObject().put("k", me.id).put("id", id)
        fun eventJson(e: EventEntity, id: String, owner: String) = JSONObject().put("id", id).put("owner", owner).put("name", e.name).put("art", e.art)
            .put("photo", upload(e.photoFile) ?: JSONObject.NULL).put("start", e.startDay).put("end", e.endDay ?: JSONObject.NULL).put("notes", e.notes)
            .put("created", e.createdAt).put("at", e.updatedAt).put("by", e.editedBy ?: me.id)
            .put("people", JSONArray(e.peopleIds.mapNotNull(::person))).put("pins", JSONArray(e.pinIds.mapNotNull(::docRef)))
        // the version the others start from: what a later merge of the notes compares against
        own.forEach { e -> if (!prefs.contains("notes-base:${e.id}")) prefs.edit().putString("notes-base:${e.id}", e.notes).apply() }
        index.put("events", JSONArray(own.map { e -> eventJson(e, e.id, me.id).put("visible", JSONArray(e.visibleIds.mapNotNull(::person))) }))
        index.put("eventEdits", JSONArray(theirs.filter { it.editedBy == null && it.updatedAt > 0 }.mapNotNull { e ->
            e.organiserKey?.let { k -> eventJson(e, e.id.removePrefix("s:${g.id}:$k:"), k) }
        }))
        val eventRefs = own.associate { it.id to (me.id to it.id) } + theirs.mapNotNull { e -> e.organiserKey?.let { k -> e.id to (k to e.id.removePrefix("s:${g.id}:$k:")) } }
        index.put("eventDocs", JSONArray(dao.allDocumentsNow().filter { it.eventId in eventRefs && it.origin == null }.map { d ->
            val (owner, ev) = eventRefs.getValue(d.eventId!!)
            JSONObject().put("id", d.id).put("event", ev).put("owner", owner).put("kind", if (d.memberId == common?.id) "common" else "self").put("name", d.name)
                .put("fields", d.fieldsJson).put("text", d.rawText).put("ocr", d.readByOcr).put("country", d.country ?: JSONObject.NULL)
                .put("display", d.display.name).put("flag", d.showFlag ?: JSONObject.NULL).put("created", d.createdAt).put("updated", d.updatedAt)
                .put("files", JSONArray(d.attachments.mapNotNull { a -> upload(a.id)?.let { JSONObject().put("f", it).put("mime", a.mime) } }))
                .put("picture", upload(d.pictureFile) ?: JSONObject.NULL)
        }))
        // admin changes nobody picked up for two months are dropped
        synchronized(prefs) { ops(g.id).let { all -> all.filter { System.currentTimeMillis() - it.optLong("at") < OP_LIFE }.let { if (it.size != all.size) saveOps(g.id, it) } } }
        meta(index, g, me, upload)
        return index
        }
        val fresh = linkedSetOf<String>()
        build { id -> if (id != null && prefs.getString("${g.id}:$id", null) == null) fresh += id; null }
        if (fresh.isNotEmpty()) kotlinx.coroutines.coroutineScope {
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            fresh.map { id -> async(Dispatchers.IO) { gate.withPermit { runCatching { upload(id) } } } }.awaitAll()
        }
        val index = build(::upload)
        val text = index.toString()
        val hash = sha(text)
        var out = g
        if (g.shareIndex == null || prefs.getString("${g.id}:index-hash", null) != hash || g.shareFolder != folder) {
            val sealed = seal(text.toByteArray())
            // the same file, its contents replaced in one request (it keeps its id and link)
            val id = g.shareIndex?.takeIf { g.shareFolder == folder && runCatching { drive.updateContent(it, sealed, OCTET) }.isSuccess }
                ?: drive.upsertFile(folder, "index.fdx", sealed, OCTET, mapOf(P_SHARE_FILE to "${g.id}:index"), null, P_SHARE_FILE)
            if (g.shareIndex != id) drive.shareWithLink(id)
            out = g.copy(shareFolder = folder, shareIndex = id)
            if (out != g) dao.upsertGroup(out)
            prefs.edit().putString("${g.id}:index-hash", hash).apply()
        }
        // Files nothing points to any more (deleted documents, replaced scans) leave Drive: only when something was dropped
        // (and now and then, for leftovers).
        val stale = prefs.all.keys.filter { it.startsWith("${g.id}:") && !it.endsWith(":index-hash") && it !in live }
        if (stale.isNotEmpty() || tidy) {
            drive.children(folder).filter { val t = it.props[P_SHARE_FILE]; t != null && t != "${g.id}:index" && t !in live }.forEach { drive.trash(it.id) }
            prefs.edit().apply { stale.forEach { remove(it) } }.apply()
        }
        return out
    }

    // ---------------- reading ----------------

    /** Reads one member's share into this phone. Returns how many documents were new or changed. */
    private suspend fun read(g: GroupEntity, p: Peer, myKey: String, quick: Boolean = true): Int {
        val k = key(g)
        fun fetch(driveId: String) = fetchFile(driveId, k)
        // Unchanged since it was last read (and nothing new here for them either): skipped after a 64-byte peek.
        val seenKey = "seen:${g.id}:${p.key}"
        val local = sha(synchronized(prefs) { (ops(g.id) + relayed(g.id)).filter { it.optString("to") == p.key }.joinToString("\n") }) + "|" + com.familydocs.app.BuildConfig.VERSION_CODE
        var whole: ByteArray? = null
        if (quick) {
            val (head, complete) = DriveClient.publicPeek(p.index)
            val was = prefs.getString(seenKey, null)
            if (was != null && was.substringBeforeLast('|') == mark(head) + "|" + local &&
                System.currentTimeMillis() - (was.substringAfterLast('|').toLongOrNull() ?: 0L) < FULL_READ) {
                if (p.err != null) dao.groupNow(g.id)?.let { now -> dao.upsertGroup(now.copy(peers = Peer.encode(now.peerList.map { if (it.key == p.key) it.copy(err = null) else it }))) }
                return 0
            }
            if (complete) whole = head
        }
        val sealed = whole ?: DriveClient.publicDownload(p.index)
        val j = JSONObject(String(BackupCrypto.open(sealed, k)))
        require(j.getString("group") == g.id) { "This share belongs to another group" }
        val from = j.getJSONObject("from")
        val prefix = "s:${g.id}:${p.key}:"
        val common = dao.sharedIn(g.id)
        var changed = 0
        // Your admin changes their phone has taken over: drop them (their version is read below).
        val retired = retire(g.id, p.key, j.optJSONArray("applied"))
        // Your admin changes still waiting for their phone: shown here instead of what their share says.
        val pending = ops(g.id).filter { it.optString("to") == p.key }
        val pendingDocs = pending.filter { it.optString("kind") == "doc" }.map { prefix + it.getJSONObject("doc").getString("id") }.toSet()
        val pendingDel = pending.filter { it.optString("kind") == "deldoc" }.map { prefix + it.optString("doc") }.toSet()
        val pendingProfile = pending.lastOrNull { it.optString("kind") == "profile" }

        // An admin reading as [p]: their changes to other people's things are kept for when those people are read.
        if (p.key in (dao.groupNow(g.id)?.adminKeys ?: emptySet())) synchronized(prefs) {
            val theirs = mutableListOf<JSONObject>()
            j.optJSONArray("ops")?.forEachObj { op -> val to = op.optString("to"); if (to.isNotEmpty() && to != myKey && to != p.key) theirs += JSONObject(op.toString()).put("by", p.key) }
            saveRelayed(g.id, relayed(g.id).filter { it.optString("by") != p.key } + theirs)
        }
        // Another admin's changes to [p]'s things that [p]'s phone hasn't taken over yet: shown as the admin made them.
        val takenOver = j.optJSONArray("applied")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
        val relay = synchronized(prefs) {
            val all = relayed(g.id)
            if (all.any { it.optString("to") == p.key && it.optString("id") in takenOver }) saveRelayed(g.id, all.filterNot { it.optString("to") == p.key && it.optString("id") in takenOver })
            all.filter { it.optString("to") == p.key && it.optString("id") !in takenOver }.sortedBy { it.optLong("at") }
        }
        val relayDocs = relay.filter { it.optString("kind") == "doc" }.associate { prefix + it.getJSONObject("doc").getString("id") to it.getJSONObject("doc") }
            .filterKeys { it !in pendingDocs && it !in pendingDel }
        val relayDel = relay.filter { it.optString("kind") == "deldoc" && relay.none { o -> o.optString("kind") == "doc" && o.optLong("at") > it.optLong("at") && docIdOf(o) == it.optString("doc") } }
            .map { prefix + it.optString("doc") }.toSet() - pendingDocs
        val relayProfile = relay.lastOrNull { it.optString("kind") == "profile" }
        var selfRow: String? = null

        val memberIds = mutableMapOf<String, String>()
        val keepMembers = mutableSetOf<String>()
        j.getJSONArray("members").forEachObj { m ->
            val kind = m.optString("kind")
            if (kind == "common") {
                common?.let { c ->
                    memberIds[m.getString("id")] = c.id
                    // their Common changed more recently than yours: take its name and picture
                    val theirs = j.optLong("commonAt", 0L)
                    if (theirs > commonStamp(g.id, c)) {
                        val photo = m.optStringOrNull("photo")?.let { fetch(it) }
                        if (m.optStringOrNull("photo") == null || photo != null) {
                            val now = c.copy(name = m.optString("name").ifBlank { c.name }, avatar = m.optStringOrNull("avatar"), photoFile = photo)
                            dao.upsertMember(now)
                            synchronized(prefs) { prefs.edit().putString("common-sig:${g.id}", commonSig(now)).putLong("common-at:${g.id}", theirs).apply() }
                            changed++
                        }
                    }
                }
                return@forEachObj
            }
            // Only the account holder themselves: people their phone made up (older versions) are not group members.
            if (kind != "self") return@forEachObj
            val id = prefix + m.getString("id")
            memberIds[m.getString("id")] = id; keepMembers += id
            val photo = m.optStringOrNull("photo")?.let { fetch(it) }
            var row = MemberEntity(id, m.getString("name"), "Group member", photo, createdAt = 1L, avatar = m.optStringOrNull("avatar"), groupId = g.id, origin = p.key)
            selfRow = id
            relayProfile?.let { op -> row = row.copy(name = op.optStringOrNull("name") ?: row.name, avatar = op.optStringOrNull("avatar") ?: row.avatar,
                photoFile = if (op.has("photo")) op.optString("photo").ifEmpty { null }?.let { fetch(it) } else row.photoFile) }
            pendingProfile?.let { op -> row = row.copy(name = op.optStringOrNull("name") ?: row.name, avatar = op.optStringOrNull("avatar") ?: row.avatar,
                photoFile = if (op.has("photo")) op.optString("photo").ifEmpty { null } else row.photoFile) }
            dao.upsertMember(row)
        }

        val keepDocs = mutableSetOf<String>()
        // What they changed since the last read, for a silent notification (not on the first read: that's everything).
        val activity = mutableListOf<String>()
        fun where(member: String) = if (member == common?.id) " in Common" else ""
        j.getJSONArray("documents").forEachObj { d ->
            val member = memberIds[d.getString("member")] ?: return@forEachObj
            val id = prefix + d.getString("id")
            if (id in pendingDel || id in relayDel) return@forEachObj
            keepDocs += id
            if (id in pendingDocs || id in relayDocs) return@forEachObj
            val existing = dao.documentNow(id)
            val atts = mutableListOf<Attachment>()
            d.getJSONArray("files").forEachObj { f -> fetch(f.getString("f"))?.let { atts += Attachment(it, f.optString("mime", "image/jpeg")) } }
            if (existing != null && existing.updatedAt == d.getLong("updated") && existing.attachments.map { it.id } == atts.map { it.id }) return@forEachObj
            activity += (if (existing == null) "added " else "updated ") + d.getString("name") + where(member)
            dao.upsertDocument(DocumentEntity(
                id = id, memberId = member, name = d.getString("name"), fileId = atts.firstOrNull()?.id, fileMime = atts.firstOrNull()?.mime,
                display = runCatching { DisplayMode.valueOf(d.getString("display")) }.getOrDefault(DisplayMode.ICON),
                pictureFile = d.optStringOrNull("picture")?.let { fetch(it) }, fieldsJson = d.optString("fields", "[]"), rawText = d.optString("text"),
                readByOcr = d.optBoolean("ocr"), country = d.optStringOrNull("country"), createdAt = d.optLong("created"), updatedAt = d.getLong("updated"),
                showFlag = if (d.isNull("flag")) null else d.getBoolean("flag"), moreFiles = Attachment.encode(atts.drop(1)),
                sortOrder = if (d.isNull("order")) null else d.getInt("order"), origin = p.key,
                archivedAt = if (!d.has("archived") || d.isNull("archived")) null else d.getLong("archived"),
                parentId = d.optStringOrNull("parent")?.let { prefix + it },
            ))
            changed++
        }

        // another admin's added or edited documents for them, as the admin made them
        relayDocs.forEach { (id, d) ->
            if (id in relayDel) return@forEach
            val member = (if (d.optBoolean("common")) common?.id else selfRow) ?: return@forEach
            val atts = mutableListOf<Attachment>()
            d.optJSONArray("files")?.forEachObj { f -> fetch(f.getString("f"))?.let { atts += Attachment(it, f.optString("mime", "image/jpeg")) } }
            val existing = dao.documentNow(id)
            if (existing != null && existing.updatedAt == d.optLong("updated") && existing.attachments.map { it.id } == atts.map { it.id }) { keepDocs += id; return@forEach }
            dao.upsertDocument(DocumentEntity(
                id = id, memberId = member, name = d.getString("name"), fileId = atts.firstOrNull()?.id, fileMime = atts.firstOrNull()?.mime,
                display = runCatching { DisplayMode.valueOf(d.getString("display")) }.getOrDefault(DisplayMode.ICON),
                pictureFile = d.optStringOrNull("picture")?.let { fetch(it) }, fieldsJson = d.optString("fields", "[]"), rawText = d.optString("text"),
                readByOcr = d.optBoolean("ocr"), country = d.optStringOrNull("country"), createdAt = existing?.createdAt ?: d.optLong("created", System.currentTimeMillis()),
                updatedAt = d.optLong("updated", System.currentTimeMillis()), showFlag = if (d.isNull("flag")) null else d.optBoolean("flag"),
                moreFiles = Attachment.encode(atts.drop(1)), sortOrder = existing?.sortOrder, origin = p.key,
                archivedAt = if (!d.has("archived") || d.isNull("archived")) null else d.getLong("archived"),
            ))
            keepDocs += id; changed++
        }

        // ---- events: theirs that you can see, changes anyone made to an event you can see, documents added to them ----
        val keepEvents = mutableSetOf<String>()
        val mySelf = dao.selfNow()?.id
        fun person(o: JSONObject): String? = when (val k = o.optString("k")) {
            myKey -> mySelf; "common" -> common?.id; "" -> null; else -> "s:${g.id}:$k:${o.optString("id")}"
        }
        fun refs(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.getJSONObject(i) } }.orEmpty()
        fun localEvent(owner: String, id: String) = if (owner == myKey) id else "s:${g.id}:$owner:$id"
        /** [base] with the event's contents from [ev] (who sees it stays as it is: only the organiser changes that). */
        fun content(ev: JSONObject, base: EventEntity): EventEntity {
            val at = ev.optLong("at", 0L)
            // You changed the notes here too (at the same time, or before this phone shared it): both are kept, and the
            // merged notes go out again as your change.
            val theirs = ev.optString("notes")
            val mine = base.notes.takeIf { base.editedBy == null && base.updatedAt > 0 && it != theirs }
            val notes = mine?.let { NotesMerge.merge(prefs.getString("notes-base:${base.id}", null).orEmpty(), it, theirs) } ?: theirs
            prefs.edit().putString("notes-base:${base.id}", theirs).apply()
            val merged = notes != theirs
            if (merged) synchronized(ownEventsChanged) { ownEventsChanged += g.id }
            return base.copy(
                name = ev.getString("name"), art = ev.optString("art", "trip"), photoFile = ev.optStringOrNull("photo")?.let { fetch(it) },
                startDay = ev.getString("start"), endDay = ev.optStringOrNull("end"),
                people = refs(ev.optJSONArray("people")).mapNotNull(::person).joinToString(","),
                pins = refs(ev.optJSONArray("pins")).mapNotNull { r -> if (r.optString("k") == myKey) r.optString("id") else "s:${g.id}:${r.optString("k")}:${r.optString("id")}" }.joinToString(","),
                notes = notes, updatedAt = if (merged) maxOf(System.currentTimeMillis(), at + 1) else at,
                editedBy = if (merged) null else ev.optString("by").ifEmpty { p.key }.takeIf { it != myKey } ?: p.key,
            )
        }
        // their own events: they decide who sees them
        j.optJSONArray("events")?.forEachObj { ev ->
            val visible = refs(ev.optJSONArray("visible"))
            if (visible.isNotEmpty() && visible.none { it.optString("k") == myKey }) return@forEachObj
            val id = prefix + ev.getString("id")
            keepEvents += id
            val old = dao.eventNow(id)
            val at = ev.optLong("at", 0L)
            val vis = visible.mapNotNull(::person).joinToString(",")
            val take = old == null || at > old.updatedAt || (at == 0L && old.updatedAt == 0L)
            val row = (if (take) content(ev, old ?: EventEntity(id = id, name = "", art = "trip", startDay = "", remindDays = -1,
                createdAt = ev.optLong("created", 0L), groupId = g.id)) else old!!).copy(visibleTo = vis, groupId = g.id)
            if (row != old) { dao.upsertEvent(row); changed++; if (old == null) activity += "added the event ${row.name}" }
        }
        // what they changed last in someone else's event (yours included) that you can see too
        j.optJSONArray("eventEdits")?.forEachObj { ev ->
            val owner = ev.optString("owner").ifEmpty { return@forEachObj }
            val id = localEvent(owner, ev.getString("id"))
            val old = dao.eventNow(id)?.takeIf { it.groupId == g.id } ?: return@forEachObj
            if (ev.optLong("at", 0L) <= old.updatedAt) return@forEachObj
            dao.upsertEvent(content(ev, old)); changed++
            if (owner == myKey) synchronized(ownEventsChanged) { ownEventsChanged += g.id }
        }
        removeEvents(g, p.key, keepEvents)
        j.optJSONArray("eventDocs")?.forEachObj { d ->
            val ev = localEvent(d.optString("owner").ifEmpty { p.key }, d.getString("event"))
            if (dao.eventNow(ev)?.groupId != g.id) return@forEachObj
            val member = (if (d.optString("kind") == "common") common?.id else selfRow) ?: return@forEachObj
            val id = prefix + d.getString("id")
            keepDocs += id
            val existing = dao.documentNow(id)
            val atts = mutableListOf<Attachment>()
            d.optJSONArray("files")?.forEachObj { f -> fetch(f.getString("f"))?.let { atts += Attachment(it, f.optString("mime", "image/jpeg")) } }
            if (existing != null && existing.updatedAt == d.optLong("updated") && existing.attachments.map { it.id } == atts.map { it.id }) return@forEachObj
            dao.upsertDocument(DocumentEntity(
                id = id, memberId = member, name = d.getString("name"), fileId = atts.firstOrNull()?.id, fileMime = atts.firstOrNull()?.mime,
                display = runCatching { DisplayMode.valueOf(d.getString("display")) }.getOrDefault(DisplayMode.ICON),
                pictureFile = d.optStringOrNull("picture")?.let { fetch(it) }, fieldsJson = d.optString("fields", "[]"), rawText = d.optString("text"),
                readByOcr = d.optBoolean("ocr"), country = d.optStringOrNull("country"), createdAt = d.optLong("created"), updatedAt = d.optLong("updated"),
                showFlag = if (d.isNull("flag")) null else d.optBoolean("flag"), eventId = ev, origin = p.key,
                moreFiles = Attachment.encode(atts.drop(1)),
            ))
            changed++
        }

        val keepPws = mutableSetOf<String>()
        val hadPws = if (common != null) dao.receivedPasswords(p.key, "$prefix%").map { it.id }.toSet() else emptySet()
        if (common != null) {
            suspend fun take(w: JSONObject) {
                val id = prefix + w.getString("id"); keepPws += id
                dao.upsertPassword(PasswordEntity(id, common.id, w.getString("site"), w.getString("user"), w.getString("pass"), w.optLong("created"), origin = p.key))
            }
            j.optJSONArray("passwords")?.forEachObj { take(it) } // (older apps)
            j.optJSONObject("logins")?.let { l ->
                val opened = runCatching { openLogins(l, myKey) }.getOrNull()
                // not sealed to this phone yet (they haven't read your new key): keep what you had from them
                if (opened == null) dao.receivedPasswords(p.key, "s:${g.id}:${p.key}:%").forEach { keepPws += it.id }
                else opened.forEachObj { take(it) }
            }
        }
        dao.allDocumentsNow().filter { it.id.startsWith(prefix) && it.id !in keepDocs && it.id !in pendingDocs && it.id !in pendingDel && it.eventId == null }
            .forEach { activity += "removed ${it.name}" + where(it.memberId) }
        (keepPws - hadPws).size.takeIf { it > 0 && common != null }?.let { n -> activity += if (n == 1) "added a login in Common" else "added $n logins in Common" }
        if (p.at > 0L && activity.isNotEmpty())
            runCatching { GroupActivity.show(context, g.id, g.name, from.optString("name", p.name), activity.take(6) + listOfNotNull(if (activity.size > 6) "and ${activity.size - 6} more" else null)) }
        removeFrom(g, p.key, keepDocs + pendingDocs, keepMembers, keepPws)
        cleanup(retired)

        // ---- admins: who made the group, who else is an admin, removals, and changes an admin made to your things ----
        j.optLong("since").takeIf { it > 0 }?.let { prefs.edit().putLong("since:${g.id}:${p.key}", it).apply() }
        var grp = dao.groupNow(g.id) ?: g
        if (grp.creator == null) j.optStringOrNull("creator")?.let { grp = grp.copy(creator = it); dao.upsertGroup(grp) }
        val by = from.optString("name", p.name)
        if (p.key in grp.adminKeys) {
            j.optJSONObject("admins")?.let { a ->
                val at = a.optLong("at")
                if (at > grp.adminsAt) {
                    val keys = a.optJSONArray("keys")?.let { ks -> (0 until ks.length()).map { ks.getString(it) } }.orEmpty().filter { it != grp.creator }
                    val was = myKey in grp.adminKeys
                    grp = grp.copy(admins = keys.joinToString(",").ifEmpty { null }, adminsAt = at); dao.upsertGroup(grp)
                    val isNow = myKey in grp.adminKeys
                    if (isNow && !was) addNotice(grp, by, "$by made you an admin of ${grp.name}: you can change everyone's documents and profiles, and add or remove admins and members.")
                    if (was && !isNow) addNotice(grp, by, "$by took away your admin role in ${grp.name}.")
                }
            }
            var removedMe = false
            j.optJSONObject("removed")?.let { r ->
                val handled = synchronized(prefs) { longMap("handled:${g.id}") }
                r.keys().forEach { rk ->
                    val at = r.optLong(rk)
                    if (at > (handled[rk] ?: 0L)) {
                        handled[rk] = at
                        if (rk == myKey) removedMe = true else if (rk != p.key && rk != grp.creator) forget(g.id, rk)
                    }
                }
                synchronized(prefs) { putLongMap("handled:${g.id}", longMap("handled:${g.id}").apply { putAll(handled) }) }
            }
            if (removedMe) { leave(grp, by); return changed }
            changed += applyOps(grp, j, by, myKey, k)
        }

        // Their name may have changed, and they may know members you don't yet (not people an admin just removed).
        val now = dao.groupNow(g.id) ?: grp
        val known = now.peerList.associateBy { it.key }.toMutableMap()
        val sees = (0 until (j.optJSONArray("peers")?.length() ?: 0)).any { j.getJSONArray("peers").getJSONObject(it).optString("key") == myKey }
        known[p.key] = p.copy(name = by, at = System.currentTimeMillis(), err = null, pub = j.optStringOrNull("pub") ?: p.pub, sees = sees)
        // They've just added you (you were waiting for it): say so once.
        if (p.sees == false && sees) addNotice(grp, by, "You and $by are now synced!", "check")
        val gone = synchronized(prefs) { longMap("handled:${g.id}") }
        j.optJSONArray("peers")?.forEachObj { o -> val pk = o.getString("key")
            if (pk != myKey && pk !in known && System.currentTimeMillis() - (gone[pk] ?: 0L) > REMOVED_QUIET) known[pk] = Peer(pk, o.optString("name"), o.getString("index")) }
        dao.upsertGroup(now.copy(peers = Peer.encode(known.values.toList())))
        prefs.edit().putString(seenKey, mark(sealed) + "|" + local + "|" + System.currentTimeMillis()).apply()
        return changed
    }

    /** A share's version: its first bytes (a new random IV every time it's re-encrypted). */
    private fun mark(b: ByteArray) = b.copyOf(minOf(64, b.size)).joinToString("") { "%02x".format(it) }

    // ---------------- Common logins, sealed to each member's phone ----------------

    /**
     * This phone's key pair for the group's Common logins: the private half never leaves the phone (kept encrypted with
     * the Android Keystore key), the public half goes in your share. A new phone gets a new pair; the others seal to it
     * once they've read your share.
     */
    private fun deviceKeys(): Pair<String, java.security.PrivateKey> {
      synchronized(prefs) {
        val pub = prefs.getString("device_pub", null)
        val priv = crypto.readSecret("device_ec_priv")
        if (pub != null && priv != null)
            return pub to java.security.KeyFactory.getInstance("EC").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(priv))
        val kp = PhoneCrypto.newKeys()
        crypto.storeSecret("device_ec_priv", kp.private.encoded)
        val p = PhoneCrypto.b64(kp.public.encoded)
        prefs.edit().putString("device_pub", p).commit()
        return p to kp.private
      }
    }
    private fun devicePub() = deviceKeys().first

    /**
     * Your Common logins for the share: sealed with a fresh random key, and that key sealed to each member's phone key
     * (and yours). Only members whose phones you've read can open them; the group code alone can't. Reused while
     * nothing changed, so the share isn't rewritten on every sync.
     */
    private fun sealedLogins(g: GroupEntity, me: MemberEntity, pws: List<PasswordEntity>): Any {
        if (pws.isEmpty()) return JSONObject.NULL
        val plain = JSONArray(pws.map { JSONObject().put("id", it.id).put("site", it.site).put("user", it.username).put("pass", it.password).put("created", it.createdAt) }).toString()
        val to = (g.peerList.mapNotNull { p -> p.pub?.let { p.key to it } } + (me.id to devicePub())).sortedBy { it.first }
        val sig = sha(plain + "|" + to.joinToString(",") { "${it.first}:${it.second}" })
        if (prefs.getString("loginsig:${g.id}", null) == sig) prefs.getString("logins:${g.id}", null)?.let { return JSONObject(it) }
        val k = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val env = JSONObject()
        to.forEach { (key, pub) -> runCatching { PhoneCrypto.sealTo(PhoneCrypto.b64(k), PhoneCrypto.publicOf(pub)) }.getOrNull()
            ?.let { (epk, box) -> env.put(key, JSONObject().put("epk", epk).put("box", box)) } }
        val out = JSONObject().put("box", PhoneCrypto.b64(BackupCrypto.seal(plain.toByteArray(), k, GroupInvite.salt(g.id)))).put("to", env)
        prefs.edit().putString("logins:${g.id}", out.toString()).putString("loginsig:${g.id}", sig).apply()
        return out
    }

    /** Opens someone's Common logins with this phone's key; null when they aren't sealed to it (yet). */
    private fun openLogins(l: JSONObject, myKey: String): JSONArray? {
        val e = l.optJSONObject("to")?.optJSONObject(myKey) ?: return null
        val k = PhoneCrypto.unb64(PhoneCrypto.openFrom(e.getString("epk"), e.getString("box"), deviceKeys().second))
        return JSONArray(String(BackupCrypto.open(PhoneCrypto.unb64(l.getString("box")), k)))
    }

    // ---------------- admins ----------------

    /** Something an admin changed in your things (or about you), shown on Home until you close it. */
    data class Notice(val id: String, val groupId: String, val by: String, val text: String, val at: Long, val icon: String = "shield")
    val notices = kotlinx.coroutines.flow.MutableStateFlow(loadNotices())
    fun dismissNotice(id: String) = synchronized(prefs) { saveNotices(notices.value.filter { it.id != id }) }
    private fun loadNotices(): List<Notice> = runCatching {
        val a = JSONArray(prefs.getString("notices", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Notice(o.getString("id"), o.optString("g"), o.optString("by"), o.getString("t"), o.optLong("at"), o.optString("i").ifEmpty { "shield" }) } }
    }.getOrDefault(emptyList())
    private fun saveNotices(list: List<Notice>) {
        prefs.edit().putString("notices", JSONArray(list.map { JSONObject().put("id", it.id).put("g", it.groupId).put("by", it.by).put("t", it.text).put("at", it.at).put("i", it.icon) }).toString()).apply()
        notices.value = list
    }
    private fun addNotice(g: GroupEntity, by: String, text: String, icon: String = "shield") = synchronized(prefs) {
        saveNotices((notices.value + Notice(java.util.UUID.randomUUID().toString(), g.id, by, text, System.currentTimeMillis(), icon)).takeLast(20))
    }

    private fun jsonList(key: String): MutableList<JSONObject> = runCatching {
        val a = JSONArray(prefs.getString(key, null) ?: return mutableListOf()); (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
    }.getOrDefault(mutableListOf())
    private fun longMap(key: String): MutableMap<String, Long> = runCatching {
        val o = JSONObject(prefs.getString(key, null) ?: return mutableMapOf()); o.keys().asSequence().associateWith { o.optLong(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())
    private fun putLongMap(key: String, m: Map<String, Long>) { prefs.edit().putString(key, JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }.toString()).apply() }
    /**
     * When this phone's Common vault (name, picture) last changed: noticed by comparing it with what was shared last
     * time. Unknown before this existed: a Common with a photo counts as slightly newer than one without.
     */
    private fun commonSig(c: MemberEntity) = sha("${c.name}|${c.avatar}|${c.photoFile}")
    private fun commonStamp(gid: String, c: MemberEntity): Long = synchronized(prefs) {
        val sig = commonSig(c); val was = prefs.getString("common-sig:$gid", null)
        val at = when {
            was == null -> if (c.photoFile != null) 1L else 0L
            was != sig -> System.currentTimeMillis()
            else -> prefs.getLong("common-at:$gid", 0L)
        }
        prefs.edit().putString("common-sig:$gid", sig).putLong("common-at:$gid", at).apply()
        at
    }

    /**
     * Another admin's changes to someone else's things (from that admin's share), so they show on this phone too
     * straight away, not only once the person's own phone has taken them over and shared them again.
     */
    private fun relayed(gid: String) = jsonList("relay:$gid")
    private fun saveRelayed(gid: String, list: List<JSONObject>) { prefs.edit().putString("relay:$gid", JSONArray(list.takeLast(200)).toString()).apply() }

    /** Your admin changes for other members of group [gid], not yet taken over by their phones. */
    private fun ops(gid: String) = jsonList("ops:$gid")
    private fun saveOps(gid: String, list: List<JSONObject>) { prefs.edit().putString("ops:$gid", JSONArray(list).toString()).apply() }
    /** Ids of admin changes this phone has applied to your own things (the admin's phone stops sending them). */
    private fun applied(gid: String): MutableList<String> = runCatching {
        val a = JSONArray(prefs.getString("applied:$gid", null) ?: return mutableListOf()); (0 until a.length()).map { a.getString(it) }.toMutableList()
    }.getOrDefault(mutableListOf())
    private fun docIdOf(op: JSONObject) = op.optJSONObject("doc")?.optString("id") ?: op.optString("doc")
    private fun newOp(to: String, kind: String) = JSONObject().put("id", java.util.UUID.randomUUID().toString()).put("to", to).put("at", System.currentTimeMillis()).put("kind", kind)
    private fun addOp(gid: String, op: JSONObject, replaces: (JSONObject) -> Boolean = { false }) = synchronized(prefs) { saveOps(gid, ops(gid).filterNot(replaces) + op) }

    private suspend fun requireAdmin(gid: String) {
        val g = dao.groupNow(gid) ?: error("Group not found")
        require(g.isAdmin(dao.selfNow()?.id)) { "Only a group admin can change other members' things" }
    }

    /** Whether [key] was removed from group [groupId] after [at] (an old request of theirs mustn't bring them back). */
    fun removedSince(groupId: String, key: String, at: Long): Boolean = synchronized(prefs) { (longMap("handled:$groupId")[key] ?: 0L) > at }

    /** Someone is added again (an invite, an approved request): earlier removals of them no longer count. */
    private fun readmit(gid: String, key: String) = synchronized(prefs) {
        val removed = longMap("removed:$gid"); if (removed.remove(key) != null) putLongMap("removed:$gid", removed)
        putLongMap("handled:$gid", longMap("handled:$gid").apply { put(key, System.currentTimeMillis()) })
    }

    override suspend fun putDoc(row: DocumentEntity, member: MemberEntity) {
        val gid = member.groupId ?: return; val to = row.origin ?: return
        requireAdmin(gid)
        val docId = row.id.removePrefix("s:$gid:$to:")
        val d = JSONObject().put("id", docId).put("common", member.shared).put("name", row.name).put("fields", row.fieldsJson).put("text", row.rawText)
            .put("ocr", row.readByOcr).put("country", row.country ?: JSONObject.NULL).put("display", row.display.name).put("flag", row.showFlag ?: JSONObject.NULL)
            .put("created", row.createdAt).put("updated", row.updatedAt).put("archived", row.archivedAt ?: JSONObject.NULL)
            .put("files", JSONArray(row.attachments.map { JSONObject().put("id", it.id).put("mime", it.mime) })).put("picture", row.pictureFile ?: JSONObject.NULL)
        addOp(gid, newOp(to, "doc").put("doc", d)) { it.optString("to") == to && it.optString("kind") in DOC_KINDS && docIdOf(it) == docId }
        BackupWorker.soon(context)
    }

    override suspend fun deleteDoc(row: DocumentEntity, member: MemberEntity?) {
        val to = row.origin ?: return
        val gid = member?.groupId ?: row.id.split(':').getOrNull(1) ?: return
        requireAdmin(gid)
        val docId = row.id.removePrefix("s:$gid:$to:")
        addOp(gid, newOp(to, "deldoc").put("doc", docId).put("name", row.name)) { it.optString("to") == to && it.optString("kind") in DOC_KINDS && docIdOf(it) == docId }
        BackupWorker.soon(context)
    }

    override suspend fun profile(row: MemberEntity, photoChanged: Boolean) {
        val gid = row.groupId ?: return; val to = row.origin ?: return
        requireAdmin(gid)
        val op = newOp(to, "profile").put("name", row.name).put("avatar", row.avatar ?: JSONObject.NULL)
        if (photoChanged) op.put("photo", row.photoFile ?: "")
        addOp(gid, op)
        BackupWorker.soon(context)
    }

    /** Removes [key] from the group: their phone leaves it, everyone else's stops reading their share. */
    override suspend fun removeMember(groupId: String, key: String) = withContext(Dispatchers.IO) {
        requireAdmin(groupId)
        val g = dao.groupNow(groupId) ?: return@withContext
        require(key != g.creator) { "The person who made the group can't be removed" }
        val now = System.currentTimeMillis()
        synchronized(prefs) {
            putLongMap("removed:$groupId", longMap("removed:$groupId").apply { put(key, now) })
            putLongMap("handled:$groupId", longMap("handled:$groupId").apply { put(key, now) })
            saveOps(groupId, ops(groupId).filter { it.optString("to") != key })
        }
        forget(groupId, key)
        dao.groupNow(groupId)?.let { if (key in it.adminKeys) dao.upsertGroup(it.copy(admins = (it.adminKeys - key - setOfNotNull(it.creator)).joinToString(",").ifEmpty { null }, adminsAt = now)) }
        BackupWorker.soon(context)
    }

    /** Makes [key] an admin of the group, or takes it back (not from the person who made the group). */
    suspend fun setAdmin(groupId: String, key: String, on: Boolean) = withContext(Dispatchers.IO) {
        requireAdmin(groupId)
        val g = dao.groupNow(groupId) ?: return@withContext
        if (key == g.creator) return@withContext
        val others = g.adminKeys - setOfNotNull(g.creator)
        val next = if (on) others + key else others - key
        dao.upsertGroup(g.copy(admins = next.joinToString(",").ifEmpty { null }, adminsAt = System.currentTimeMillis()))
        BackupWorker.soon(context)
    }

    /** Admin details in a share index: when you got the group, who made it, the admins, and the admin changes. */
    private fun meta(o: JSONObject, g: GroupEntity, me: MemberEntity, upload: ((String?) -> String?)?): JSONObject {
        o.put("since", g.createdAt).put("creator", g.creator ?: JSONObject.NULL).put("pub", devicePub())
            .put("admins", JSONObject().put("keys", JSONArray((g.adminKeys - setOfNotNull(g.creator)).toList())).put("at", g.adminsAt))
            .put("applied", JSONArray(synchronized(prefs) { applied(g.id) }))
        if (g.isAdmin(me.id)) {
            o.put("removed", JSONObject().apply { synchronized(prefs) { longMap("removed:${g.id}") }.forEach { (k, v) -> put(k, v) } })
            if (upload != null) o.put("ops", JSONArray(synchronized(prefs) { ops(g.id) }.mapNotNull { wire(it, upload) }))
        }
        return o
    }

    /** An op as it goes into the share: this phone's file ids become the uploaded Drive files. */
    private fun wire(op: JSONObject, upload: (String?) -> String?): JSONObject? {
        val w = JSONObject(op.toString())
        when (op.optString("kind")) {
            "doc" -> {
                val d = w.getJSONObject("doc")
                val fs = d.getJSONArray("files"); val out = JSONArray()
                for (i in 0 until fs.length()) { val f = fs.getJSONObject(i); out.put(JSONObject().put("f", upload(f.getString("id")) ?: return null).put("mime", f.optString("mime"))) }
                d.put("files", out)
                if (!d.isNull("picture")) d.put("picture", upload(d.getString("picture")) ?: return null)
            }
            "profile" -> if (w.optString("photo").isNotEmpty()) w.put("photo", upload(w.getString("photo")) ?: return null)
        }
        return w
    }

    /** An admin's changes addressed to you, from their share: applied to your own vault, then a note on Home. */
    private suspend fun applyOps(g: GroupEntity, j: JSONObject, by: String, myKey: String, key: ByteArray): Int {
        val list = j.optJSONArray("ops") ?: return 0
        val done = synchronized(prefs) { applied(g.id) }
        val before = done.size
        val what = mutableListOf<String>()
        for (i in 0 until list.length()) {
            val op = list.getJSONObject(i)
            if (op.optString("to") != myKey || op.optString("id") in done) continue
            val r = runCatching { applyOp(g, op, key) }
            if (r.isFailure) continue // (a file didn't download: tried again next time)
            done += op.getString("id"); r.getOrNull()?.let { what += it }
        }
        if (done.size != before) synchronized(prefs) { prefs.edit().putString("applied:${g.id}", JSONArray(done.takeLast(300)).toString()).apply() }
        if (what.isNotEmpty()) {
            addNotice(g, by, "$by (admin of ${g.name}) made changes: ${what.joinToString("; ")}.")
            BackupWorker.soon(context) // your share and backup now carry them
        }
        return what.size
    }

    private suspend fun applyOp(g: GroupEntity, op: JSONObject, key: ByteArray): String? {
        fun need(driveId: String) = fetchFile(driveId, key) ?: error("Couldn't download a file")
        when (op.optString("kind")) {
            "profile" -> {
                val self = dao.selfNow() ?: error("No account on this phone"); val acct = dao.accountNow()
                var s = self; val what = mutableListOf<String>()
                op.optStringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() && it != (acct?.name ?: self.name) }?.let { s = s.copy(name = it); what += "your name is now $it" }
                if (op.has("photo")) {
                    val ph = op.optString("photo"); val id = if (ph.isEmpty()) null else need(ph)
                    if (id != self.photoFile) {
                        setOfNotNull(self.photoFile, acct?.photoFile).filter { it != id }.forEach { files.delete(it) }
                        s = s.copy(photoFile = id); what += if (id == null) "removed your photo" else "changed your photo"
                    }
                }
                op.optStringOrNull("avatar")?.takeIf { it != self.avatar }?.let { s = s.copy(avatar = it); if (s.photoFile == null) what += "changed your picture" }
                if (s != self) { dao.upsertMember(s); acct?.let { dao.upsertAccount(it.copy(name = s.name, photoFile = s.photoFile)) } }
                return what.joinToString(", ").ifEmpty { null }
            }
            "doc" -> {
                val d = op.getJSONObject("doc"); val id = d.getString("id")
                val old = dao.documentNow(id)
                require(old == null || old.origin == null) { "Not one of yours" }
                val member = old?.memberId ?: ((if (d.optBoolean("common")) dao.sharedIn(g.id) else dao.selfNow()) ?: error("No vault for it")).id
                val atts = mutableListOf<Attachment>()
                d.getJSONArray("files").forEachObj { f -> atts += Attachment(need(f.getString("f")), f.optString("mime", "image/jpeg")) }
                val pic = d.optStringOrNull("picture")?.let { need(it) }
                old?.let { o -> (o.attachments.map { it.id } + listOfNotNull(o.pictureFile)).filter { f -> atts.none { it.id == f } && f != pic }.forEach { files.delete(it) } }
                dao.upsertDocument(DocumentEntity(
                    id = id, memberId = member, name = d.getString("name"), fileId = atts.firstOrNull()?.id, fileMime = atts.firstOrNull()?.mime,
                    display = runCatching { DisplayMode.valueOf(d.getString("display")) }.getOrDefault(DisplayMode.ICON),
                    pictureFile = pic, fieldsJson = d.optString("fields", "[]"), rawText = d.optString("text"), readByOcr = d.optBoolean("ocr"),
                    country = d.optStringOrNull("country"), createdAt = old?.createdAt ?: d.optLong("created", System.currentTimeMillis()),
                    updatedAt = d.optLong("updated", System.currentTimeMillis()), showFlag = if (d.isNull("flag")) null else d.optBoolean("flag"),
                    moreFiles = Attachment.encode(atts.drop(1)), sortOrder = old?.sortOrder, origin = null,
                    archivedAt = if (!d.has("archived") || d.isNull("archived")) null else d.getLong("archived"),
                ))
                return "${if (old == null) "added" else "edited"} “${d.getString("name")}”"
            }
            "deldoc" -> {
                val old = dao.documentNow(op.optString("doc"))?.takeIf { it.origin == null } ?: return null
                old.attachments.forEach { files.delete(it.id) }; files.delete(old.pictureFile); dao.deleteDocument(old.id)
                return "removed “${old.name}”"
            }
            else -> return null
        }
    }

    /** Admin changes [peerKey]'s phone lists as applied: no longer sent. Returns them, for [cleanup]. */
    private fun retire(gid: String, peerKey: String, theirs: JSONArray?): List<JSONObject> {
        theirs ?: return emptyList()
        val done = (0 until theirs.length()).map { theirs.getString(it) }.toSet()
        return synchronized(prefs) {
            val (out, keep) = ops(gid).partition { it.optString("to") == peerKey && it.optString("id") in done }
            if (out.isNotEmpty()) saveOps(gid, keep)
            out
        }
    }

    /** This phone's copies of files that only finished admin changes still pointed to. */
    private suspend fun cleanup(gone: List<JSONObject>) {
        if (gone.isEmpty()) return
        val ids = gone.flatMap { op ->
            val d = op.optJSONObject("doc")
            val fs = d?.optJSONArray("files")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }.orEmpty()
            fs + listOfNotNull(d?.optStringOrNull("picture"), op.optStringOrNull("photo"))
        }.toSet()
        if (ids.isEmpty()) return
        val used = mutableSetOf<String>()
        dao.allDocumentsNow().forEach { d -> d.attachments.forEach { used += it.id }; d.pictureFile?.let { used += it } }
        dao.membersNow().forEach { m -> m.photoFile?.let { used += it } }
        dao.accountNow()?.photoFile?.let { used += it }
        dao.groupsNow().forEach { g -> g.photoFile?.let { used += it }; ops(g.id).forEach { op -> op.optJSONObject("doc")?.optJSONArray("files")?.let { a -> for (i in 0 until a.length()) used += a.getJSONObject(i).getString("id") } } }
        (ids - used).forEach { files.delete(it) }
    }

    /** An admin removed you: the group stays on this phone with your own things, without the others. */
    private suspend fun leave(g: GroupEntity, by: String) {
        g.peerList.forEach { removeFrom(g, it.key, emptySet(), emptySet(), emptySet()) }
        dao.upsertGroup(g.copy(peers = null, creator = null, admins = null, adminsAt = 0L))
        synchronized(prefs) { saveOps(g.id, emptyList()) }
        addNotice(g, by, "$by removed you from ${g.name}. Your own documents and the Common vault stay on this phone. If it was a mistake, ask someone in it for a new invite.")
    }

    /**
     * Who made the group, for groups from before admins: the member who has had it longest (their "since"), once
     * everyone's app says. Alone in a group, it's you.
     */
    private suspend fun settleCreator(gid: String, me: MemberEntity) {
        val g = dao.groupNow(gid) ?: return
        if (g.creator != null) return
        val since = g.peerList.associate { it.key to prefs.getLong("since:$gid:${it.key}", 0L) }
        if (since.values.any { it == 0L }) return
        val creator = (since + (me.id to g.createdAt)).minByOrNull { it.value }!!.key
        dao.upsertGroup(g.copy(creator = creator))
    }

    /** [key]'s events that are no longer shared with you (or the whole lot, when they leave): gone from this phone. */
    private suspend fun removeEvents(g: GroupEntity, key: String, keep: Set<String> = emptySet()) {
        dao.eventsNow().filter { it.id.startsWith("s:${g.id}:$key:") && it.id !in keep }.forEach { e ->
            dao.eventDocumentsNow(e.id).forEach { d -> d.attachments.forEach { files.delete(it.id) }; files.delete(d.pictureFile); dao.deleteDocument(d.id) }
            files.delete(e.photoFile); dao.deleteEventRow(e.id); prefs.edit().remove("notes-base:${e.id}").apply()
        }
    }

    private suspend fun removeFrom(g: GroupEntity, key: String, keepDocs: Set<String>, keepMembers: Set<String>, keepPws: Set<String>) {
        val prefix = "s:${g.id}:$key:%"
        if (keepMembers.isEmpty()) { removeEvents(g, key); prefs.edit().remove("seen:${g.id}:$key").apply() }
        dao.receivedDocuments(key, prefix).filter { it.id !in keepDocs }.forEach { d ->
            d.attachments.forEach { files.delete(it.id) }; files.delete(d.pictureFile); dao.deleteDocument(d.id)
        }
        dao.receivedPasswords(key, prefix).filter { it.id !in keepPws }.forEach { dao.deletePassword(it.id) }
        dao.receivedMembers(key, g.id).filter { it.id !in keepMembers }.forEach { files.delete(it.photoFile); dao.deleteMember(it.id) }
    }

    /** A shared file, kept on the phone (encrypted like everything else) under an id made from its Drive id: downloaded once. */
    private fun fetchFile(driveId: String, key: ByteArray): String? {
        val id = "s-$driveId"
        if (files.has(id)) return id
        return runCatching { files.put(BackupCrypto.open(DriveClient.publicDownload(driveId), key), id) }.getOrNull()
    }

    private inline fun JSONArray.forEachObj(f: (JSONObject) -> Unit) { for (i in 0 until length()) f(getJSONObject(i)) }
    private fun JSONObject.optStringOrNull(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifEmpty { null }
    private fun sha(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val P_SHARE = "fd_share"; const val P_SHARE_FILE = "fd_share_file"
        private val DOC_KINDS = setOf("doc", "deldoc")
        private const val OP_LIFE = 60L * 24 * 3600 * 1000
        /** An unchanged share is still read in full this often, just in case. */
        private const val FULL_READ = 10L * 60 * 1000
        /** How often the share's Drive folder is looked up again (it's remembered in between). */
        private const val FOLDER_CHECK = 6L * 3600 * 1000
        /** For a week after someone is removed, other members' (older) lists of who's in the group don't bring them back. */
        private const val REMOVED_QUIET = 7L * 24 * 3600 * 1000
        private const val OCTET = "application/octet-stream"
    }
}
