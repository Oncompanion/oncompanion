// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.domain.reminder

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.github.se.oncompanion.model.reminder.*

/** Builds the app-scoped coordinator only after device adapters are installed; preserves fakes. */
object AppointmentRemindersProvider {
  private var installed: ReconcileAppointmentReminders? = null
  /** Coordinator used by the Activity's reminder ViewModel. */
  var coordinator: ReconcileAppointmentReminders
    get() = checkNotNull(installed) { "Appointment reminders were not installed" }
    set(value) {
      installed = value
    }

  /** Restores the uninstalled boundary between provider tests. */
  @VisibleForTesting
  internal fun reset() {
    installed = null
  }

  /** Installs the persistent receipt store and formatting boundary once per process. */
  @Synchronized
  fun install(context: Context) {
    AppointmentReminderPlansProvider.install(context)
    if (installed == null)
        installed = ReconcileAppointmentReminders(AndroidAppointmentReminderText(context))
  }
}
