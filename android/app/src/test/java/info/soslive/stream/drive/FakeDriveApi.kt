package info.soslive.stream.drive

import java.time.Instant

/** In-memory DriveApi with failure injection for updateContent. */
class FakeDriveApi : DriveApi {
    data class Item(
        val file: DriveFile,
        val tag: String,
        var parentId: String?,
        val folder: Boolean,
        var content: ByteArray = ByteArray(0),
        var shared: Boolean = false,
        val viewers: MutableList<DrivePermission> = mutableListOf(),
    )

    val items = mutableListOf<Item>()
    val uploads = mutableListOf<ByteArray>()
    /** Thrown (in order) by the next updateContent calls. */
    val updateFailures = ArrayDeque<Exception>()
    var shareFailure: DriveException? = null
    private var seq = 0
    private var time = Instant.parse("2026-01-01T00:00:00Z")

    private fun nextId() = "id${++seq}"
    private fun tick(): Instant = time.also { time = time.plusSeconds(1) }

    fun addFolder(id: String, created: Instant, trashed: Boolean = false) {
        items += Item(DriveFile(id, FOLDER_NAME, created, trashed), DriveTags.ROOT, null, folder = true)
    }

    override suspend fun findByTag(tag: String, parentId: String?, newestFirst: Boolean, folderOnly: Boolean): List<DriveFile> =
        items.filter { it.tag == tag && !it.file.trashed && (!folderOnly || it.folder) && (parentId == null || it.parentId == parentId) }
            .sortedBy { it.file.createdTime }
            .let { if (newestFirst) it.reversed() else it }
            .map { it.file }

    override suspend fun getFile(id: String): DriveFile? = items.firstOrNull { it.file.id == id }?.file

    override suspend fun createFolder(name: String, tag: String, parentId: String?): DriveFile =
        DriveFile(nextId(), name, tick()).also { items += Item(it, tag, parentId, folder = true) }

    fun addFile(id: String, tag: String, parentId: String) {
        items += Item(DriveFile(id, "$id.json", tick()), tag, parentId, folder = false)
    }

    override suspend fun createFile(name: String, mimeType: String, parentId: String, tag: String, content: ByteArray): DriveFile {
        if (items.none { it.file.id == parentId && !it.file.trashed }) throw DriveNotFoundException("parent $parentId")
        return DriveFile(nextId(), name, tick()).also { items += Item(it, tag, parentId, folder = false, content = content) }
    }

    override suspend fun updateContent(id: String, mimeType: String, content: ByteArray) {
        updateFailures.removeFirstOrNull()?.let { throw it }
        val item = items.firstOrNull { it.file.id == id && !it.file.trashed } ?: throw DriveNotFoundException(id)
        item.content = content
        uploads += content
    }

    override suspend fun download(id: String): ByteArray =
        items.firstOrNull { it.file.id == id && !it.file.trashed }?.content ?: throw DriveNotFoundException(id)

    override suspend fun shareAnyoneReader(id: String) {
        shareFailure?.let { throw it }
        items.first { it.file.id == id }.shared = true
    }

    override suspend fun trash(id: String) {
        val index = items.indexOfFirst { it.file.id == id }
        items[index] = items[index].copy(file = items[index].file.copy(trashed = true))
    }

    override suspend fun moveFile(id: String, fromParentId: String, toParentId: String) {
        val item = items.firstOrNull { it.file.id == id } ?: throw DriveNotFoundException(id)
        check(item.parentId == fromParentId)
        item.parentId = toParentId
    }

    override suspend fun listUserPermissions(id: String): List<DrivePermission> =
        (items.firstOrNull { it.file.id == id && !it.file.trashed } ?: throw DriveNotFoundException(id)).viewers.toList()

    override suspend fun shareWithUser(id: String, email: String): DrivePermission =
        DrivePermission("p${++seq}", email).also { item(id).viewers += it }

    override suspend fun removePermission(id: String, permissionId: String) {
        if (!item(id).viewers.removeAll { it.id == permissionId }) throw DriveNotFoundException(permissionId)
    }

    fun item(id: String) = items.first { it.file.id == id }
}

class InMemoryDriveCache : DriveCache {
    var folder: String? = null
    var configBytes: ByteArray? = null
    override suspend fun folderId() = folder
    override suspend fun setFolderId(id: String?) { folder = id }
    override suspend fun config() = configBytes
    override suspend fun setConfig(bytes: ByteArray?) { configBytes = bytes }
}
