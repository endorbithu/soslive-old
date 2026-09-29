package info.soslive.stream.drive

import java.io.IOException
import java.time.Instant

data class DriveFile(
    val id: String,
    val name: String,
    val createdTime: Instant?,
    val trashed: Boolean = false,
)

/** HTTP error from the Drive API. */
open class DriveException(val status: Int, message: String) : IOException(message) {
    /** 429, 5xx and 403 rate limits are worth retrying; everything else is not. */
    val retryable: Boolean
        get() = status == 429 || status >= 500 || (status == 403 && RATE_LIMIT.containsMatchIn(message ?: ""))

    private companion object {
        val RATE_LIMIT = Regex("rateLimitExceeded|userRateLimitExceeded", RegexOption.IGNORE_CASE)
    }
}

/** 404 - the file (or its folder) was deleted by the user. */
class DriveNotFoundException(message: String) : DriveException(404, message)

/** The user has not granted (or revoked) the drive.file permission. */
class DriveConsentRequiredException : IOException("Google Drive permission is required")

/**
 * The few Drive operations SOSlive needs. Files are found by their appProperties tag
 * ({"soslive": tag}); with the drive.file scope the app only ever sees files it created.
 */
interface DriveApi {
    /** Non-trashed files with the tag, optionally inside [parentId], ordered by creation time. */
    suspend fun findByTag(tag: String, parentId: String? = null, newestFirst: Boolean = false, folderOnly: Boolean = false): List<DriveFile>

    /** Metadata, or null if the file does not exist any more. */
    suspend fun getFile(id: String): DriveFile?

    suspend fun createFolder(name: String, tag: String): DriveFile

    suspend fun createFile(name: String, mimeType: String, parentId: String, tag: String, content: ByteArray): DriveFile

    /** Replaces the whole content of the file. */
    suspend fun updateContent(id: String, mimeType: String, content: ByteArray)

    suspend fun download(id: String): ByteArray

    /** "anyone with the link" reader permission. */
    suspend fun shareAnyoneReader(id: String)

    suspend fun trash(id: String)

    /** URL that shows a publicly shared image in an <img> tag. */
    fun publicImageUrl(id: String): String = "https://drive.google.com/thumbnail?id=$id&sz=w1600"
}
