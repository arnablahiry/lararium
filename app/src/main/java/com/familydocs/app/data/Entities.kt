package com.familydocs.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.familydocs.app.domain.DocField
import org.json.JSONArray
import org.json.JSONObject

/** One stored file of a document (encrypted in [FileStore]). */
data class Attachment(val id: String, val mime: String) {
    companion object {
        fun encode(list: List<Attachment>): String? = if (list.isEmpty()) null
            else JSONArray(list.map { JSONObject().put("id", it.id).put("mime", it.mime) }).toString()
        fun decode(json: String?): List<Attachment> = runCatching {
            val a = JSONArray(json ?: return emptyList())
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Attachment(o.getString("id"), o.optString("mime", "image/jpeg")) } }
        }.getOrDefault(emptyList())
    }
}

/** A group (e.g. your family): its own people, Common vault and events, and a code to join it. Database version 7. */
@Entity(tableName = "vault_groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val code: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Drive backup of this group: false = only you and the Common vault (default); true = everyone in the group. Database version 8. */
    val backupAll: Boolean = false,
    /** Your encrypted share for the group in your Drive: its folder and index file (null until you first invite someone). */
    val shareFolder: String? = null,
    val shareIndex: String? = null,
    /** People whose shares you read: JSON [{"key":…,"name":…,"index":…,"at":…}]. */
    val peers: String? = null,
    /** Group picture: a template ("family", "friends", "office", "flat"); [photoFile] (your own photo) wins when set. */
    val pic: String? = null,
    val photoFile: String? = null,
    /** Key of the member who made the group: always an admin. Null until known (older groups learn it on the next sync). Database version 9. */
    val creator: String? = null,
    /** Other admins' keys, comma separated, and when that list was last changed (the newest list from an admin wins). */
    val admins: String? = null,
    val adminsAt: Long = 0L,
) {
    val peerList: List<Peer> get() = Peer.decode(peers)
    val adminKeys: Set<String> get() = setOfNotNull(creator) + admins.orEmpty().split(',').filter { it.isNotBlank() }
    /** Admins can change other members' documents and profiles, make admins and remove people. A group of one is yours. */
    fun isAdmin(key: String?): Boolean = key != null && (key in adminKeys || (creator == null && peerList.isEmpty()))
}

/** Another account in a group: their app shares an encrypted index file (Drive "anyone with the link", sealed with the group code). */
data class Peer(val key: String, val name: String, val index: String, val at: Long = 0L, val err: String? = null,
                /** Their phone's public key: Common logins are sealed to it (so the group code alone can't open them). */
                val pub: String? = null,
                /** Whether their share lists you, i.e. they've added your invite (null: not read yet / older version). */
                val sees: Boolean? = null) {
    /** Both ways: you read their share, and theirs lists you (they read yours). */
    val synced: Boolean get() = sees == true && at > 0L

    companion object {
        /** "Mohua", "Mohua and Teesta", "Arnab, Mohua and Teesta". */
        fun names(list: List<String>): String = when (list.size) {
            0 -> ""; 1 -> list[0]; else -> list.dropLast(1).joinToString(", ") + " and " + list.last()
        }
        fun encode(list: List<Peer>): String? = if (list.isEmpty()) null else JSONArray(list.map {
            JSONObject().put("key", it.key).put("name", it.name).put("index", it.index).put("at", it.at).put("err", it.err ?: JSONObject.NULL).put("pub", it.pub ?: JSONObject.NULL)
                .put("sees", it.sees ?: JSONObject.NULL) }).toString()
        fun decode(json: String?): List<Peer> = runCatching {
            val a = JSONArray(json ?: return emptyList())
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Peer(o.getString("key"), o.optString("name"), o.getString("index"), o.optLong("at"),
                if (o.isNull("err")) null else o.optString("err").ifEmpty { null }, if (o.isNull("pub")) null else o.optString("pub").ifEmpty { null },
                if (!o.has("sees") || o.isNull("sees")) null else o.optBoolean("sees")) } }
        }.getOrDefault(emptyList())
    }
}

@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey val id: Int = 1,
    val name: String,
    val countryCode: String,
    val phone: String,
    val backupEmail: String,
    val photoFile: String?,
    val googleLinked: Boolean,
    /** Countries the family keeps documents for, e.g. "IN,GR". Drives the filter pills and "Issued by" choices. */
    val countries: String = "IN,GR",
)

@Entity(tableName = "members")
data class MemberEntity(
    @PrimaryKey val id: String,
    val name: String,
    val relation: String,
    val photoFile: String?,
    val isSelf: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** The "Common" vault: one per family, every account holder can see and edit it. */
    val shared: Boolean = false,
    /** Illustrated character used when there's no photo (e.g. "char_blue_short"). Added in database version 2. */
    val avatar: String? = null,
    /** The group this person belongs to; null for you (you're in every group). Added in database version 7. */
    val groupId: String? = null,
    /** Who shared this person with you: null = made on this phone; else the other account's key (read-only here). Database version 8. */
    val origin: String? = null,
)

enum class DisplayMode { ICON, FROM_FILE, CUSTOM }

@Entity(
    tableName = "documents",
    foreignKeys = [ForeignKey(entity = MemberEntity::class, parentColumns = ["id"], childColumns = ["memberId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("memberId")],
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    val memberId: String,
    val name: String,
    /** Encrypted original (image or PDF) in [FileStore]. */
    val fileId: String?,
    val fileMime: String?,
    val display: DisplayMode = DisplayMode.ICON,
    /** Square thumbnail used when [display] is FROM_FILE or CUSTOM. */
    val pictureFile: String? = null,
    val fieldsJson: String = "[]",
    val rawText: String = "",
    val readByOcr: Boolean = false,
    /** ISO alpha-2 issuing country ("IN", "GR"…), "" for none, null = work it out from the fields. */
    val country: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Set for a ticket or booking added only for an event: it then shows in that event, not in the vault. Added in database version 3. */
    val eventId: String? = null,
    /** Faint country flag behind the card art: null = the type's default (on for national ID cards). Added in database version 4. */
    val showFlag: Boolean? = null,
    /** Files after the first one ([fileId]), in order: JSON [{"id":…,"mime":…}]. Added in database version 5. */
    val moreFiles: String? = null,
    /** Position in the person's wallet, set by hold-and-drag (null = in the order added). Added in database version 6. */
    val sortOrder: Int? = null,
    /** null = added on this phone; else the key of the group member who shared it (read-only here). Database version 8. */
    val origin: String? = null,
    /** When it was put away (replaced by a renewed one): kept and searchable, but out of the wallet and reminders. Version 11. */
    val archivedAt: Long? = null,
) {
    val archived: Boolean get() = archivedAt != null
    /** Every file of the document (front, back, more pages…), in order. */
    val attachments: List<Attachment> get() = listOfNotNull(fileId?.let { Attachment(it, fileMime ?: "image/jpeg") }) + Attachment.decode(moreFiles)
    val fields: List<DocField> get() = DocMemo.fields(fieldsJson)
    /** Whether the card shows its issuing country's flag. */
    val flagShown: Boolean get() = showFlag ?: com.familydocs.app.domain.DocTypes.flagByDefault(name)
    val issuer: String get() = country ?: DocMemo.issuer(name, fieldsJson, rawText) { com.familydocs.app.domain.Countries.infer(name, fields, rawText) }
}

/**
 * Remembers decoded fields and worked-out issuing countries: cards, lists and filters ask for them many times a
 * frame, and parsing the JSON (or scanning the OCR text) each time made long vaults stutter. Same results, worked out once.
 */
internal object DocMemo {
    private class Lru<K, V>(private val max: Int) : LinkedHashMap<K, V>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    }
    private val fieldMemo = Lru<String, List<DocField>>(800)
    private val issuerMemo = Lru<Triple<String, String, String>, String>(800)

    fun fields(json: String): List<DocField> = synchronized(fieldMemo) { fieldMemo[json] } ?: decodeFields(json).also { synchronized(fieldMemo) { fieldMemo[json] = it } }

    fun issuer(name: String, json: String, text: String, work: () -> String): String {
        val k = Triple(name, json, text)
        return synchronized(issuerMemo) { issuerMemo[k] } ?: work().also { synchronized(issuerMemo) { issuerMemo[k] = it } }
    }
}

@Entity(
    tableName = "passwords",
    foreignKeys = [ForeignKey(entity = MemberEntity::class, parentColumns = ["id"], childColumns = ["memberId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("memberId")],
)
data class PasswordEntity(
    @PrimaryKey val id: String,
    val memberId: String,
    val site: String,
    val username: String,
    val password: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** null = saved on this phone; else shared by another group member (Common logins only). Database version 8. */
    val origin: String? = null,
)

data class MemberCount(val memberId: String, val n: Int)

fun encodeFields(fields: List<DocField>): String = JSONArray().apply {
    fields.forEach { f ->
        put(JSONObject().put("label", f.label).put("value", f.value).put("sensitive", f.sensitive).put("auto", f.auto)
            .apply { f.verified?.let { put("verified", it) } })
    }
}.toString()

fun decodeFields(json: String): List<DocField> = runCatching {
    val a = JSONArray(json)
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        DocField(
            label = o.optString("label"), value = o.optString("value"), sensitive = o.optBoolean("sensitive"),
            auto = o.optBoolean("auto"), verified = if (o.has("verified")) o.getBoolean("verified") else null,
        )
    }
}.getOrDefault(emptyList())

/**
 * A trip or occasion (database version 3): picture, dates, who's going, who can see it, the vault documents it
 * needs (pinned, they stay in their owner's vault) and a reminder. Its own tickets/bookings are documents with [DocumentEntity.eventId].
 */
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** One of the event pictures ("mountain", "beach", "heritage"…), used when there's no photo. */
    val art: String,
    val photoFile: String? = null,
    /** ISO dates, "2026-11-20". */
    val startDay: String,
    val endDay: String? = null,
    /** Member ids, comma separated. */
    val people: String = "",
    /** Member ids who can see the event; the creator is always included. */
    val visibleTo: String = "",
    /** Pinned vault document ids, comma separated. */
    val pins: String = "",
    /** Days before [startDay] to notify, or -1 for no reminder. */
    val remindDays: Int = -1,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** The group the event belongs to. Added in database version 7. */
    val groupId: String? = null,
    /**
     * When it was last changed, and by whom: null = on this phone, else that member's key. Everyone the event is shared
     * with can change it, so the newest change wins on every phone. Database version 10.
     */
    val updatedAt: Long = 0L,
    val editedBy: String? = null,
) {
    /** Made by someone else (their event, shared with you): only they choose who sees it, or delete it. */
    val received: Boolean get() = id.startsWith("s:")
    /** The organiser's key, for an event someone else made. */
    val organiserKey: String? get() = if (received) id.split(':', limit = 4).getOrNull(2) else null
    val peopleIds: List<String> get() = people.split(',').filter { it.isNotBlank() }
    val visibleIds: List<String> get() = visibleTo.split(',').filter { it.isNotBlank() }
    val pinIds: List<String> get() = pins.split(',').filter { it.isNotBlank() }
}
