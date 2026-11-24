// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.reminder

import android.content.Context
import java.util.UUID

/**
 * Durable delivery versions, without appointment content; callers hold [lock] until posting ends.
 */
internal class ReminderDeliveries(context: Context) {
  private val preferences =
      context.getSharedPreferences("reminder_deliveries", Context.MODE_PRIVATE)

  /** Persists a fresh version before handing the alarm to Android, surviving process death. */
  fun replace(tag: String): String =
      UUID.randomUUID().toString().also {
        check(preferences.edit().putString(tag, it).commit()) {
          "Could not persist reminder version"
        }
      }

  /** Invalidates queued deliveries before cancelling or replacing an alarm. */
  fun invalidate(tag: String) {
    check(preferences.edit().remove(tag).commit()) { "Could not invalidate reminder version" }
  }

  /** Whether this is the delivery most recently scheduled for this key. */
  fun isCurrent(tag: String, version: String): Boolean = preferences.getString(tag, null) == version

  companion object {
    // Shared by every scheduler/receiver instance: checking and posting cannot straddle
    // cancellation.
    val lock = Any()
  }
}
