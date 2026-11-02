package com.familydocs.app.domain

import com.familydocs.app.data.DisplayMode
import com.familydocs.app.data.DocumentEntity
import com.familydocs.app.data.EventEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Changes made in the desktop app (Mac, Windows, Linux), on their way to the phone.
 *
 * The phone is where the vault lives, so the desktop never writes your backup itself. Each change it makes is an "op"
 * in one sealed file in your Drive's Lararium folder ("Desktop changes.fdenc", sealed with your backup key, link-shared
 * so the phone can look at it every few seconds without asking Google). New pictures and scans go up next to it, sealed
 * the same way. The phone applies each op once (the newest change wins), backs up, and then lists the op as done in your
 * Drive's hidden app folder ([ACK_FILE]); the desktop drops it and its files. Until then the desktop shows the change
 * on top of what it read from the backup.
 *
 * Shared by both apps, so the two always read and write the same thing.
 */
object DeskOps {
    /** Drive labels of the changes file and of the files uploaded with it. */
    const val TAG = "desk"
    const val FILE_KIND = "desk-file"
    const val FILE_NAME = "Desktop changes.fdenc"
    /** In the hidden app folder: which ops the phone has taken over (and backed up). */
    const val ACK_FILE = "lararium-desk-ack.json"

    const val DOC = "doc"; const val DEL_DOC = "deldoc"; const val ORDER = "order"
    const val EVENT = "event"; const val DEL_EVENT = "delevent"
    const val MEMBER = "member"; const val DEL_MEMBER = "delmember"
    const val GROUP = "group"

    fun op(kind: String, at: Long = System.currentTimeMillis()): JSONObject =
        JSONObject().put("id", java.util.UUID.randomUUID().toString()).put("at", at).put("kind", kind)

    // ---- documents ----

    /** A document's details (not its files: those travel as "files" / "picture" next to this, or stay as they are). */
    fun docJson(d: DocumentEntity): JSONObject = JSONObject().put("id", d.id).put("member", d.memberId).put("name", d.name)
        .put("fields", d.fieldsJson).put("text", d.rawText).put("ocr", d.readByOcr).put("country", d.country ?: JSONObject.NULL)
        .put("display", d.display.name).put("flag", d.showFlag ?: JSONObject.NULL).put("event", d.eventId ?: JSONObject.NULL)
        .put("archived", d.archivedAt ?: JSONObject.NULL).put("created", d.createdAt)

    /** [base] (the phone's copy, if any) with the details from [j]; files are left as they are. */
    fun docFrom(j: JSONObject, base: DocumentEntity?): DocumentEntity {
        val row = base ?: DocumentEntity(id = j.getString("id"), memberId = j.getString("member"), name = "", fileId = null, fileMime = null,
            createdAt = j.optLong("created", System.currentTimeMillis()))
        return row.copy(
            memberId = j.getString("member"), name = j.getString("name"), fieldsJson = j.optString("fields", "[]"), rawText = j.optString("text"),
            readByOcr = j.optBoolean("ocr"), country = j.strOrNull("country"),
            display = runCatching { DisplayMode.valueOf(j.getString("display")) }.getOrDefault(row.display),
            showFlag = if (!j.has("flag") || j.isNull("flag")) null else j.getBoolean("flag"), eventId = j.strOrNull("event"),
            archivedAt = if (!j.has("archived") || j.isNull("archived")) null else j.getLong("archived"),
        )
    }

    // ---- events ----

    fun eventJson(e: EventEntity): JSONObject = JSONObject().put("id", e.id).put("name", e.name).put("art", e.art)
        .put("start", e.startDay).put("end", e.endDay ?: JSONObject.NULL).put("people", e.people).put("visible", e.visibleTo)
        .put("pins", e.pins).put("remind", e.remindDays).put("notes", e.notes).put("group", e.groupId ?: JSONObject.NULL).put("created", e.createdAt)

    /** [base] with what [j] says (its photo is handled next to this). Who can see a received event stays as it was. */
    fun eventFrom(j: JSONObject, base: EventEntity?): EventEntity {
        val row = base ?: EventEntity(id = j.getString("id"), name = "", art = "trip", startDay = j.getString("start"),
            createdAt = j.optLong("created", System.currentTimeMillis()), groupId = j.strOrNull("group"))
        return row.copy(
            name = j.getString("name"), art = j.optString("art", row.art), startDay = j.getString("start"), endDay = j.strOrNull("end"),
            people = j.optString("people"), visibleTo = if (row.received) row.visibleTo else j.optString("visible"), pins = j.optString("pins"),
            remindDays = j.optInt("remind", row.remindDays), notes = j.optString("notes"), groupId = row.groupId ?: j.strOrNull("group"),
        )
    }

    // ---- reading and writing the changes file ----

    fun pack(ops: List<JSONObject>, from: String): String = JSONObject().put("v", 1).put("from", from).put("ops", JSONArray(ops)).toString()
    fun unpack(text: String): List<JSONObject> = JSONObject(text).optJSONArray("ops").jsonObjects()

    fun ackJson(ids: Collection<String>): String = JSONObject().put("v", 1).put("applied", JSONArray(ids.toList())).put("at", System.currentTimeMillis()).toString()
    fun acked(text: String?): Set<String> = text?.let { t -> runCatching { JSONObject(t).optJSONArray("applied")?.let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull() }
        .orEmpty().toSet()
}

/** The objects in a JSON array (none when it's missing). */
fun JSONArray?.jsonObjects(): List<JSONObject> = this?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
/** A string value, or null when it's missing, null or empty. */
fun JSONObject.strOrNull(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifEmpty { null }
