// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.github.se.oncompanion.model.session.SessionAccess

/** Schedules exact, idle-aware alarms; Android owns them even after the app process exits. */
class AlarmReminderScheduler(context: Context) : ReminderScheduler {
  private val context = context.applicationContext
  private val alarms = this.context.getSystemService(AlarmManager::class.java)
  private val notifications = ReminderNotifications(this.context)
  private val deliveries = ReminderDeliveries(this.context)

  override fun schedule(reminder: Reminder): ReminderScheduleResult = SessionAccess.immediate {
    synchronized(ReminderDeliveries.lock) { scheduleCurrent(reminder) }
  }

  private fun scheduleCurrent(reminder: Reminder): ReminderScheduleResult {
    notifications.createChannel()
    // A refused replacement must not leave an obsolete reminder scheduled.
    cancel(reminder.key)
    if (!notifications.enabled()) return ReminderScheduleResult.NOTIFICATIONS_BLOCKED
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
      return ReminderScheduleResult.EXACT_ALARMS_BLOCKED
    }
    val version = deliveries.replace(reminder.key.uri().toString())
    val intent =
        reminder.key
            .intent(context)
            .putExtra(TITLE, reminder.title)
            .putExtra(TEXT, reminder.text)
            .putExtra(VERSION, version)
    val operation = PendingIntent.getBroadcast(context, 0, intent, FLAGS)
    return try {
      alarms.setExactAndAllowWhileIdle(
          AlarmManager.RTC_WAKEUP,
          reminder.at.toEpochMilli(),
          operation,
      )
      ReminderScheduleResult.SCHEDULED
    } catch (_: SecurityException) {
      // Access can be unavailable despite a preceding capability check.
      deliveries.invalidate(reminder.key.uri().toString())
      operation.cancel()
      ReminderScheduleResult.EXACT_ALARMS_BLOCKED
    }
  }

  override fun cancel(key: ReminderKey) =
      synchronized(ReminderDeliveries.lock) {
        // Invalidate first: an already queued broadcast must not display after cancellation.
        deliveries.invalidate(key.uri().toString())
        PendingIntent.getBroadcast(
                context,
                0,
                key.intent(context),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            ?.let {
              alarms.cancel(it)
              it.cancel()
            }
        notifications.cancel(key.uri().toString())
      }

  internal companion object {
    const val CHANNEL = REMINDER_CHANNEL_ID
    const val TITLE = "reminder_title"
    const val TEXT = "reminder_text"
    const val VERSION = "reminder_version"
    const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  }
}

// Full URI identity avoids hash-code collisions and isolates accounts, features and items.
internal fun ReminderKey.uri(): Uri =
    Uri.Builder()
        .scheme("oncompanion-reminder")
        .authority("local")
        .appendPath(ownerUid)
        .appendPath(feature)
        .appendPath(itemId)
        .build()

internal fun ReminderKey.intent(context: Context): Intent =
    Intent(context, ReminderReceiver::class.java).setData(uri())
