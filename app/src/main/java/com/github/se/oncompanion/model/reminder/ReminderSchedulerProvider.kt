// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.reminder

import android.content.Context
import androidx.annotation.VisibleForTesting

/** Installs the application-scoped scheduler without scheduling or requesting permission. */
object ReminderSchedulerProvider {
  @Volatile private var current: ReminderScheduler? = null

  /** The installed device service; tests may replace it with a fake. */
  var scheduler: ReminderScheduler
    get() = checkNotNull(current) { "ReminderSchedulerProvider.install() wasn't called" }
    set(value) {
      current = value
    }

  /** Keeps a previously installed service or fake across Activity recreation. */
  @Synchronized
  fun install(context: Context) {
    if (current == null) current = AlarmReminderScheduler(context)
  }

  /** Restores the uninstalled state between tests. */
  @VisibleForTesting
  internal fun reset() {
    current = null
  }
}
