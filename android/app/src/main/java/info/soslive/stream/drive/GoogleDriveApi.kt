package info.soslive.stream.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

/** Supplies OAuth access tokens with the drive.file scope. */
fun interface AccessTokenProvider {
    /** @param forceRefresh true after a 401 - the cached token is stale. */
    suspend fun accessToken(forceRefresh: Boolean): String
}

/** Drive REST API v3 over OkHttp, authenticated with the user's own Google token. */
class GoogleDriveApi(
    private val client: OkHttpClient,
    private val tokens: AccessTokenProvider,
    baseUrl: String = "https://www.googleapis.com/",
) : DriveApi {

    private val api = baseUrl.toHttpUrl().resolve("drive/v3/")!!
    private val upload = baseUrl.toHttpUrl().resolve("upload/drive/v3/")!!

    override suspend fun findByTag(tag: String, parentId: String?, newestFirst: Boolean, folderOnly: Boolean): List<DriveFile> {
        val q = buildString {
            append("appProperties has { key='soslive' and value='").append(tag).append("' } and trashed=false")
            if (folderOnly) append(" and mimeType='").append(FOLDER_MIME).append("'")
            if (parentId != null) append(" and '").append(parentId).append("' in parents")
        }
        val result = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = api.newBuilder().addPathSegment("files")
                .addQueryParameter("q", q)
                .addQueryParameter("orderBy", if (newestFirst) "createdTime desc" else "createdTime")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("fields", "nextPageToken,files(id,name,createdTime,trashed)")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val body = json(execute { Request.Builder().url(url).get() })
            (body["files"] as? JsonArray)?.forEach { result += (it as JsonObject).toDriveFile() }
            pageToken = (body["nextPageToken"] as? JsonPrimitive)?.contentOrNull
        } while (pageToken != null)
        return result
    }

    override suspend fun getFile(id: String): DriveFile? = try {
        val url = api.newBuilder().addPathSegment("files").addPathSegment(id)
            .addQueryParameter("fields", "id,name,createdTime,trashed").build()
        json(execute { Request.Builder().url(url).get() }).toDriveFile()
    } catch (_: DriveNotFoundException) {
        null
    }

    override suspend fun createFolder(name: String, tag: String): DriveFile {
        val metadata = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("mimeType", JsonPrimitive(FOLDER_MIME))
            put("appProperties", buildJsonObject { put("soslive", JsonPrimitive(tag)) })
        }
        val url = api.newBuilder().addPathSegment("files").addQueryParameter("fields", FILE_FIELDS).build()
        return json(execute { Request.Builder().url(url).post(metadata.toBody()) }).toDriveFile()
    }

    override suspend fun createFile(name: String, mimeType: String, parentId: String, tag: String, content: ByteArray): DriveFile {
        val metadata = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("mimeType", JsonPrimitive(mimeType))
            put("parents", JsonArray(listOf(JsonPrimitive(parentId))))
            put("appProperties", buildJsonObject { put("soslive", JsonPrimitive(tag)) })
        }
        val url = upload.newBuilder().addPathSegment("files")
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", FILE_FIELDS).build()
        val multipart = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toBody())
            .addPart(content.toRequestBody(mimeType.toMediaType()))
            .build()
        return json(execute { Request.Builder().url(url).post(multipart) }).toDriveFile()
    }

    override suspend fun updateContent(id: String, mimeType: String, content: ByteArray) {
        val url = upload.newBuilder().addPathSegment("files").addPathSegment(id)
            .addQueryParameter("uploadType", "media")
            .addQueryParameter("fields", "id").build()
        execute { Request.Builder().url(url).patch(content.toRequestBody(mimeType.toMediaType())) }
    }

    override suspend fun download(id: String): ByteArray {
        val url = api.newBuilder().addPathSegment("files").addPathSegment(id).addQueryParameter("alt", "media").build()
        return execute { Request.Builder().url(url).get() }
    }

    override suspend fun shareAnyoneReader(id: String) {
        val url = api.newBuilder().addPathSegment("files").addPathSegment(id).addPathSegment("permissions")
            .addQueryParameter("fields", "id").build()
        val body = buildJsonObject {
            put("type", JsonPrimitive("anyone"))
            put("role", JsonPrimitive("reader"))
        }
        execute { Request.Builder().url(url).post(body.toBody()) }
    }

    override suspend fun trash(id: String) {
        val url = api.newBuilder().addPathSegment("files").addPathSegment(id).addQueryParameter("fields", "id").build()
        execute { Request.Builder().url(url).patch(buildJsonObject { put("trashed", JsonPrimitive(true)) }.toBody()) }
    }

    // --- plumbing ---

    /** Runs the request with a bearer token; on 401 refreshes the token once and retries. */
    private suspend fun execute(build: () -> Request.Builder): ByteArray = withContext(Dispatchers.IO) {
        val first = call(build, tokens.accessToken(forceRefresh = false))
        val response = if (first.code == 401) call(build, tokens.accessToken(forceRefresh = true)) else first
        when {
            response.code in 200..299 -> response.bytes
            response.code == 404 -> throw DriveNotFoundException(errorMessage(response.bytes, response.message))
            else -> throw DriveException(response.code, errorMessage(response.bytes, response.message))
        }
    }

    private class RawResponse(val code: Int, val message: String, val bytes: ByteArray)

    private fun call(build: () -> Request.Builder, token: String): RawResponse =
        client.newCall(build().header("Authorization", "Bearer $token").build()).execute().use {
            RawResponse(it.code, it.message, it.body?.bytes() ?: ByteArray(0))
        }

    private fun json(bytes: ByteArray): JsonObject = DriveJson.parseToJsonElement(bytes.decodeToString()).jsonObject

    private fun errorMessage(bytes: ByteArray, fallback: String): String = runCatching {
        val error = json(bytes)["error"]?.jsonObject
        val reason = ((error?.get("errors") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("reason")
        listOfNotNull((error?.get("message") as? JsonPrimitive)?.contentOrNull, (reason as? JsonPrimitive)?.contentOrNull)
            .joinToString(" / ")
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    private fun JsonObject.toBody(): RequestBody =
        DriveJson.encodeToString(JsonObject.serializer(), this).toRequestBody("application/json; charset=UTF-8".toMediaType())

    private fun JsonObject.toDriveFile() = DriveFile(
        id = (this["id"] as JsonPrimitive).content,
        name = (this["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        createdTime = (this["createdTime"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() },
        trashed = (this["trashed"] as? JsonPrimitive)?.booleanOrNull ?: false,
    )

    private companion object {
        const val FILE_FIELDS = "id,name,createdTime,trashed"
    }
}
