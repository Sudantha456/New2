package com.app.youtube.lite.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.app.youtube.lite.core.util.L
import com.app.youtube.lite.data.model.AppSettings
import com.app.youtube.lite.data.model.QualityCap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** DataStore instance, created once per process (the delegate is internally synchronised). */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "youtube_lite_settings",
)

/**
 * Settings, exposed both reactively and synchronously.
 *
 * The synchronous [current] snapshot exists for hot paths that must not suspend and must not
 * allocate: `LowRamLoadControl` reads a buffer ceiling while ExoPlayer allocates, and
 * `StreamResolver` reads the codec policy while building a ladder. A `StateFlow` collection in
 * the application scope keeps that snapshot fresh.
 */
class SettingsRepository(
    context: Context,
    private val scope: CoroutineScope,
) {

    private val store = context.applicationContext.settingsDataStore

    private val _current = MutableStateFlow(AppSettings.Default)

    /** Lock-free snapshot for hot paths. */
    val current: AppSettings get() = _current.value

    val flow: StateFlow<AppSettings> = _current.asStateFlow()

    init {
        scope.launch {
            store.data
                .catch { throwable ->
                    // A corrupt preferences file must not brick startup: fall back to defaults.
                    L.e("Settings") { "DataStore read failed, using defaults: $throwable" }
                    emit(emptyPreferences())
                }
                .map(::toSettings)
                .collect { _current.value = it }
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_current.value)
        _current.value = updated
        store.edit { prefs ->
            prefs[Keys.qualityCap] = updated.qualityCap.name
            prefs[Keys.preferH264] = updated.preferH264
            prefs[Keys.allowAv1] = updated.allowAv1
            prefs[Keys.allowVp9] = updated.allowVp9
            prefs[Keys.backgroundAudio] = updated.backgroundAudio
            prefs[Keys.autoplayNext] = updated.autoplayNext
            prefs[Keys.dataSaver] = updated.dataSaver
            prefs[Keys.highRefreshRate] = updated.highRefreshRate
            prefs[Keys.maxBufferMegabytes] = updated.maxBufferMegabytes
            prefs[Keys.doubleTapSeekSeconds] = updated.doubleTapSeekSeconds
        }
    }

    /** Convenience for the settings screen's switch rows. */
    fun toggleSetting(block: (AppSettings) -> AppSettings) {
        scope.launch { update(block) }
    }

    val dataFlow: Flow<AppSettings> get() = store.data.map(::toSettings)

    private fun toSettings(prefs: Preferences): AppSettings = AppSettings(
        qualityCap = prefs[Keys.qualityCap]
            ?.let { stored -> QualityCap.entries.firstOrNull { it.name == stored } }
            ?: AppSettings.Default.qualityCap,
        preferH264 = prefs[Keys.preferH264] ?: AppSettings.Default.preferH264,
        allowAv1 = prefs[Keys.allowAv1] ?: AppSettings.Default.allowAv1,
        allowVp9 = prefs[Keys.allowVp9] ?: AppSettings.Default.allowVp9,
        backgroundAudio = prefs[Keys.backgroundAudio] ?: AppSettings.Default.backgroundAudio,
        autoplayNext = prefs[Keys.autoplayNext] ?: AppSettings.Default.autoplayNext,
        dataSaver = prefs[Keys.dataSaver] ?: AppSettings.Default.dataSaver,
        highRefreshRate = prefs[Keys.highRefreshRate] ?: AppSettings.Default.highRefreshRate,
        maxBufferMegabytes = prefs[Keys.maxBufferMegabytes] ?: AppSettings.Default.maxBufferMegabytes,
        doubleTapSeekSeconds = prefs[Keys.doubleTapSeekSeconds] ?: AppSettings.Default.doubleTapSeekSeconds,
    )

    private object Keys {
        val qualityCap = stringPreferencesKey("quality_cap")
        val preferH264 = booleanPreferencesKey("prefer_h264")
        val allowAv1 = booleanPreferencesKey("allow_av1")
        val allowVp9 = booleanPreferencesKey("allow_vp9")
        val backgroundAudio = booleanPreferencesKey("background_audio")
        val autoplayNext = booleanPreferencesKey("autoplay_next")
        val dataSaver = booleanPreferencesKey("data_saver")
        val highRefreshRate = booleanPreferencesKey("high_refresh_rate")
        val maxBufferMegabytes = intPreferencesKey("max_buffer_mb")
        val doubleTapSeekSeconds = intPreferencesKey("double_tap_seek_seconds")
    }

}
