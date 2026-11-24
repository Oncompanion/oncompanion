// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.reminder

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Device-wide OS request history, independent of account preferences and appointment content. */
interface ReminderPermissionHistory {
  /** Whether this installation has already requested Android notification permission. */
  val requested: Boolean

  /** Records an explicit request before launching Android's dialog. */
  fun markRequested()
}

/** Keeps the existing preference key so upgrades retain the installation's request history. */
class PreferencesReminderPermissionHistory(private val preferences: SharedPreferences) :
    ReminderPermissionHistory {
  override val requested: Boolean
    get() = preferences.getBoolean(REQUESTED, false)

  override fun markRequested() {
    preferences.edit { putBoolean(REQUESTED, true) }
  }

  private companion object {
    const val REQUESTED = "notifications_requested"
  }
}

/** Shares one installation's store, preserving an injected fake across Activity recreation. */
object ReminderPermissionHistoryProvider {
  /** An override for tests; null until the first consumer installs the device store. */
  @Volatile var history: ReminderPermissionHistory? = null

  /** Resolves the store using only the application context; sign-out does not reset OS history. */
  @Synchronized
  fun get(context: Context): ReminderPermissionHistory =
      history
          ?: PreferencesReminderPermissionHistory(
                  context.applicationContext.getSharedPreferences(
                      "reminder_permission_history",
                      Context.MODE_PRIVATE,
                  )
              )
              .also { history = it }
}
