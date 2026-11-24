// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.reminder

import android.content.Context
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.appointment.Appointment
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Display text at the Android boundary; the coordinator has no Context or resource access. */
data class ReminderText(val title: String, val text: String)

/** Formats only recorded appointment information, with the phone's display time zone. */
fun interface AppointmentReminderText {
  /** Title, date/time and optional location, without private notes or caregiver attribution. */
  fun format(appointment: Appointment, zoneId: ZoneId): ReminderText
}

/** Uses string resources and the device locale; formatting runs when a plan is reconciled. */
class AndroidAppointmentReminderText(context: Context) : AppointmentReminderText {
  private val context = context.applicationContext

  override fun format(appointment: Appointment, zoneId: ZoneId): ReminderText {
    val formatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(context.resources.configuration.locales[0])
            .withZone(zoneId)
    val dateTime = formatter.format(appointment.scheduledAt)
    val text =
        appointment.location
            ?.takeIf { it.isNotBlank() }
            ?.let { context.getString(R.string.appointment_reminder_with_location, dateTime, it) }
            ?: context.getString(R.string.appointment_reminder_without_location, dateTime)
    return ReminderText(appointment.title, text)
  }
}
