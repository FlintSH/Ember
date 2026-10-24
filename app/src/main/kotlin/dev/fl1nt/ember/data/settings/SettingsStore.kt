package dev.fl1nt.ember.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.fl1nt.ember.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Which Flare address Ember copies after an upload. */
enum class LinkFormat {
    /** The share page, which respects passwords and shows previews. */
    SHARE_PAGE,

    /** The raw file, for embedding. */
    RAW_FILE,
}

data class SyncPreferences(
    val enabled: Boolean = true,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val intervalMinutes: Int = 60,
)

data class EmberSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val defaults: UploadPreset = UploadPreset(),
    val copyLinkAfterUpload: Boolean = true,
    val linkFormat: LinkFormat = LinkFormat.SHARE_PAGE,
    /** Show the review sheet before uploading shared files. Off uploads right away. */
    val reviewShares: Boolean = true,
    val notifyWhenDone: Boolean = true,
    val pasteFileName: String = DEFAULT_PASTE_NAME,
    /** Replace selected text with the paste link when the app allows it. */
    val replaceSelection: Boolean = true,
    val sync: SyncPreferences = SyncPreferences(),
) {
    companion object {
        const val DEFAULT_PASTE_NAME = "paste.txt"
        val SYNC_INTERVALS = listOf(15, 60, 360, 1440)
    }
}

class SettingsStore(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<EmberSettings> = dataStore.data.map { it.toSettings() }

    suspend fun current(): EmberSettings = settings.first()

    suspend fun update(transform: (EmberSettings) -> EmberSettings) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[THEME] = next.themeMode.name
            prefs[DEFAULTS] = next.defaults.encode()
            prefs[COPY_LINK] = next.copyLinkAfterUpload
            prefs[LINK_FORMAT] = next.linkFormat.name
            prefs[REVIEW_SHARES] = next.reviewShares
            prefs[NOTIFY] = next.notifyWhenDone
            prefs[PASTE_NAME] = next.pasteFileName.trim().ifEmpty { EmberSettings.DEFAULT_PASTE_NAME }
            prefs[REPLACE_SELECTION] = next.replaceSelection
            prefs[SYNC_ENABLED] = next.sync.enabled
            prefs[SYNC_WIFI_ONLY] = next.sync.wifiOnly
            prefs[SYNC_CHARGING_ONLY] = next.sync.chargingOnly
            prefs[SYNC_INTERVAL] = next.sync.intervalMinutes.coerceAtLeast(15)
        }
    }

    private fun Preferences.toSettings() = EmberSettings(
        themeMode = enumOr(this[THEME], ThemeMode.DARK),
        defaults = UploadPreset.decode(this[DEFAULTS]),
        copyLinkAfterUpload = this[COPY_LINK] ?: true,
        linkFormat = enumOr(this[LINK_FORMAT], LinkFormat.SHARE_PAGE),
        reviewShares = this[REVIEW_SHARES] ?: true,
        notifyWhenDone = this[NOTIFY] ?: true,
        pasteFileName = this[PASTE_NAME] ?: EmberSettings.DEFAULT_PASTE_NAME,
        replaceSelection = this[REPLACE_SELECTION] ?: true,
        sync = SyncPreferences(
            enabled = this[SYNC_ENABLED] ?: true,
            wifiOnly = this[SYNC_WIFI_ONLY] ?: true,
            chargingOnly = this[SYNC_CHARGING_ONLY] ?: false,
            intervalMinutes = this[SYNC_INTERVAL] ?: 60,
        ),
    )

    private inline fun <reified T : Enum<T>> enumOr(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val DEFAULTS = stringPreferencesKey("upload_defaults")
        val COPY_LINK = booleanPreferencesKey("copy_link")
        val LINK_FORMAT = stringPreferencesKey("link_format")
        val REVIEW_SHARES = booleanPreferencesKey("review_shares")
        val NOTIFY = booleanPreferencesKey("notify_done")
        val PASTE_NAME = stringPreferencesKey("paste_name")
        val REPLACE_SELECTION = booleanPreferencesKey("replace_selection")
        val SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val SYNC_WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")
        val SYNC_CHARGING_ONLY = booleanPreferencesKey("sync_charging_only")
        val SYNC_INTERVAL = intPreferencesKey("sync_interval_minutes")
    }
}
