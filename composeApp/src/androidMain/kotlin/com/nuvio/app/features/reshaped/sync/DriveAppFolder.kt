package com.nuvio.app.features.reshaped.sync

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * The synced file as read: its Drive id (null when there is none yet) and text. [others] are
 * copies two devices created at once on their first sync; sync merges them in, then deletes them.
 */
internal data class DriveSyncFile(val id: String?, val text: String?, val others: List<Pair<String, String>> = emptyList())

/**
 * Reads and writes Reshaped's one file in the Google account's hidden app folder (Drive's
 * "appDataFolder"): only this app can see it, and it does not count as the viewer's files.
 */
internal object DriveAppFolder {
    const val FILE_NAME = "nuvio-reshaped-sync.json"
    private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
    private val JSON = "application/json; charset=UTF-8".toMediaType()

    /** Signed out (or access withdrawn) while syncing. */
    class SignedOutException : Exception("Not signed in to Google")

    suspend fun read(context: Context): DriveSyncFile {
        val listUrl = FILES_URL.toHttpUrl().newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("q", "name = '$FILE_NAME' and trashed = false")
            .addQueryParameter("fields", "files(id,modifiedTime)")
            .addQueryParameter("orderBy", "modifiedTime desc")
            .addQueryParameter("pageSize", "10")
            .build()
        val files = call(context) { token -> Request.Builder().url(listUrl).header("Authorization", "Bearer $token").build() }
            .let { JSONObject(it).optJSONArray("files") ?: JSONArray() }
        val ids = (0 until files.length()).mapNotNull { files.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
        val id = ids.firstOrNull() ?: return DriveSyncFile(null, null)
        val others = ids.drop(1).mapNotNull { other -> runCatching { other to download(context, other) }.getOrNull() }
        return DriveSyncFile(id, download(context, id), others)
    }

    private suspend fun download(context: Context, id: String): String = call(context) { token ->
        Request.Builder().url("$FILES_URL/$id?alt=media").header("Authorization", "Bearer $token").build()
    }

    /** Deletes the file [id]; one already gone is fine. */
    suspend fun delete(context: Context, id: String) {
        try {
            call(context) { token -> Request.Builder().url("$FILES_URL/$id").header("Authorization", "Bearer $token").delete().build() }
        } catch (gone: NotFoundException) {
            // Deleted by the other device meanwhile.
        }
    }

    /** Writes [text] to the file [id], or a new file when [id] is null. Returns the file's id. */
    suspend fun write(context: Context, id: String?, text: String): String {
        if (id != null) {
            val updated = runCatching {
                call(context) { token ->
                    Request.Builder()
                        .url("$UPLOAD_URL/$id?uploadType=media&fields=id")
                        .header("Authorization", "Bearer $token")
                        .patch(text.toRequestBody(JSON))
                        .build()
                }
            }
            updated.exceptionOrNull()?.let { error -> if (error !is NotFoundException) throw error }
            if (updated.isSuccess) return id
            // The file was deleted meanwhile (the viewer cleared the app's Drive data): create it again.
        }
        val metadata = JSONObject().put("name", FILE_NAME).put("parents", JSONArray().put("appDataFolder")).toString()
        val created = call(context) { token ->
            val body = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(metadata.toRequestBody(JSON))
                .addPart(text.toRequestBody(JSON))
                .build()
            Request.Builder()
                .url("$UPLOAD_URL?uploadType=multipart&fields=id")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
        }
        return JSONObject(created).getString("id")
    }

    private class NotFoundException : IOException("Not found")

    /** Runs a request with a fresh token, once more with a refreshed one on HTTP 401. */
    private suspend fun call(context: Context, build: (String) -> Request): String {
        repeat(2) { attempt ->
            val token = GoogleAccount.accessToken(context, forceRefresh = attempt > 0) ?: throw SignedOutException()
            val result = runInterruptible(Dispatchers.IO) {
                GoogleAccount.http.newCall(build(token)).execute().use { response -> response.textOrError() }
            }
            if (result != null) return result
        }
        throw SignedOutException()
    }

    /** The body, or null for HTTP 401 (retry with a new token); other failures throw. */
    private fun Response.textOrError(): String? = when {
        isSuccessful -> body?.string().orEmpty()
        code == 401 -> null
        code == 404 -> throw NotFoundException()
        else -> {
            // Google's reason (Drive API not enabled, quota) so the settings can show it.
            val reason = runCatching { JSONObject(body?.string().orEmpty()).optJSONObject("error")?.optString("message") }.getOrNull()
            throw IOException(if (reason.isNullOrBlank()) "Drive HTTP $code" else "Drive HTTP $code: $reason")
        }
    }
}
