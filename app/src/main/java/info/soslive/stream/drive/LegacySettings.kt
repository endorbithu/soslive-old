package info.soslive.stream.drive

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings left behind by the legacy Java app (same package name): SharedPreferences "YaseaRci",
 * key "smsTelNumbers" (comma separated). Used once to seed config.json when the user has none.
 */
@Singleton
class LegacySettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun phones(): List<String> {
        val prefs = context.getSharedPreferences("YaseaRci", Context.MODE_PRIVATE)
        return ContactRules.split(prefs.getString("smsTelNumbers", "").orEmpty()).filter(ContactRules::isValidPhone)
    }

    /** Seed for config.json, or null when there is nothing to migrate. */
    fun toConfig(): SosConfig? = phones().takeIf { it.isNotEmpty() }?.let { SosConfig(notificationPhones = it) }
}
