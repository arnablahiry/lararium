package com.familydocs.app.data

import com.familydocs.app.domain.Countries
import com.familydocs.app.domain.DocField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withContext
import java.util.UUID

const val DEFAULT_SELF_AVATAR = "char_blue_short"
/** The family this app was made for: whoever signs up as one of them gets the other two, with the right relation and picture. */
data class FamilyPreset(val name: String, val avatar: String)
val FAMILY_PRESET = listOf(FamilyPreset("Arnab", "char_blue_short"), FamilyPreset("Mohua", "char_peach_bun"), FamilyPreset("Teesta", "char_lilac_long"))
private val PRESET_RELATIONS = mapOf(
    "Arnab" to mapOf("Mohua" to "Mom", "Teesta" to "Sister"),
    "Mohua" to mapOf("Arnab" to "Son", "Teesta" to "Daughter"),
    "Teesta" to mapOf("Arnab" to "Brother", "Mohua" to "Mom"),
)
fun presetFor(name: String): FamilyPreset? = FAMILY_PRESET.firstOrNull { name.trim().substringBefore(' ').equals(it.name, ignoreCase = true) }

val CHARACTER_KEYS = listOf("char_blue_short", "char_peach_bun", "char_lilac_long", "char_mint_glasses", "char_sand_bun", "char_rose_long", "char_sky_short", "char_slate_glasses")

/**
 * A group admin's changes to another member's things (their documents, their profile, removing them). The change shows
 * here straight away; [com.familydocs.app.backup.GroupShare] passes it to their phone, which applies it to their own vault.
 */
interface AdminEdits {
    suspend fun putDoc(row: DocumentEntity, member: MemberEntity)
    suspend fun deleteDoc(row: DocumentEntity, member: MemberEntity?)
    suspend fun profile(row: MemberEntity, photoChanged: Boolean)
    suspend fun removeMember(groupId: String, key: String)
}

class VaultRepository(private val dao: VaultDao, val files: FileStore) {
    /** Called after anything the backup covers is added, changed or deleted (AppContainer queues a Drive backup). */
    var onChanged: () -> Unit = {}
    /** Where admin changes to other members' things go (set by AppContainer). */
    var admin: () -> AdminEdits? = { null }

    val account: Flow<AccountEntity?> = dao.account()

    // ---- groups: the screens show the current group's people, documents and events ----
    /** The group being shown (AppContainer.selectGroup changes it and remembers it). */
    val groupId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val groups: Flow<List<GroupEntity>> = dao.groups()
    suspend fun groupsNow() = dao.groupsNow()
    fun membersOf(g: String?): Flow<List<MemberEntity>> = dao.membersIn(g)
    fun eventsOf(g: String?): Flow<List<EventEntity>> = dao.eventsIn(g)
    /** Documents of group [g]'s people (yours included). */
    fun documentsOf(g: String?): Flow<List<DocumentEntity>> = kotlinx.coroutines.flow.combine(dao.allDocuments(), dao.membersIn(g)) { d, m ->
        val ids = m.map { it.id }.toSet(); d.filter { it.memberId in ids }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val members: Flow<List<MemberEntity>> = groupId.flatMapLatest { membersOf(it) }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val allDocuments: Flow<List<DocumentEntity>> = groupId.flatMapLatest { documentsOf(it) }

    /** A new group with its own Common vault and a fresh code. */
    suspend fun createGroup(name: String, pic: String? = null, photo: ByteArray? = null): GroupEntity = withContext(Dispatchers.IO) {
        val n = name.trim().ifBlank { "Family" }
        // You made it, so you're its admin (during sign-up "you" don't exist yet: saveAccount fills it in).
        val g = GroupEntity(UUID.randomUUID().toString(), n, com.familydocs.app.domain.GroupCodes.new(),
            pic = pic ?: com.familydocs.app.domain.GroupPics.DEFAULT, photoFile = photo?.let { files.put(it) }, creator = dao.selfNow()?.id)
        dao.upsertGroup(g)
        dao.upsertMember(MemberEntity(UUID.randomUUID().toString(), "Common", "Shared", photoFile = null, createdAt = 0L, shared = true, groupId = g.id))
        onChanged()
        g
    }
    fun group(id: String) = dao.group(id)
    /** Group setting: back up the whole group to your Drive, or only you and the Common vault. */
    suspend fun setGroupBackup(id: String, all: Boolean) = withContext(Dispatchers.IO) {
        dao.groupNow(id)?.let { dao.upsertGroup(it.copy(backupAll = all)); onChanged() }
    }
    /** Group picture: a template ([pic]) or your own [photo] (replaces the old photo). */
    suspend fun setGroupPicture(id: String, pic: String?, photo: ByteArray?) = withContext(Dispatchers.IO) {
        val g = dao.groupNow(id) ?: return@withContext
        if (photo != null || pic != null) files.delete(g.photoFile)
        dao.upsertGroup(g.copy(pic = pic ?: g.pic, photoFile = photo?.let { files.put(it) }))
        onChanged()
    }
    suspend fun renameGroup(id: String, name: String) = withContext(Dispatchers.IO) {
        dao.groupNow(id)?.let { dao.upsertGroup(it.copy(name = name.trim().ifEmpty { it.name })); onChanged() }
    }
    suspend fun groupByCode(code: String): GroupEntity? = dao.groupsNow().firstOrNull {
        com.familydocs.app.domain.GroupCodes.normalize(it.code) == com.familydocs.app.domain.GroupCodes.normalize(code)
    }
    val documentCounts: Flow<List<MemberCount>> = dao.documentCounts()

    fun member(id: String) = dao.member(id)
    fun documents(memberId: String) = dao.documents(memberId)
    fun document(id: String) = dao.document(id)
    fun passwords(memberId: String) = dao.passwords(memberId)

    suspend fun accountNow() = dao.accountNow()
    suspend fun selfNow() = dao.selfNow()
    suspend fun documentsCount(memberId: String) = dao.documentsNow(memberId).size
    fun self() = dao.self()
    suspend fun documentNow(id: String) = dao.documentNow(id)
    suspend fun memberNow(id: String) = dao.memberNow(id)

    /**
     * Creates or updates the account and the matching "You" member. Your profile photo is ONE file shared by the
     * account and your circle on the home screen, so changing it in either place changes both.
     * [photo] = new picture, [removePhoto] = go back to the character, otherwise the current photo is kept.
     */
    suspend fun saveAccount(
        name: String, cc: String, phone: String, email: String, photo: ByteArray?, google: Boolean, countries: List<String>,
        removePhoto: Boolean = false, avatar: String? = null,
    ) = withContext(Dispatchers.IO) {
        val oldAccount = dao.accountNow()
        val self = dao.selfNow()
        val oldPhotos = setOfNotNull(oldAccount?.photoFile, self?.photoFile)
        val photoId = when {
            photo != null -> files.put(photo)
            removePhoto -> null
            else -> self?.photoFile ?: oldAccount?.photoFile
        }
        oldPhotos.filter { it != photoId }.forEach { files.delete(it) }
        dao.upsertAccount(AccountEntity(name = name, countryCode = cc, phone = phone, backupEmail = email, photoFile = photoId, googleLinked = google,
            countries = Countries.encode(countries)))
        dao.upsertMember(
            (self ?: MemberEntity(id = UUID.randomUUID().toString(), name = name, relation = "You", photoFile = null, isSelf = true, avatar = DEFAULT_SELF_AVATAR))
                .let { it.copy(name = name, photoFile = photoId, avatar = avatar ?: it.avatar ?: DEFAULT_SELF_AVATAR) },
        )
        // Groups made on this phone before "you" existed (sign-up): you made them, so you're their admin.
        dao.selfNow()?.let { me -> dao.groupsNow().filter { it.creator == null && it.peerList.isEmpty() }.forEach { dao.upsertGroup(it.copy(creator = me.id)) } }
        // Every group gets one Common vault (rent, bills, Wi-Fi...) that all its account holders share.
        if (dao.sharedIn(groupId.value) == null) {
            dao.upsertMember(MemberEntity(UUID.randomUUID().toString(), "Common", "Shared", photoFile = null, createdAt = 0L, shared = true, groupId = groupId.value))
        }
    }

    /** People made on this phone (not you, not Common, not shared by other members) that have no documents or logins. */
    suspend fun removeEmptyLocalPeople() = withContext(Dispatchers.IO) {
        val docs = dao.allDocumentsNow().map { it.memberId }.toSet(); val pws = dao.allPasswordsNow().map { it.memberId }.toSet()
        dao.membersNow().filter { !it.isSelf && !it.shared && it.origin == null && it.id !in docs && it.id !in pws }
            .forEach { files.delete(it.photoFile); dao.deleteMember(it.id) }
    }

    /**
     * Only account holders (and Common) are people. Anyone else made on this phone (the old preset family) goes:
     * their documents move to the group's Common vault and their logins to you, so nothing is lost.
     */
    suspend fun foldLocalPeople() = withContext(Dispatchers.IO) {
        val self = dao.selfNow() ?: return@withContext
        val extra = dao.membersNow().filter { !it.isSelf && !it.shared && it.origin == null }
        if (extra.isEmpty()) return@withContext
        val pws = dao.allPasswordsNow()
        extra.forEach { m ->
            val home = dao.sharedIn(m.groupId) ?: self
            dao.documentsNow(m.id).forEach { dao.upsertDocument(it.copy(memberId = home.id)) }
            pws.filter { it.memberId == m.id }.forEach { dao.upsertPassword(it.copy(memberId = self.id)) }
            files.delete(m.photoFile); dao.deleteMemberRow(m.id)
        }
        onChanged()
    }

    /**
     * Adds the rest of the preset family (see [FAMILY_PRESET]) if you are one of them and they aren't here yet,
     * and turns a plain "Family" relation into the right one (Mom, Sister…). Never touches anyone else.
     */
    suspend fun ensureFamily() = withContext(Dispatchers.IO) {
        val me = dao.selfNow() ?: return@withContext
        val preset = presetFor(me.name) ?: return@withContext
        val rel = PRESET_RELATIONS.getValue(preset.name)
        val g = groupId.value
        val people = dao.membersNow().filter { it.groupId == g }
        var t = System.currentTimeMillis()
        FAMILY_PRESET.filter { it != preset }.forEach { p ->
            val existing = people.firstOrNull { !it.isSelf && !it.shared && it.name.trim().equals(p.name, ignoreCase = true) }
            when {
                existing == null -> dao.upsertMember(MemberEntity(UUID.randomUUID().toString(), p.name, rel.getValue(p.name), photoFile = null, createdAt = t++, avatar = p.avatar, groupId = g))
                existing.relation == "Family" -> dao.upsertMember(existing.copy(relation = rel.getValue(p.name)))
            }
        }
    }

    /** Add or edit a person. For yourself this also updates the account photo (same file). */
    suspend fun saveMember(id: String?, name: String, relation: String, photo: ByteArray?, keepPhoto: Boolean, avatar: String?) = withContext(Dispatchers.IO) {
        val old = id?.let { dao.memberNow(it) }
        val photoId = when {
            photo != null -> files.put(photo)
            keepPhoto -> old?.photoFile
            else -> null
        }
        val chosenAvatar = avatar ?: old?.avatar ?: nextCharacter()
        // Another member's profile: only an admin gets here; their phone takes the change over.
        if (old?.origin != null) {
            val row = old.copy(name = name, photoFile = photoId, avatar = chosenAvatar)
            admin()?.profile(row, photoChanged = photoId != old.photoFile) ?: error("Only a group admin can change this")
            if (photoId != old.photoFile) files.delete(old.photoFile)
            dao.upsertMember(row)
            return@withContext
        }
        old?.photoFile?.takeIf { it != photoId }?.let { files.delete(it) }
        dao.upsertMember(
            old?.copy(name = name, relation = relation, photoFile = photoId, avatar = chosenAvatar)
                ?: MemberEntity(UUID.randomUUID().toString(), name, relation, photoId, avatar = chosenAvatar, groupId = groupId.value),
        )
        if (old?.isSelf == true) dao.accountNow()?.let { a ->
            if (a.photoFile != photoId) { a.photoFile?.takeIf { it != old.photoFile && it != photoId }?.let { files.delete(it) } }
            dao.upsertAccount(a.copy(name = name, photoFile = photoId))
        }
        onChanged()
    }

    /** First character nobody uses yet, so a new person looks different from the others. */
    suspend fun nextCharacter(): String {
        val used = dao.membersNow().mapNotNull { it.avatar }.toSet()
        return CHARACTER_KEYS.firstOrNull { it !in used } ?: CHARACTER_KEYS[used.size % CHARACTER_KEYS.size]
    }

    suspend fun deleteMember(id: String) = withContext(Dispatchers.IO) {
        val m = dao.memberNow(id) ?: return@withContext
        if (m.shared) return@withContext // the Common vault can't be removed
        // Another member (an admin removing them from the group): everyone's phone drops them.
        if (m.origin != null) { admin()?.removeMember(m.groupId ?: return@withContext, m.origin) ?: error("Only a group admin can remove members"); return@withContext }
        dao.documentsNow(id).forEach { it.attachments.forEach { a -> files.delete(a.id) }; files.delete(it.pictureFile) }
        dao.memberNow(id)?.let { files.delete(it.photoFile) }
        dao.deleteMember(id)
        onChanged()
    }

    suspend fun saveDocument(doc: DocumentEntity, fields: List<DocField>) = withContext(Dispatchers.IO) {
        var row = doc.copy(fieldsJson = encodeFields(fields), updatedAt = System.currentTimeMillis())
        val member = dao.memberNow(doc.memberId)
        val target = doc.origin ?: member?.origin
        // Another member's document (theirs, or one they put in Common): an admin's change, which their phone takes over.
        if (target != null && member != null && doc.eventId == null) {
            val prefix = "s:${member.groupId}:$target:"
            row = row.copy(id = if (row.id.startsWith(prefix)) row.id else prefix + row.id, origin = target)
            admin()?.putDoc(row, member) ?: error("Only a group admin can change this")
        }
        dao.upsertDocument(row)
        onChanged()
    }

    /** Puts a document away (or back): kept and searchable, but out of the wallet, counts and expiry reminders. */
    suspend fun setArchived(id: String, archived: Boolean) {
        val d = withContext(Dispatchers.IO) { dao.documentNow(id) } ?: return
        saveDocument(d.copy(archivedAt = if (archived) System.currentTimeMillis() else null), d.fields)
    }

    /** Older documents a newly added [doc] replaces (same kind, same person, same country): offered to be archived. */
    suspend fun replacedBy(doc: DocumentEntity): List<DocumentEntity> = withContext(Dispatchers.IO) {
        val key = com.familydocs.app.domain.DocTypes.forName(doc.name).key
        if (doc.eventId != null || key !in com.familydocs.app.domain.DocTypes.RENEWABLE) return@withContext emptyList()
        dao.allDocumentsNow().filter {
            it.id != doc.id && it.memberId == doc.memberId && it.eventId == null && !it.archived && it.parentId == null &&
                com.familydocs.app.domain.DocTypes.forName(it.name).key == key && (it.issuer.isEmpty() || doc.issuer.isEmpty() || it.issuer == doc.issuer)
        }
    }

    /** New order of a person's cards after hold-and-drag: [ids] top to bottom. */
    suspend fun reorderDocuments(ids: List<String>) = withContext(Dispatchers.IO) {
        ids.forEachIndexed { i, id -> dao.setDocumentOrder(id, i) }
        onChanged()
    }

    fun supplements(parentId: String) = dao.supplements(parentId)

    suspend fun deleteDocument(id: String): Unit = withContext(Dispatchers.IO) {
        // its later reports go with it
        dao.supplementsNow(id).forEach { s -> runCatching { deleteDocument(s.id) } }
        dao.documentNow(id)?.let { d -> if (d.origin != null) admin()?.deleteDoc(d, dao.memberNow(d.memberId)) ?: error("Only a group admin can remove this") }
        dao.documentNow(id)?.let { it.attachments.forEach { a -> files.delete(a.id) }; files.delete(it.pictureFile) }
        dao.deleteDocument(id)
        onChanged()
    }

    suspend fun savePassword(memberId: String, site: String, user: String, pass: String, id: String? = null) {
        // logins are private to their owner: none can be added to another member's vault, not even by an admin
        require(dao.memberNow(memberId)?.origin == null) { "Logins are private: only they can save their own" }
        dao.upsertPassword(PasswordEntity(id ?: UUID.randomUUID().toString(), memberId, site, user, pass))
        onChanged()
    }

    suspend fun deletePassword(id: String) { dao.deletePassword(id); onChanged() }

    /** Logins brought back from a backup (after the backup password was entered). Returns how many were added. */
    suspend fun putPasswords(list: List<PasswordEntity>): Int = withContext(Dispatchers.IO) {
        var n = 0
        list.forEach { p -> if (dao.memberNow(p.memberId) != null) { dao.upsertPassword(p); n++ } }
        if (n > 0) onChanged()
        n
    }

    // ---- events ----
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val events: Flow<List<EventEntity>> = groupId.flatMapLatest { eventsOf(it) }
    fun event(id: String) = dao.event(id)
    fun eventDocuments(eventId: String) = dao.eventDocuments(eventId)
    suspend fun eventNow(id: String) = dao.eventNow(id)
    suspend fun saveEvent(e: EventEntity) = withContext(Dispatchers.IO) {
        val row = e.copy(updatedAt = System.currentTimeMillis(), editedBy = null)
        dao.upsertEvent(if (row.groupId == null) row.copy(groupId = groupId.value) else row); onChanged()
    }
    suspend fun setEventPins(id: String, pins: List<String>) = withContext(Dispatchers.IO) {
        dao.eventNow(id)?.let { dao.upsertEvent(it.copy(pins = pins.distinct().joinToString(","), updatedAt = System.currentTimeMillis(), editedBy = null)) }
        onChanged()
    }
    /** Deletes the event and the tickets/bookings added only for it. Pinned vault documents are untouched. */
    suspend fun deleteEvent(id: String) = withContext(Dispatchers.IO) {
        dao.eventDocumentsNow(id).forEach { it.attachments.forEach { a -> files.delete(a.id) }; files.delete(it.pictureFile) }
        dao.eventNow(id)?.let { files.delete(it.photoFile) }
        dao.deleteEventDocuments(id)
        dao.deleteEventRow(id)
        onChanged()
    }

    // ---- used by backup ----
    suspend fun snapshot() = withContext(Dispatchers.IO) {
        Snapshot(dao.accountNow(), dao.membersNow(), dao.allDocumentsNow(), dao.allPasswordsNow(), dao.eventsNow(), dao.groupsNow())
    }

    /** Marks the account as linked to this Google account (its Drive holds the backups). */
    suspend fun linkGoogle(email: String) = withContext(Dispatchers.IO) {
        dao.accountNow()?.let { dao.upsertAccount(it.copy(backupEmail = email, googleLinked = true)) }
    }

    /** Removes every account, person, document and password from this phone. */
    suspend fun wipe() = withContext(Dispatchers.IO) { dao.replaceAll(null, emptyList(), emptyList(), emptyList()); files.clear() }

    suspend fun restore(s: Snapshot) = withContext(Dispatchers.IO) {
        var snap = s
        // A backup from before groups: it all becomes the group "Family", like the database upgrade does.
        if (s.groups.isEmpty() && s.members.any { !it.isSelf }) {
            val g = GroupEntity(UUID.randomUUID().toString(), "Family", com.familydocs.app.domain.GroupCodes.new())
            snap = s.copy(groups = listOf(g), members = s.members.map { if (it.isSelf) it else it.copy(groupId = it.groupId ?: g.id) },
                events = s.events.map { it.copy(groupId = it.groupId ?: g.id) })
        }
        dao.replaceAll(snap.account, snap.members, snap.documents, snap.passwords, snap.events, snap.groups)
        if (snap.groups.none { it.id == groupId.value }) groupId.value = snap.groups.firstOrNull()?.id
    }

    data class Snapshot(
        val account: AccountEntity?, val members: List<MemberEntity>, val documents: List<DocumentEntity>, val passwords: List<PasswordEntity>,
        val events: List<EventEntity> = emptyList(),
        val groups: List<GroupEntity> = emptyList(),
    )
}
