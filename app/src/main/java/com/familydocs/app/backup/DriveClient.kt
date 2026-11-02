package com.familydocs.app.backup

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Minimal Google Drive v3 REST client (scope drive.file: the app only ever sees files it created). */
class DriveClient(private val token: String) {
    data class Item(val id: String, val name: String, val mime: String, val props: Map<String, String>, val parents: List<String> = emptyList(),
                    /** The contents' checksum (files only): changes when a file is overwritten in place. */
                    val md5: String = "")

    // one client for the whole app: connections to Drive stay open between syncs (no new handshake every time)
    private val http get() = SHARED

    private fun call(req: Request.Builder): String =
        http.newCall(req.header("Authorization", "Bearer $token").build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            check(r.isSuccessful) { "Drive ${r.code}: ${body.take(200)}" }
            body
        }

    private fun parse(o: JSONObject): Item {
        val p = o.optJSONObject("appProperties")
        val parents = o.optJSONArray("parents")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        return Item(o.getString("id"), o.optString("name"), o.optString("mimeType"), p?.keys()?.asSequence()?.associateWith { p.getString(it) } ?: emptyMap(), parents,
            o.optString("md5Checksum"))
    }

    fun list(q: String, space: String = "drive"): List<Item> {
        val out = mutableListOf<Item>()
        var page: String? = null
        do {
            val url = "$FILES?spaces=$space&pageSize=1000&fields=nextPageToken,files(id,name,mimeType,appProperties,parents,md5Checksum)&q=" +
                URLEncoder.encode("$q and trashed = false", "UTF-8") + (page?.let { "&pageToken=$it" } ?: "")
            val j = JSONObject(call(Request.Builder().url(url)))
            val arr = j.getJSONArray("files")
            for (i in 0 until arr.length()) out += parse(arr.getJSONObject(i))
            page = j.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out
    }

    fun children(parent: String) = list("'$parent' in parents")

    fun findByProp(key: String, value: String, parent: String? = null): Item? =
        list("appProperties has { key='$key' and value='${value.replace("'", "\\'")}' }" + (parent?.let { " and '$it' in parents" } ?: "")).firstOrNull()

    private fun meta(name: String, parent: String?, props: Map<String, String>, description: String?, mime: String?, color: String?) = JSONObject().apply {
        put("name", name)
        parent?.let { put("parents", JSONArray().put(it)) }
        put("appProperties", JSONObject(props))
        description?.let { put("description", it) }
        mime?.let { put("mimeType", it) }
        color?.let { put("folderColorRgb", it) }
    }

    /** Finds the folder tagged with [props] under [parent], creating it if needed; keeps name/description/colour current. */
    /**
     * Like [folder], but finds the folder (by its label) anywhere in Drive and moves it under [parent] if it's elsewhere,
     * keeping everything inside: used when a person's folder moves into their group's folder.
     */
    fun folderMovedInto(name: String, parent: String, props: Map<String, String>, description: String? = null): String {
        val (k, v) = props.entries.first()
        val existing = findByProp(k, v) ?: return folder(name, parent, props, description)
        if (parent !in existing.parents) {
            call(Request.Builder().url("$FILES/${existing.id}?addParents=$parent" + if (existing.parents.isNotEmpty()) "&removeParents=${existing.parents.joinToString(",")}" else "")
                .patch(JSONObject().toString().toRequestBody(JSON)))
        }
        return folder(name, parent, props, description)
    }

    fun folder(name: String, parent: String?, props: Map<String, String>, description: String? = null, color: String? = null): String {
        val (k, v) = props.entries.first()
        val existing = findByProp(k, v, parent)
        if (existing != null) {
            if (existing.name != name || description != null || color != null) {
                call(Request.Builder().url("$FILES/${existing.id}").patch(
                    JSONObject().put("name", name).apply { description?.let { put("description", it) }; color?.let { put("folderColorRgb", it) } }
                        .toString().toRequestBody(JSON)))
            }
            return existing.id
        }
        return JSONObject(call(Request.Builder().url("$FILES?fields=id").post(
            meta(name, parent, props, description, FOLDER, color).toString().toRequestBody(JSON)))).getString("id")
    }

    /** Creates or overwrites a file (matched by appProperties [matchKey]). */
    fun upsertFile(parent: String, name: String, bytes: ByteArray, mime: String, props: Map<String, String>, description: String?, matchKey: String): String {
        val existing = findByProp(matchKey, props.getValue(matchKey), parent)
        if (existing != null) {
            val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart(JSONObject().put("name", name).put("appProperties", JSONObject(props)).apply { description?.let { put("description", it) } }.toString().toRequestBody(JSON))
                .addPart(bytes.toRequestBody(mime.toMediaType())).build()
            call(Request.Builder().url("$UPLOAD/${existing.id}?uploadType=multipart").patch(body))
            return existing.id
        }
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta(name, parent, props, description, null, null).toString().toRequestBody(JSON))
            .addPart(bytes.toRequestBody(mime.toMediaType())).build()
        return JSONObject(call(Request.Builder().url("$UPLOAD?uploadType=multipart&fields=id").post(body))).getString("id")
    }

    fun download(id: String): ByteArray =
        http.newCall(Request.Builder().url("$FILES/$id?alt=media").header("Authorization", "Bearer $token").build()).execute().use { r ->
            check(r.isSuccessful) { "Drive download ${r.code}" }
            r.body!!.bytes()
        }

    /** Permanently deletes a file, or a folder with everything in it. */
    fun delete(id: String) {
        http.newCall(Request.Builder().url("$FILES/$id").delete().header("Authorization", "Bearer $token").build()).execute().use { r ->
            check(r.isSuccessful || r.code == 404) { "Drive delete ${r.code}" }
        }
    }

    // ---- hidden app folder (scope drive.appdata): only this app can see it, it never shows in My Drive ----
    fun appDataFind(name: String): Item? = list("name = '${name.replace("'", "\\'")}'", "appDataFolder").firstOrNull()

    fun appDataRead(name: String): ByteArray? = appDataFind(name)?.let { download(it.id) }

    fun appDataWrite(name: String, bytes: ByteArray) {
        val existing = appDataFind(name)
        if (existing != null) {
            call(Request.Builder().url("$UPLOAD/${existing.id}?uploadType=media").patch(bytes.toRequestBody(OCTET_TYPE)))
            return
        }
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(JSONObject().put("name", name).put("parents", JSONArray().put("appDataFolder")).toString().toRequestBody(JSON))
            .addPart(bytes.toRequestBody(OCTET_TYPE)).build()
        call(Request.Builder().url("$UPLOAD?uploadType=multipart&fields=id").post(body))
    }

    fun appDataDelete(name: String) { appDataFind(name)?.let { delete(it.id) } }

    /** "Anyone with the link can view": used only for a group's encrypted share files (unreadable without the group code). */
    fun shareWithLink(id: String) {
        call(Request.Builder().url("$FILES/$id/permissions?fields=id").post(JSONObject().put("role", "reader").put("type", "anyone").toString().toRequestBody(JSON)))
    }

    /** Replaces the contents of file [id] (one request: no lookup first). */
    fun updateContent(id: String, bytes: ByteArray, mime: String) {
        call(Request.Builder().url("$UPLOAD/$id?uploadType=media&fields=id").patch(bytes.toRequestBody(mime.toMediaType())))
    }

    fun trash(id: String) { call(Request.Builder().url("$FILES/$id").patch(JSONObject().put("trashed", true).toString().toRequestBody(JSON))) }

    companion object {
        private val SHARED = OkHttpClient.Builder().readTimeout(2, TimeUnit.MINUTES).writeTimeout(2, TimeUnit.MINUTES).build()
        const val FOLDER = "application/vnd.google-apps.folder"
        private const val FILES = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private val JSON = "application/json; charset=UTF-8".toMediaType()
        private val OCTET_TYPE = "application/octet-stream".toMediaType()
        private val plain by lazy { OkHttpClient.Builder().readTimeout(2, TimeUnit.MINUTES).build() }

        /**
         * Downloads a file someone shared by link (no sign-in needed: it's how you read another member's encrypted share,
         * which this app can't see through its own Drive access). Fails clearly when the link isn't shared or was removed.
         */
        /**
         * The first [size] bytes of a public file, or all of it when Drive sends the whole file anyway (then `complete`).
         * A share is re-encrypted with a new random IV every time it changes, so these bytes tell whether it did.
         */
        fun publicPeek(id: String, size: Int = 64): Pair<ByteArray, Boolean> {
            val url = "https://drive.usercontent.google.com/download?id=" + URLEncoder.encode(id, "UTF-8") + "&export=download&confirm=t"
            return plain.newCall(Request.Builder().url(url).header("Range", "bytes=0-${size - 1}").build()).execute().use { r ->
                val type = r.header("Content-Type").orEmpty()
                check(r.isSuccessful && !type.startsWith("text/html")) { if (r.code == 404 || type.startsWith("text/html")) "That share isn't available (it may have been removed)" else "Drive ${r.code}" }
                val b = r.body!!.bytes()
                b to (r.code != 206)
            }
        }

        fun publicDownload(id: String): ByteArray {
            val url = "https://drive.usercontent.google.com/download?id=" + URLEncoder.encode(id, "UTF-8") + "&export=download&confirm=t"
            return plain.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val type = r.header("Content-Type").orEmpty()
                check(r.isSuccessful && !type.startsWith("text/html")) { if (r.code == 404 || type.startsWith("text/html")) "That share isn't available (it may have been removed)" else "Drive ${r.code}" }
                r.body!!.bytes()
            }
        }
    }
}
