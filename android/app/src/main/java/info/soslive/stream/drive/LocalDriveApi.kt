package info.soslive.stream.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Simulated Drive for development without a Google Cloud project: the same operations, stored
 * in the app's private files. Links built from these ids cannot be opened by anyone else.
 */
class LocalDriveApi(
    private val root: File,
    private val clock: Clock = Clock.systemUTC(),
) : DriveApi {

    @Serializable
    private data class Meta(
        val id: String,
        val name: String,
        val tag: String,
        val parentId: String?,
        val folder: Boolean,
        val createdMillis: Long,
        val trashed: Boolean = false,
        val shared: Boolean = false,
    )

    private val mutex = Mutex()
    private val indexFile get() = File(root, "index.json")

    private fun readIndex(): MutableList<Meta> =
        if (indexFile.exists()) DriveJson.decodeFromString(ListSerializer(Meta.serializer()), indexFile.readText()).toMutableList()
        else mutableListOf()

    private fun writeIndex(items: List<Meta>) {
        root.mkdirs()
        indexFile.writeText(DriveJson.encodeToString(ListSerializer(Meta.serializer()), items))
    }

    private suspend fun <T> locked(block: (MutableList<Meta>) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block(readIndex()) }
    }

    private fun Meta.toFile() = DriveFile(id, name, Instant.ofEpochMilli(createdMillis), trashed)

    private fun MutableList<Meta>.require(id: String): Meta =
        firstOrNull { it.id == id && !it.trashed } ?: throw DriveNotFoundException("File not found: $id")

    override suspend fun findByTag(tag: String, parentId: String?, newestFirst: Boolean, folderOnly: Boolean) = locked { index ->
        index.filter { it.tag == tag && !it.trashed && (!folderOnly || it.folder) && (parentId == null || it.parentId == parentId) }
            .sortedBy { it.createdMillis }
            .let { if (newestFirst) it.reversed() else it }
            .map { it.toFile() }
    }

    override suspend fun getFile(id: String) = locked { index -> index.firstOrNull { it.id == id }?.toFile() }

    override suspend fun createFolder(name: String, tag: String) = locked { index ->
        val meta = Meta(UUID.randomUUID().toString(), name, tag, null, folder = true, createdMillis = clock.millis())
        index += meta
        writeIndex(index)
        meta.toFile()
    }

    override suspend fun createFile(name: String, mimeType: String, parentId: String, tag: String, content: ByteArray) = locked { index ->
        index.require(parentId)
        val meta = Meta(UUID.randomUUID().toString(), name, tag, parentId, folder = false, createdMillis = clock.millis())
        File(root, meta.id).writeBytes(content)
        index += meta
        writeIndex(index)
        meta.toFile()
    }

    override suspend fun updateContent(id: String, mimeType: String, content: ByteArray) = locked { index ->
        index.require(id)
        File(root, id).writeBytes(content)
    }

    override suspend fun download(id: String): ByteArray = locked { index ->
        index.require(id)
        File(root, id).readBytes()
    }

    override suspend fun shareAnyoneReader(id: String) = locked { index ->
        val position = index.indexOfFirst { it.id == id }
        if (position < 0) throw DriveNotFoundException("File not found: $id")
        index[position] = index[position].copy(shared = true)
        writeIndex(index)
    }

    override suspend fun trash(id: String) = locked { index ->
        val position = index.indexOfFirst { it.id == id }
        if (position < 0) throw DriveNotFoundException("File not found: $id")
        index[position] = index[position].copy(trashed = true)
        writeIndex(index)
    }

    override fun publicImageUrl(id: String): String = File(root, id).toURI().toString()
}
