// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Persists preferences in the single application-owned DataStore. Read/write errors propagate. */
class SettingsRepositoryDataStore(storage: Lazy<DataStore<Preferences>>) : SettingsRepository {
  private val store by storage

  override fun observeSettings(): Flow<DeviceSettings> =
      store.data.map { preferences ->
        // Unknown values from another app version retain the safe device default.
        val appearance =
            AppearancePreference.entries.firstOrNull { it.name == preferences[APPEARANCE] }
                ?: AppearancePreference.FOLLOW_DEVICE
        val dateFormat =
            DateFormatPreference.entries.firstOrNull { it.name == preferences[DATE_FORMAT] }
                ?: DateFormatPreference.FOLLOW_DEVICE
        val timeFormat =
            TimeFormatPreference.entries.firstOrNull { it.name == preferences[TIME_FORMAT] }
                ?: TimeFormatPreference.FOLLOW_DEVICE
        DeviceSettings(appearance, dateFormat, timeFormat)
      }

  override suspend fun setAppearance(appearance: AppearancePreference) {
    store.edit { it[APPEARANCE] = appearance.name }
  }

  override suspend fun setDateFormat(dateFormat: DateFormatPreference) {
    store.edit { it[DATE_FORMAT] = dateFormat.name }
  }

  override suspend fun setTimeFormat(timeFormat: TimeFormatPreference) {
    store.edit { it[TIME_FORMAT] = timeFormat.name }
  }

  private companion object {
    val DATE_FORMAT = stringPreferencesKey("date_format")
    val TIME_FORMAT = stringPreferencesKey("time_format")
    val APPEARANCE = stringPreferencesKey("appearance")
  }
}
