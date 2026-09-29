package info.soslive.stream.drive

import info.soslive.stream.core.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Single writer of one event file. Keeps the whole document in memory, appends entries and
 * uploads the complete latest state (entries created close together go up in one upload).
 * Network errors, 429 and 5xx are retried with a growing delay; a 404 (the user deleted the
 * file) stops writing and is reported through [onStopped].
 */
class EventWriter(
    private val drive: DriveApi,
    val fileId: String,
    initial: EventDocument,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val debounceMillis: Long = 1_500,
    private val initialBackoffMillis: Long = 2_000,
    private val maxBackoffMillis: Long = 60_000,
    private val positionIntervalMillis: Long = AppConfig.POSITION_INTERVAL_MILLIS,
    private val onStopped: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private var document = initial
    private var version = 0L
    private var uploadedVersion = 0L
    private var lastPositionAt: Instant? = initial.entries.lastOrNull { it.type == "pos" }?.time?.let { runCatching { Instant.parse(it) }.getOrNull() }
    private var job: Job? = null
    private val flushMutex = Mutex()

    @Volatile
    var stopped: Boolean = false
        private set

    val current: EventDocument get() = synchronized(lock) { document }

    /** True while there are changes that are not on Drive yet. */
    val hasPendingChanges: Boolean get() = synchronized(lock) { uploadedVersion != version }

    fun setStream(url: String) = update { it.copy(stream = url) }

    /** Adds a position unless the previous one is younger than 30 s. @return whether it was added. */
    fun addPosition(lat: Double, lng: Double, force: Boolean = false): Boolean {
        val now = Instant.now(clock)
        synchronized(lock) {
            val last = lastPositionAt
            if (!force && last != null && Duration.between(last, now).toMillis() < positionIntervalMillis) return false
            lastPositionAt = now
        }
        update { it.copy(entries = it.entries + EventEntry.position(now, lat, lng)) }
        return true
    }

    fun addMessage(name: String, text: String) =
        update { it.copy(entries = it.entries + EventEntry.message(Instant.now(clock), name, text)) }

    fun addImage(url: String) =
        update { it.copy(entries = it.entries + EventEntry.image(Instant.now(clock), url)) }

    private fun update(change: (EventDocument) -> EventDocument) {
        if (stopped) return
        synchronized(lock) {
            document = change(document)
            version++
            if (job == null || job?.isActive != true) {
                job = scope.launch {
                    delay(debounceMillis)
                    uploadLoop()
                }
            }
        }
    }

    /** Final upload when the event is closed: waits for pending changes to reach Drive. */
    suspend fun close() {
        job?.join()
        if (!stopped && hasPendingChanges) uploadLoop()
    }

    private suspend fun uploadLoop() = flushMutex.withLock {
        var backoff = initialBackoffMillis
        while (!stopped) {
            val (snapshot, snapshotVersion) = synchronized(lock) {
                if (uploadedVersion == version) {
                    job = null // cleared under the same lock update() checks, so no change is left behind
                    return@withLock
                }
                document to version
            }
            try {
                drive.updateContent(fileId, JSON_MIME, snapshot.encode())
                synchronized(lock) { uploadedVersion = maxOf(uploadedVersion, snapshotVersion) }
                backoff = initialBackoffMillis
            } catch (e: DriveNotFoundException) {
                stopped = true
                onStopped(e)
            } catch (e: DriveException) {
                if (!e.retryable) {
                    stopped = true
                    onStopped(e)
                } else {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(maxBackoffMillis)
                }
            } catch (e: DriveConsentRequiredException) {
                stopped = true
                onStopped(e)
            } catch (e: IOException) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(maxBackoffMillis)
            }
        }
    }
}
