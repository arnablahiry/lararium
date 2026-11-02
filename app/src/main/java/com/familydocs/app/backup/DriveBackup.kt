package com.familydocs.app.backup

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.familydocs.app.data.AccountEntity
import com.familydocs.app.data.DisplayMode
import com.familydocs.app.data.Attachment
import com.familydocs.app.data.DocumentEntity
import com.familydocs.app.data.FileStore
import com.familydocs.app.data.MemberEntity
import com.familydocs.app.data.PasswordEntity
import com.familydocs.app.data.VaultRepository
import com.familydocs.app.domain.Countries
import com.familydocs.app.domain.DocTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup to the user's own Google Drive, laid out so it makes sense when browsed:
 *
 *   My Drive
 *   └─ Lararium/                              ← root (label fd_root)
 *      ├─ Lararium vault.fdenc                ← index: account, people, document list
 *      ├─ Common (shared)/                       ← one folder per person / the Common vault (label fd_member)
 *      │  ├─ Passwords.fdenc
 *      │  └─ Rental Agreement · Greece/          ← one folder per document (label fd_doc, coloured by type)
 *      │     ├─ original.jpg.fdenc               ← the scan / PDF, encrypted
 *      │     ├─ details.fdenc                    ← digitised fields, encrypted
 *      │     └─ original.jpg                     ← optional readable copy (Settings → "Viewable copies in Drive")
 *      ├─ Arnab/
 *      │  ├─ Aadhaar · India/ …
 *      │  └─ Passport · India/ …
 *      └─ Mohua/ …
 *
 * Every file and folder carries Drive appProperties ("labels": member, document id, type, country, expiry,
 * last-changed time) and a human description, so Drive search works and only changed documents are re-uploaded.
 * Encryption: AES-256-GCM (see [BackupCrypto]) with a random key that lives on the phone and in the Google
 * account's hidden app folder (scope drive.appdata), so signing in with Google on a new phone is enough to
 * restore. Names are readable, contents are not. Scope drive.file: the app only sees files it created.
 */
class DriveBackup(
    private val context: Context,
    private val repo: VaultRepository,
    private val files: FileStore,
    val keys: BackupKeyStore,
    private val passcode: com.familydocs.app.security.PasscodeManager? = null,
    /** The optional backup password for logins: Passwords files get a second seal only it opens. */
    val loginLock: LoginLock? = null,
) {
    /** The passcode check found by the last [restore] (sealed in the backup), so signing in again keeps your passcode. */
    var restoredPasscode: String? = null; private set

    /** Same grant as "Continue with Google"; if the result has a resolution, the UI must launch it once. */
    suspend fun authorize(): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(com.familydocs.app.auth.GoogleAccountHelper(context).request()).await()

    /** The backup key for this Google account: the one already in Drive's hidden app folder wins, so every phone shares it. */
    private fun keyFor(drive: DriveClient): BackupKeyStore.Key {
        val remote = drive.appDataRead(KEY_FILE)?.let { runCatching { parseKey(it) }.getOrNull() }
        val local = keys.current()
        return when {
            remote != null -> { if (local == null || !local.id.contentEquals(remote.id)) keys.install(remote); remote }
            local != null -> local.also { drive.appDataWrite(KEY_FILE, keyJson(it)) }
            else -> keys.generate().also { drive.appDataWrite(KEY_FILE, keyJson(it)) }
        }
    }

    private fun keyJson(k: BackupKeyStore.Key) = JSONObject().put("v", 1)
        .put("id", android.util.Base64.encodeToString(k.id, android.util.Base64.NO_WRAP))
        .put("key", android.util.Base64.encodeToString(k.key, android.util.Base64.NO_WRAP)).toString().toByteArray()

    private fun parseKey(b: ByteArray): BackupKeyStore.Key = JSONObject(String(b)).let {
        BackupKeyStore.Key(android.util.Base64.decode(it.getString("key"), android.util.Base64.NO_WRAP), android.util.Base64.decode(it.getString("id"), android.util.Base64.NO_WRAP))
    }

    /** True when this Google account already has a Lararium backup. */
    suspend fun hasBackup(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        val drive = DriveClient(accessToken)
        drive.findByProp(P_ROOT, "1") != null && drive.appDataFind(KEY_FILE) != null
    }

    /** Permanently removes the Lararium folder and the backup key from this Google account's Drive. */
    suspend fun deleteFromDrive(accessToken: String) = withContext(Dispatchers.IO) {
        forgetManifest()
        val drive = DriveClient(accessToken)
        drive.findByProp(P_ROOT, "1")?.let { drive.delete(it.id) }
        drive.appDataDelete(KEY_FILE)
        drive.appDataDelete(PASSCODE_FILE)
    }

    data class Report(val uploaded: Int, val unchanged: Int, val removed: Int)

    /**
     * What the last successful backup put in Drive (a fingerprint per document, a hash of each passwords file, photo,
     * events and index), kept on the phone. Anything unchanged since then is skipped without even asking Drive.
     * A full check against Drive runs once a week, and whenever the Drive folder or the backup key is different.
     */
    private val manifest = context.getSharedPreferences("drive_backup_manifest", Context.MODE_PRIVATE)
    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun sha(s: String) = sha(s.toByteArray())
    /** After sign out / delete account: the next backup checks everything again. */
    fun forgetManifest() = manifest.edit().clear().apply()

    /** One backup at a time (the daily one, one after a change, "Back up now"), so files are never uploaded twice. */
    private val running = Mutex()

    suspend fun backup(accessToken: String, viewableCopies: Boolean = false, onProgress: (String) -> Unit = {}): Report = running.withLock { withContext(Dispatchers.IO) {
        val drive = DriveClient(accessToken)
        val everything = repo.snapshot()
        val s = scoped(everything)
        val k = keyFor(drive); val key = k.key; val salt = k.id
        fun seal(b: ByteArray) = BackupCrypto.seal(b, key, salt)
        val selfName = s.account?.name ?: "Me"

        onProgress("Preparing Lararium folder…")
        val root = drive.folder("Lararium", null, mapOf(P_ROOT to "1"), "Encrypted backup made by the Lararium app for $selfName. Open it with the app.")
        var uploaded = 0; var unchanged = 0; var removed = 0
        val keyId = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)
        val now = System.currentTimeMillis()
        val full = manifest.getString("root", null) != root || manifest.getString("key", null) != keyId ||
            now - manifest.getLong("full_at", 0L) > 7L * 24 * 60 * 60 * 1000
        val seen = mutableMapOf<String, String>()
        /** true when [k] is unchanged since the last backup (and this isn't the weekly full check); remembers [v] either way */
        fun same(k: String, v: String): Boolean { seen[k] = v; return !full && manifest.getString(k, null) == v }
        // Your passcode check (salt + hash, sealed), in the hidden app folder: signing in on any phone keeps the same passcode.
        passcode?.export()?.let { pc -> if (!same("passcode", sha(pc))) drive.appDataWrite(PASSCODE_FILE, seal(pc.toByteArray())) }

        // My Drive › Lararium › <your folder> (your documents are in every group) and one folder per group
        // (Family, Flatmates…) holding that group's people. Existing folders are moved in, not uploaded again.
        val groupFolders = s.groups.associate { g -> g.id to drive.folder(g.name, root, mapOf(P_GROUP to g.id), "Lararium group · its people, Common vault and events") }
        // Group pictures you picked from your gallery (templates are just a name in the index)
        s.groups.forEach { g ->
            val folder = groupFolders.getValue(g.id)
            if (!same("gphoto:${g.id}", g.photoFile ?: "-")) g.photoFile?.let { files.get(it) }?.let { photo ->
                drive.upsertFile(folder, "group-photo.jpg.fdenc", seal(photo), OCTET, mapOf(P_KIND to "group-photo", P_DOC_FILE to "group:${g.id}:photo"), null, P_DOC_FILE)
            }
        }
        val memberFolders = mutableMapOf<String, String>()
        s.members.forEach { m ->
            val title = if (m.shared) "${m.name} (shared)" else m.name
            val parent = m.groupId?.let { groupFolders[it] } ?: root
            memberFolders[m.id] = drive.folderMovedInto(title, parent, mapOf(P_MEMBER to m.id), if (m.shared) "Shared with the whole group" else m.relation)
        }

        s.documents.forEachIndexed { i, d ->
            // Unchanged since the last backup: nothing to ask Drive, nothing to upload.
            if (same("doc:${d.id}", "${d.updatedAt}:${d.attachments.size}:$viewableCopies")) { unchanged++; return@forEachIndexed }
            onProgress("Backing up ${i + 1} of ${s.documents.size}: ${d.name}")
            val parent = memberFolders[d.memberId] ?: return@forEachIndexed
            val type = DocTypes.forName(d.name)
            val country = d.issuer
            val folderName = listOfNotNull(d.name, country.takeIf { it.isNotEmpty() }?.let { Countries.name(it) }).joinToString(" · ")
            val expiry = d.fields.firstOrNull { Regex("expiry|valid until|valid till", RegexOption.IGNORE_CASE).containsMatchIn(it.label) && it.value.isNotBlank() }?.value
            val labels = buildMap {
                put(P_DOC, d.id); put(P_MEMBER_OF, d.memberId); put(P_TYPE, type.key)
                if (country.isNotEmpty()) put(P_COUNTRY, country)
                expiry?.let { put(P_EXPIRY, it) }
            }
            val description = listOfNotNull(type.label.takeIf { type.matched }, s.members.firstOrNull { it.id == d.memberId }?.name,
                country.takeIf { it.isNotEmpty() }?.let { Countries.name(it) }, expiry?.let { "valid until $it" }).joinToString(" · ")
            val docFolder = drive.folder(folderName, parent, labels, description, color = hex(type.hue))

            val remote = drive.children(docFolder)
            val stamp = d.updatedAt.toString()
            val upToDate = remote.any { it.props[P_KIND] == "details" && it.props[P_UPDATED] == stamp } &&
                (d.fileId == null || remote.any { it.props[P_KIND] == "original" && it.props[P_UPDATED] == stamp }) &&
                (!viewableCopies || d.fileId == null || remote.any { it.props[P_KIND] == "plain" && it.props[P_UPDATED] == stamp }) &&
                d.attachments.drop(1).indices.all { n -> remote.any { it.props[P_KIND] == "extra" && it.props["n"] == "$n" && it.props[P_UPDATED] == stamp } }
            if (upToDate) { unchanged++; return@forEachIndexed }

            val ext = when (d.fileMime) { "application/pdf" -> "pdf"; "image/png" -> "png"; else -> "jpg" }
            d.fileId?.let { files.get(it) }?.let { raw ->
                drive.upsertFile(docFolder, "original.$ext.fdenc", seal(raw), OCTET, mapOf(P_KIND to "original", P_DOC_FILE to "${d.id}:original", P_UPDATED to stamp, "ext" to ext), "Encrypted $description", P_DOC_FILE)
                if (viewableCopies) drive.upsertFile(docFolder, "original.$ext", raw, d.fileMime ?: "image/jpeg",
                    mapOf(P_KIND to "plain", P_DOC_FILE to "${d.id}:plain", P_UPDATED to stamp), "Readable copy: $description", P_DOC_FILE)
            }
            // Further pages / back side: extra-0, extra-1… (same encryption; readable copies only if turned on)
            val extras = d.attachments.drop(1)
            extras.forEachIndexed { n, a ->
                val x = when (a.mime) { "application/pdf" -> "pdf"; "image/png" -> "png"; else -> "jpg" }
                files.get(a.id)?.let { raw ->
                    drive.upsertFile(docFolder, "page-${n + 2}.$x.fdenc", seal(raw), OCTET, mapOf(P_KIND to "extra", "n" to "$n", P_DOC_FILE to "${d.id}:extra:$n", P_UPDATED to stamp, "ext" to x), "Encrypted page ${n + 2}: $description", P_DOC_FILE)
                    if (viewableCopies) drive.upsertFile(docFolder, "page-${n + 2}.$x", raw, a.mime,
                        mapOf(P_KIND to "plain-extra", "n" to "$n", P_DOC_FILE to "${d.id}:plain-extra:$n", P_UPDATED to stamp), "Readable copy: $description", P_DOC_FILE)
                }
            }
            remote.filter { (it.props[P_KIND] == "extra" || it.props[P_KIND] == "plain-extra") && (it.props["n"]?.toIntOrNull() ?: 0) >= extras.size }.forEach { drive.trash(it.id) }
            d.pictureFile?.let { files.get(it) }?.let { pic ->
                drive.upsertFile(docFolder, "picture.jpg.fdenc", seal(pic), OCTET, mapOf(P_KIND to "picture", P_DOC_FILE to "${d.id}:picture", P_UPDATED to stamp), null, P_DOC_FILE)
            }
            drive.upsertFile(docFolder, "details.fdenc", seal(docJson(d).toString().toByteArray()), OCTET,
                mapOf(P_KIND to "details", P_DOC_FILE to "${d.id}:details", P_UPDATED to stamp), "Digitised details, encrypted", P_DOC_FILE)
            if (!viewableCopies) remote.filter { it.props[P_KIND] == "plain" || it.props[P_KIND] == "plain-extra" }.forEach { drive.trash(it.id) }
            uploaded++
        }

        onProgress("Saving passwords and photos…")
        // Logins restored on this phone still waiting for the backup password: leave the Passwords files in Drive alone.
        val loginsWaiting = loginLock?.waitingList()?.isNotEmpty() == true
        s.members.forEach { m ->
            val folder = memberFolders.getValue(m.id)
            val pws = s.passwords.filter { it.memberId == m.id }
            // Passwords are ALWAYS sealed. "Viewable copies" only ever applies to document scans, never to logins or fields.
            // With a backup password, sealed twice: inside, with the key made from that password.
            val pwJsonText = JSONArray(pws.map { pwJson(it) }).toString()
            if (!loginsWaiting && !same("pw:${m.id}", sha(pwJsonText + "|" + (loginLock?.stamp() ?: "off")))) {
                val locked = loginLock?.seal(pwJsonText.toByteArray())
                val body = locked?.let { JSONObject().put("v", 2).put("locked", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)).toString() } ?: pwJsonText
                drive.upsertFile(folder, "Passwords.fdenc", seal(body.toByteArray()), OCTET, mapOf(P_KIND to "passwords", P_DOC_FILE to "${m.id}:passwords"),
                    "${pws.size} saved logins, encrypted" + if (locked != null) " (and locked with your backup password)" else "", P_DOC_FILE)
            }
            if (!same("photo:${m.id}", m.photoFile ?: "-")) m.photoFile?.let { files.get(it) }?.let { photo ->
                drive.upsertFile(folder, "photo.jpg.fdenc", seal(photo), OCTET, mapOf(P_KIND to "photo", P_DOC_FILE to "${m.id}:photo"), null, P_DOC_FILE)
            }
        }
        if (!same("account-photo", s.account?.photoFile ?: "-")) s.account?.photoFile?.let { files.get(it) }?.let { photo ->
            drive.upsertFile(root, "account-photo.jpg.fdenc", seal(photo), OCTET, mapOf(P_KIND to "account-photo", P_DOC_FILE to "account:photo"), null, P_DOC_FILE)
        }

        // Anything in Drive that no longer exists on the phone goes to Drive's bin (recoverable for 30 days).
        onProgress("Tidying up…")
        // (everything still on the phone counts as live: switching a group back to "Me + Common" doesn't delete older copies)
        val liveDocs = everything.documents.map { it.id }.toSet(); val liveMembers = everything.members.map { it.id }.toSet()
        // only when documents or people were added/removed since the last backup
        if (!same("tidy", sha((liveDocs.sorted() + "|" + liveMembers.sorted() + "|" + s.groups.map { it.id + it.name }.sorted()).joinToString(",")))) memberFolders.values.forEach { mf ->
            drive.children(mf).filter { it.mime == DriveClient.FOLDER && it.props[P_DOC] != null && it.props[P_DOC] !in liveDocs }.forEach { drive.trash(it.id); removed++ }
        }
        if (manifest.getString("tidy", null) != seen["tidy"] || full) {
            val liveGroups = s.groups.map { it.id }.toSet()
            (listOf(root) + groupFolders.values).forEach { parent ->
                drive.children(parent).filter { it.mime == DriveClient.FOLDER && it.props[P_MEMBER] != null && it.props[P_MEMBER] !in liveMembers }.forEach { drive.trash(it.id); removed++ }
            }
            drive.children(root).filter { it.mime == DriveClient.FOLDER && it.props[P_GROUP] != null && it.props[P_GROUP] !in liveGroups }.forEach { drive.trash(it.id); removed++ }
        }

        onProgress("Writing index…")
        val index = JSONObject()
            .put("format", 2)
            .put("account", s.account?.let { accountJson(it) } ?: JSONObject.NULL)
            .put("members", JSONArray(s.members.map { memberJson(it) }))
            .put("groups", JSONArray(s.groups.map { JSONObject().put("id", it.id).put("name", it.name).put("code", it.code).put("created", it.createdAt)
                .put("all", it.backupAll).put("pic", it.pic ?: JSONObject.NULL).put("photo", it.photoFile != null).put("shareFolder", it.shareFolder ?: JSONObject.NULL).put("shareIndex", it.shareIndex ?: JSONObject.NULL).put("peers", it.peers ?: JSONObject.NULL)
                .put("creator", it.creator ?: JSONObject.NULL).put("admins", it.admins ?: JSONObject.NULL).put("adminsAt", it.adminsAt) }))
            .put("documents", JSONArray(s.documents.map { JSONObject().put("id", it.id).put("member", it.memberId).put("order", it.sortOrder ?: JSONObject.NULL) }))
        val indexText = index.toString()
        if (!same("index", sha(indexText))) drive.upsertFile(root, "Lararium vault.fdenc", seal(indexText.toByteArray()), OCTET, mapOf(P_KIND to "index", P_DOC_FILE to "index"),
            "Index of people and documents, encrypted", P_DOC_FILE)
        // Events (dates, who's going, who can see them, pinned documents, reminder, notes, photo): one encrypted file.
        // Their tickets/bookings are ordinary documents (with an event id) inside the creator's folder above.
        val eventsKey = sha(JSONArray(s.events.map { e -> eventJson(e).put("photo", e.photoFile ?: JSONObject.NULL) }).toString())
        if (!same("events", eventsKey)) {
            val events = JSONArray(s.events.map { e -> eventJson(e).put("photo", e.photoFile?.let { files.get(it) }?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) } ?: JSONObject.NULL) })
            drive.upsertFile(root, "Events.fdenc", seal(events.toString().toByteArray()), OCTET, mapOf(P_KIND to "events", P_DOC_FILE to "events"),
                "${s.events.size} events, encrypted", P_DOC_FILE)
        }
        // Everything went up: remember it for next time (only what still exists, so the record never grows stale).
        manifest.edit().clear().apply {
            putString("root", root); putString("key", keyId); putLong("full_at", if (full) now else manifest.getLong("full_at", now))
            seen.forEach { (k, v) -> putString(k, v) }
        }.apply()
        Report(uploaded, unchanged, removed)
    } }

    /** Rebuilds the vault from Drive using the key in the hidden app folder. Any failure → nothing on the phone changes. */
    suspend fun restore(accessToken: String, onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        val drive = DriveClient(accessToken)
        val root = drive.findByProp(P_ROOT, "1") ?: error("No Lararium backup found in this Google account")
        val indexFile = drive.children(root.id).firstOrNull { it.props[P_KIND] == "index" } ?: error("Backup index is missing")
        val k = drive.appDataRead(KEY_FILE)?.let { parseKey(it) } ?: error("This backup's key is missing from Google Drive, so it can't be opened")
        val key = k.key
        val index = JSONObject(String(BackupCrypto.open(drive.download(indexFile.id), key)))
        fun open(b: ByteArray): ByteArray = BackupCrypto.open(b, key)
        restoredPasscode = runCatching { drive.appDataRead(PASSCODE_FILE)?.let { String(open(it)) } }.getOrNull()

        val blobs = mutableMapOf<String, ByteArray>()
        val members = index.getJSONArray("members").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val docs = mutableListOf<DocumentEntity>(); val pws = mutableListOf<PasswordEntity>(); val memberEntities = mutableListOf<MemberEntity>()
        val lockedLogins = mutableListOf<Pair<String, ByteArray>>()
        val rootChildren = drive.children(root.id)
        members.forEachIndexed { mi, mj ->
            onProgress("Restoring ${mj.getString("name")} (${mi + 1} of ${members.size})…")
            // at the top (you, older backups) or inside the person's group folder
            val folder = rootChildren.firstOrNull { it.props[P_MEMBER] == mj.getString("id") } ?: drive.findByProp(P_MEMBER, mj.getString("id")) ?: return@forEachIndexed
            val inside = drive.children(folder.id)
            var photoId: String? = null
            inside.firstOrNull { it.props[P_KIND] == "photo" }?.let { f -> photoId = "m-" + mj.getString("id"); blobs[photoId!!] = open(drive.download(f.id)) }
            memberEntities += memberFromJson(mj, photoId)
            inside.firstOrNull { it.props[P_KIND] == "passwords" }?.let { f ->
                val text = String(open(drive.download(f.id)))
                // locked with the backup password: kept sealed until it's entered on this phone
                if (text.trimStart().startsWith("{")) JSONObject(text).optString("locked").takeIf { it.isNotEmpty() }
                    ?.let { lockedLogins += mj.getString("id") to android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
                else { val arr = JSONArray(text); for (i in 0 until arr.length()) pws += pwFromJson(arr.getJSONObject(i)) }
            }
            inside.filter { it.mime == DriveClient.FOLDER && it.props[P_DOC] != null }.forEach { df ->
                val parts = drive.children(df.id)
                val details = parts.firstOrNull { it.props[P_KIND] == "details" } ?: return@forEach
                val dj = JSONObject(String(open(drive.download(details.id))))
                val fileId = parts.firstOrNull { it.props[P_KIND] == "original" }?.let { f -> ("d-" + dj.getString("id") + "-file").also { blobs[it] = open(drive.download(f.id)) } }
                val picId = parts.firstOrNull { it.props[P_KIND] == "picture" }?.let { f -> ("d-" + dj.getString("id") + "-pic").also { blobs[it] = open(drive.download(f.id)) } }
                val mimes = dj.optJSONArray("more")
                val more = parts.filter { it.props[P_KIND] == "extra" }.sortedBy { it.props["n"]?.toIntOrNull() ?: 0 }.mapIndexed { n, f ->
                    val id = "d-" + dj.getString("id") + "-x$n"
                    blobs[id] = open(drive.download(f.id))
                    Attachment(id, mimes?.optString(n)?.takeIf { it.isNotEmpty() } ?: if (f.props["ext"] == "pdf") "application/pdf" else "image/jpeg")
                }
                docs += docFromJson(dj, fileId, picId).copy(moreFiles = Attachment.encode(more))
            }
        }
        // Card order (hold and drag) lives in the index, so reordering doesn't re-upload documents.
        val order = index.optJSONArray("documents")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }
            .filter { !it.isNull("order") }.associate { it.getString("id") to it.getInt("order") } }.orEmpty()
        docs.replaceAll { d -> order[d.id]?.let { d.copy(sortOrder = it) } ?: d }
        var accountPhoto: String? = null
        rootChildren.firstOrNull { it.props[P_KIND] == "account-photo" }?.let { f -> accountPhoto = "account-photo"; blobs["account-photo"] = open(drive.download(f.id)) }
        val account = index.optJSONObject("account")?.let { accountFromJson(it, accountPhoto) }

        val events = rootChildren.firstOrNull { it.props[P_KIND] == "events" }?.let { f ->
            val arr = JSONArray(String(open(drive.download(f.id))))
            (0 until arr.length()).map { i ->
                val j = arr.getJSONObject(i)
                val photoId = if (j.isNull("photo")) null else ("e-" + j.getString("id")).also { blobs[it] = android.util.Base64.decode(j.getString("photo"), android.util.Base64.NO_WRAP) }
                eventFromJson(j, photoId)
            }
        }.orEmpty()
        // Everything downloaded and decrypted: only now replace what's on the phone.
        // (groups before saving: a group's own photo is one of the downloaded files)
        val groups = index.optJSONArray("groups")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }
            .map { com.familydocs.app.data.GroupEntity(it.getString("id"), it.getString("name"), it.getString("code"), it.optLong("created"),
                backupAll = it.optBoolean("all"), shareFolder = it.str("shareFolder"), shareIndex = it.str("shareIndex"), peers = it.str("peers"),
                creator = it.str("creator"), admins = it.str("admins"), adminsAt = it.optLong("adminsAt"),
                pic = it.str("pic"), photoFile = if (it.optBoolean("photo")) drive.findByProp(P_DOC_FILE, "group:${it.getString("id")}:photo")
                    ?.let { f -> ("g-" + it.getString("id")).also { id -> blobs[id] = open(drive.download(f.id)) } } else null) } }.orEmpty()
        onProgress("Saving to this phone…")
        files.clear()
        blobs.forEach { (id, data) -> files.put(data, id) }
        repo.restore(VaultRepository.Snapshot(account, memberEntities, docs, pws, events, groups))
        loginLock?.keepWaiting(lockedLogins)
        keys.install(k)
        com.familydocs.app.events.EventReminders.rescheduleAll(context, events)
    }

    // ---------- JSON ----------
    private fun docJson(d: DocumentEntity) = JSONObject().put("id", d.id).put("member", d.memberId).put("name", d.name).put("mime", d.fileMime ?: JSONObject.NULL)
        .put("display", d.display.name).put("fields", d.fieldsJson).put("text", d.rawText).put("ocr", d.readByOcr)
        .put("country", d.country ?: JSONObject.NULL).put("created", d.createdAt).put("updated", d.updatedAt).put("event", d.eventId ?: JSONObject.NULL)
        .put("flag", d.showFlag ?: JSONObject.NULL).put("origin", d.origin ?: JSONObject.NULL).put("archived", d.archivedAt ?: JSONObject.NULL)
        .put("more", JSONArray(d.attachments.drop(1).map { it.mime }))

    private fun docFromJson(j: JSONObject, fileId: String?, picId: String?) = DocumentEntity(
        id = j.getString("id"), memberId = j.getString("member"), name = j.getString("name"), fileId = fileId,
        fileMime = if (j.isNull("mime")) null else j.getString("mime"),
        display = runCatching { DisplayMode.valueOf(j.getString("display")) }.getOrDefault(DisplayMode.ICON), pictureFile = picId,
        fieldsJson = j.optString("fields", "[]"), rawText = j.optString("text"), readByOcr = j.optBoolean("ocr"),
        country = if (j.isNull("country")) null else j.optString("country"), createdAt = j.optLong("created"), updatedAt = j.optLong("updated"),
        eventId = if (!j.has("event") || j.isNull("event")) null else j.getString("event"),
        showFlag = if (!j.has("flag") || j.isNull("flag")) null else j.getBoolean("flag"),
        origin = j.str("origin"), archivedAt = if (!j.has("archived") || j.isNull("archived")) null else j.getLong("archived"),
    )

    private fun eventJson(e: com.familydocs.app.data.EventEntity) = JSONObject().put("id", e.id).put("name", e.name).put("art", e.art)
        .put("start", e.startDay).put("end", e.endDay ?: JSONObject.NULL).put("people", e.people).put("visible", e.visibleTo).put("pins", e.pins)
        .put("remind", e.remindDays).put("notes", e.notes).put("created", e.createdAt).put("group", e.groupId ?: JSONObject.NULL).put("at", e.updatedAt)
    private fun eventFromJson(j: JSONObject, photo: String?) = com.familydocs.app.data.EventEntity(
        id = j.getString("id"), name = j.getString("name"), art = j.optString("art", "trip"), photoFile = photo, startDay = j.getString("start"),
        endDay = if (j.isNull("end")) null else j.optString("end"), people = j.optString("people"), visibleTo = j.optString("visible"),
        pins = j.optString("pins"), remindDays = j.optInt("remind", -1), notes = j.optString("notes"), createdAt = j.optLong("created"),
        groupId = if (!j.has("group") || j.isNull("group")) null else j.getString("group"), updatedAt = j.optLong("at"),
    )

    /** Brings back logins a restore found locked with the backup password. Throws when [password] is wrong. Returns how many. */
    suspend fun unlockLogins(password: CharArray): Int = withContext(Dispatchers.Default) {
        val lock = loginLock ?: return@withContext 0
        val texts = lock.unlockWaiting(password)
        val list = texts.flatMap { t -> JSONArray(t).let { a -> (0 until a.length()).map { pwFromJson(a.getJSONObject(it)) } } }
        repo.putPasswords(list)
    }

    private fun memberJson(m: MemberEntity) = JSONObject().put("id", m.id).put("name", m.name).put("relation", m.relation)
        .put("self", m.isSelf).put("shared", m.shared).put("created", m.createdAt).put("avatar", m.avatar ?: JSONObject.NULL).put("group", m.groupId ?: JSONObject.NULL).put("origin", m.origin ?: JSONObject.NULL)
    private fun memberFromJson(j: JSONObject, photo: String?) = MemberEntity(j.getString("id"), j.getString("name"), j.getString("relation"), photo,
        j.optBoolean("self"), j.optLong("created"), j.optBoolean("shared"), if (j.isNull("avatar")) null else j.optString("avatar"),
        groupId = if (!j.has("group") || j.isNull("group")) null else j.getString("group"), origin = j.str("origin"))

    private fun pwJson(p: PasswordEntity) = JSONObject().put("id", p.id).put("member", p.memberId).put("site", p.site).put("user", p.username).put("pass", p.password).put("created", p.createdAt).put("origin", p.origin ?: JSONObject.NULL)
    private fun pwFromJson(j: JSONObject) = PasswordEntity(j.getString("id"), j.getString("member"), j.getString("site"), j.getString("user"), j.getString("pass"), j.optLong("created"), j.str("origin"))

    private fun accountJson(a: AccountEntity) = JSONObject().put("name", a.name).put("cc", a.countryCode).put("phone", a.phone).put("email", a.backupEmail)
        .put("google", a.googleLinked).put("countries", a.countries)
    private fun accountFromJson(j: JSONObject, photo: String?) = AccountEntity(name = j.getString("name"), countryCode = j.getString("cc"), phone = j.getString("phone"),
        backupEmail = j.getString("email"), photoFile = photo, googleLinked = j.optBoolean("google"), countries = j.optString("countries", "IN,GR"))

    private fun JSONObject.str(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifEmpty { null }

    /**
     * What this phone backs up: you and each group's Common vault; for a group set to "Whole group", everyone in it too.
     * Logins: only yours and the Common ones, never other people's.
     */
    private fun scoped(all: VaultRepository.Snapshot): VaultRepository.Snapshot {
        val whole = all.groups.filter { it.backupAll }.map { it.id }.toSet()
        val members = all.members.filter { it.isSelf || it.shared || it.groupId in whole }
        val ids = members.map { it.id }.toSet()
        val loginOwners = members.filter { it.isSelf || it.shared }.map { it.id }.toSet()
        // Other members' events (and what they added to them) are theirs: they come back from their shares, not your backup.
        return all.copy(members = members, documents = all.documents.filter { it.memberId in ids && it.eventId?.startsWith("s:") != true },
            passwords = all.passwords.filter { it.memberId in loginOwners }, events = all.events.filter { !it.id.startsWith("s:") })
    }

    private fun hex(argb: Long) = "#%06X".format(argb and 0xFFFFFF)

    companion object {
        /** drive.file: non-sensitive scope, no Google verification screen, and the app can't see the rest of the user's Drive. */
        const val KEY_FILE = "family-docs-backup-key.json"
        const val PASSCODE_FILE = "lararium-passcode.fdenc"
        private const val OCTET = "application/octet-stream"
        // label keys (Drive appProperties)
        const val P_ROOT = "fd_root"; const val P_GROUP = "fd_group"; const val P_MEMBER = "fd_member"; const val P_MEMBER_OF = "fd_member_of"; const val P_DOC = "fd_doc"
        const val P_DOC_FILE = "fd_file"; const val P_KIND = "fd_kind"; const val P_TYPE = "fd_type"; const val P_COUNTRY = "fd_country"
        const val P_EXPIRY = "fd_expiry"; const val P_UPDATED = "fd_updated"
    }
}
