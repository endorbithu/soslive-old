package info.soslive.stream.drive

import java.time.Clock
import java.time.Instant

/** An event file that was just created on Drive. */
data class CreatedEvent(
    val fileId: String,
    val name: String,
    val link: String,
    /** False when the "anyone with the link" share failed (e.g. a Workspace policy forbids it). */
    val shared: Boolean,
    val shareError: String? = null,
)

data class EventSummary(val fileId: String, val title: String, val createdTime: Instant?, val link: String)

/** Local copy of small bits of state (folder id, last config) - see [DataStoreDriveCache]. */
interface DriveCache {
    suspend fun folderId(): String?
    suspend fun setFolderId(id: String?)
    suspend fun config(): ByteArray?
    suspend fun setConfig(bytes: ByteArray?)
}

/**
 * The SOSlive folder on the user's Drive:
 * ```
 * SOSlive/            private
 *   config.json       private
 *   events/           events + images; shared read-only with the people the user picks
 * ```
 * Follows the mobile app spec: the oldest "SOSlive" (and "events") folder wins, event files are
 * also shared "anyone with the link", old events are rotated to the trash.
 */
class SosliveDrive(
    private val drive: DriveApi,
    private val cache: DriveCache,
    private val webappUrl: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** (root folder id, events folder id) once verified in this process. */
    @Volatile private var eventsFolder: Pair<String, String>? = null

    fun link(fileId: String): String = "${webappUrl.trimEnd('/')}/e/$fileId"

    /** Finds (or creates) the SOSlive folder. Re-searches when the cached id was deleted/trashed. */
    suspend fun folderId(): String {
        cache.folderId()?.let { cached ->
            val file = drive.getFile(cached)
            if (file != null && !file.trashed) return cached
            cache.setFolderId(null)
        }
        val id = findOldestFolder() ?: run {
            drive.createFolder(FOLDER_NAME, DriveTags.ROOT)
            // If another client (e.g. the web) created one at the same time, the oldest wins.
            findOldestFolder() ?: error("Created SOSlive folder is not visible")
        }
        cache.setFolderId(id)
        return id
    }

    private suspend fun findOldestFolder(): String? =
        drive.findByTag(DriveTags.ROOT, folderOnly = true).firstOrNull()?.id

    /** Runs [block] with the folder id; if the folder vanished meanwhile (404), finds it again once. */
    private suspend fun <T> inFolder(block: suspend (String) -> T): T = try {
        block(folderId())
    } catch (e: DriveNotFoundException) {
        cache.setFolderId(null)
        block(folderId())
    }

    /**
     * Finds (or creates) SOSlive/events. Events and images left directly in the root folder
     * (written before the events folder existed) are moved into it.
     */
    suspend fun eventsFolderId(): String {
        val root = folderId()
        eventsFolder?.let { (cachedRoot, events) -> if (cachedRoot == root) return events }
        val existing = drive.findByTag(DriveTags.EVENTS, parentId = root, folderOnly = true).firstOrNull()?.id
        val id = existing ?: run {
            drive.createFolder(EVENTS_FOLDER_NAME, DriveTags.EVENTS, root)
            drive.findByTag(DriveTags.EVENTS, parentId = root, folderOnly = true).firstOrNull()?.id
                ?: error("Created events folder is not visible")
        }
        moveLooseFiles(root, id)
        eventsFolder = root to id
        return id
    }

    private suspend fun moveLooseFiles(root: String, events: String) {
        val loose = drive.findByTag(DriveTags.EVENT, parentId = root) + drive.findByTag(DriveTags.IMAGE, parentId = root)
        // Best effort - a file that cannot be moved now is retried on the next app start.
        loose.forEach { runCatching { drive.moveFile(it.id, root, events) } }
    }

    /** Runs [block] with the events folder id; if a folder vanished meanwhile (404), finds them again once. */
    private suspend fun <T> inEventsFolder(block: suspend (String) -> T): T = try {
        block(eventsFolderId())
    } catch (e: DriveNotFoundException) {
        eventsFolder = null
        cache.setFolderId(null)
        block(eventsFolderId())
    }

    // ---------------------------------------------------------------- config.json

    /** config.json from Drive; null when the user has none yet. */
    suspend fun readRemoteConfig(): SosConfig? = inFolder { folder ->
        val file = drive.findByTag(DriveTags.CONFIG, parentId = folder).firstOrNull() ?: return@inFolder null
        val bytes = drive.download(file.id)
        cache.setConfig(bytes)
        SosConfig.parse(bytes)
    }

    /** Local copy - used when offline and to avoid a Drive round trip before every event. */
    suspend fun cachedConfig(): SosConfig? = cache.config()?.let { runCatching { SosConfig.parse(it) }.getOrNull() }

    /** Writes config.json (create or overwrite the whole file). Only the mobile app writes it. */
    suspend fun saveConfig(config: SosConfig) {
        val bytes = config.encode()
        inFolder { folder ->
            val existing = drive.findByTag(DriveTags.CONFIG, parentId = folder).firstOrNull()
            if (existing == null) {
                drive.createFile(CONFIG_NAME, JSON_MIME, folder, DriveTags.CONFIG, bytes)
            } else {
                drive.updateContent(existing.id, JSON_MIME, bytes)
            }
        }
        cache.setConfig(bytes)
    }

    // ---------------------------------------------------------------- events

    /** Creates the event file, shares it "anyone with the link" and returns the link to send out. */
    suspend fun createEvent(start: Instant, document: EventDocument): CreatedEvent {
        val name = eventFileName(start)
        val file = inEventsFolder { folder -> drive.createFile(name, JSON_MIME, folder, DriveTags.EVENT, document.encode()) }
        val shareError = try {
            drive.shareAnyoneReader(file.id)
            null
        } catch (e: DriveException) {
            e.message ?: "HTTP ${e.status}"
        }
        return CreatedEvent(file.id, name, link(file.id), shared = shareError == null, shareError = shareError)
    }

    suspend fun readEvent(fileId: String): EventDocument = EventDocument.parse(drive.download(fileId))

    suspend fun listEvents(): List<EventSummary> = inEventsFolder { folder ->
        drive.findByTag(DriveTags.EVENT, parentId = folder, newestFirst = true)
            .map { EventSummary(it.id, eventTitle(it.name), it.createdTime, link(it.id)) }
    }

    /** Moves events beyond the newest [maxEvents] to the trash (restorable for 30 days). */
    suspend fun rotate(maxEvents: Int): Int {
        val events = inEventsFolder { folder -> drive.findByTag(DriveTags.EVENT, parentId = folder, newestFirst = true) }
        val old = events.drop(maxEvents.coerceAtLeast(1))
        old.forEach { drive.trash(it.id) }
        return old.size
    }

    /** Uploads a JPEG next to the events, shares it publicly and returns a URL usable in an <img>. */
    suspend fun uploadImage(jpeg: ByteArray): String {
        val name = "img " + eventFileName(Instant.now(clock)).removeSuffix(".json") + ".jpg"
        val file = inEventsFolder { folder -> drive.createFile(name, "image/jpeg", folder, DriveTags.IMAGE, jpeg) }
        drive.shareAnyoneReader(file.id)
        return drive.publicImageUrl(file.id)
    }

    // ---------------------------------------------------------------- viewers

    /** People who can see all events (read-only share of the events folder). */
    suspend fun viewers(): List<DrivePermission> = inEventsFolder { drive.listUserPermissions(it) }

    /** Shares the events folder read-only with [email]; Google sends them an e-mail with the link. */
    suspend fun addViewer(email: String): DrivePermission = inEventsFolder { drive.shareWithUser(it, email.trim()) }

    suspend fun removeViewer(permissionId: String) = inEventsFolder { drive.removePermission(it, permissionId) }

    suspend fun forgetLocalState() {
        eventsFolder = null
        cache.setFolderId(null)
        cache.setConfig(null)
    }
}
