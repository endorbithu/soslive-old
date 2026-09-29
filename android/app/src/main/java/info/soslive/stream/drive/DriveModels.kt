package info.soslive.stream.drive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// File formats shared with the web app (endorbithu/soslive-webapp docs/EVENT_FORMAT.md).

val DriveJson = Json { ignoreUnknownKeys = true; prettyPrint = false }

/** appProperties {"soslive": <tag>} values. */
object DriveTags {
    const val ROOT = "root"
    const val CONFIG = "config"
    const val EVENT = "event"
    const val IMAGE = "image"
}

const val FOLDER_NAME = "SOSlive"
const val CONFIG_NAME = "config.json"
const val JSON_MIME = "application/json"
const val FOLDER_MIME = "application/vnd.google-apps.folder"

private val EVENT_NAME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

/** Event file name: the start time in UTC, e.g. "2026-09-29 14:03:22.json". */
fun eventFileName(start: Instant): String = EVENT_NAME_FORMAT.format(start) + ".json"

/** The web shows the file name without ".json" as the event title. */
fun eventTitle(fileName: String): String = fileName.removeSuffix(".json")

/** ISO 8601 UTC timestamp with second precision, e.g. "2026-09-29T14:03:22Z". */
fun isoUtc(time: Instant): String = time.truncatedTo(ChronoUnit.SECONDS).toString()

/**
 * config.json - written only by the mobile app. Unknown fields are kept in [raw] and written back,
 * so the format can grow without the app dropping data.
 */
data class SosConfig(
    val notificationEmails: List<String> = emptyList(),
    val notificationPhones: List<String> = emptyList(),
    val maxEvents: Int = DEFAULT_MAX_EVENTS,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    fun toJson(): JsonObject = buildJsonObject {
        raw.forEach { (key, value) -> put(key, value) }
        put("v", JsonPrimitive(1))
        put("notification_emails", JsonArray(notificationEmails.map(::JsonPrimitive)))
        put("notification_phones", JsonArray(notificationPhones.map(::JsonPrimitive)))
        put("max_events", JsonPrimitive(maxEvents))
    }

    fun encode(): ByteArray = DriveJson.encodeToString(JsonObject.serializer(), toJson()).toByteArray()

    companion object {
        const val DEFAULT_MAX_EVENTS = 100

        fun parse(bytes: ByteArray): SosConfig = parse(DriveJson.parseToJsonElement(bytes.decodeToString()).jsonObject)

        fun parse(obj: JsonObject): SosConfig = SosConfig(
            notificationEmails = obj.stringList("notification_emails"),
            notificationPhones = obj.stringList("notification_phones"),
            maxEvents = (obj["max_events"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 1 } ?: DEFAULT_MAX_EVENTS,
            raw = obj,
        )

        private fun JsonObject.stringList(key: String): List<String> =
            (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    }
}

/** One entry of an event file. Unknown entry types / fields are preserved as-is. */
@JvmInline
value class EventEntry(val json: JsonObject) {
    val type: String? get() = string("type")
    val time: String? get() = string("t")
    fun string(key: String): String? = (json[key] as? JsonPrimitive)?.contentOrNull
    fun double(key: String): Double? = (json[key] as? JsonPrimitive)?.doubleOrNull

    companion object {
        fun position(time: Instant, lat: Double, lng: Double) = EventEntry(buildJsonObject {
            put("t", JsonPrimitive(isoUtc(time)))
            put("type", JsonPrimitive("pos"))
            put("lat", JsonPrimitive(lat))
            put("lng", JsonPrimitive(lng))
        })

        /** [name] is the displayed sender - never a phone number or e-mail address. */
        fun message(time: Instant, name: String, text: String) = EventEntry(buildJsonObject {
            put("t", JsonPrimitive(isoUtc(time)))
            put("type", JsonPrimitive("msg"))
            put("name", JsonPrimitive(name))
            put("text", JsonPrimitive(text))
        })

        /** [url] must be a publicly readable http(s) image URL. */
        fun image(time: Instant, url: String) = EventEntry(buildJsonObject {
            put("t", JsonPrimitive(isoUtc(time)))
            put("type", JsonPrimitive("img"))
            put("url", JsonPrimitive(url))
        })
    }
}

/** Event file: `{"v": 1, "stream": "...", "entries": [...]}`. */
data class EventDocument(
    val stream: String = "",
    val entries: List<EventEntry> = emptyList(),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    fun toJson(): JsonObject = buildJsonObject {
        raw.forEach { (key, value) -> put(key, value) }
        put("v", JsonPrimitive(1))
        put("stream", JsonPrimitive(stream))
        put("entries", JsonArray(entries.map<EventEntry, JsonElement> { it.json }))
    }

    fun encode(): ByteArray = DriveJson.encodeToString(JsonObject.serializer(), toJson()).toByteArray()

    companion object {
        fun parse(bytes: ByteArray): EventDocument {
            val obj = DriveJson.parseToJsonElement(bytes.decodeToString()).jsonObject
            return EventDocument(
                stream = (obj["stream"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                entries = (obj["entries"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::EventEntry) }.orEmpty(),
                raw = obj,
            )
        }
    }
}

/** Validation rules of config.json (see the guide). */
object ContactRules {
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val PHONE = Regex("^\\+[0-9 ()\\-]+$")

    fun isValidEmail(value: String): Boolean = value.length <= 128 && EMAIL.matches(value)

    /** International format: "+" then digits, spaces, "(", ")", "-"; 7-15 digits. */
    fun isValidPhone(value: String): Boolean =
        PHONE.matches(value) && value.count(Char::isDigit) in 7..15

    /** Number for SmsManager / smsto: "+" and digits only. */
    fun dialable(phone: String): String = "+" + phone.filter(Char::isDigit)

    /** Splits user input (comma / semicolon / newline separated), trims, drops duplicates. */
    fun split(input: String): List<String> =
        input.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
