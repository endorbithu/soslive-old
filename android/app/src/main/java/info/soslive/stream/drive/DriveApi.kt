package info.soslive.stream.drive

import java.io.IOException
import java.time.Instant

data class DriveFile(
    val id: String,
    val name: String,
    val createdTime: Instant?,
    val trashed: Boolean = false,
)

/** A person the events folder is shared with (Drive "user" permission, not the owner). */
data class DrivePermission(val id: String, val email: String, val displayName: String = "")

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

    suspend fun createFolder(name: String, tag: String, parentId: String? = null): DriveFile

    suspend fun createFile(name: String, mimeType: String, parentId: String, tag: String, content: ByteArray): DriveFile

    /** Replaces the whole content of the file. */
    suspend fun updateContent(id: String, mimeType: String, content: ByteArray)

    suspend fun download(id: String): ByteArray

    /** "anyone with the link" reader permission. */
    suspend fun shareAnyoneReader(id: String)

    suspend fun trash(id: String)

    /** Moves a file from one folder to another. */
    suspend fun moveFile(id: String, fromParentId: String, toParentId: String)

    /** People the file / folder is shared with by e-mail (owner and "anyone" excluded). */
    suspend fun listUserPermissions(id: String): List<DrivePermission>

    /** Read-only share with one Google account; Google e-mails the person a link. */
    suspend fun shareWithUser(id: String, email: String): DrivePermission

    suspend fun removePermission(id: String, permissionId: String)

    /** URL that shows a publicly shared image in an <img> tag. */
    fun publicImageUrl(id: String): String = "https://drive.google.com/thumbnail?id=$id&sz=w1600"
}
