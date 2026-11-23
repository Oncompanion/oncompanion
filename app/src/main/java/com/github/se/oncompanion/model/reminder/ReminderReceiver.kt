// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.reminder

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.session.SessionAccess

/** Delivers only this account's reminders; permission loss and incomplete sign-out fail closed. */
class ReminderReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val uri = intent.data ?: return
    if (uri.scheme != "oncompanion-reminder" || uri.authority != "local") return
    val parts = uri.pathSegments
    if (parts.size != 3) return
    val title = intent.getStringExtra(AlarmReminderScheduler.TITLE) ?: return
    val text = intent.getStringExtra(AlarmReminderScheduler.TEXT) ?: return
    try {
      SessionAccess.immediate {
        if (AuthRepositoryProvider.repository.currentUser?.uid != parts[0]) return@immediate
        val notifications = ReminderNotifications(context)
        notifications.createChannel()
        notifications.show(uri.toString(), title, text)
      }
    } catch (_: IllegalStateException) {
      // Session cleanup or unavailable authentication must not reveal appointment content.
    } catch (_: SecurityException) {
      // Android can revoke notification access between the check and notify.
    }
  }
}

/** Shared channel and posting boundary, independent of the alarm implementation. */
internal class ReminderNotifications(private val context: Context) {
  private val manager = context.getSystemService(NotificationManager::class.java)

  fun createChannel() {
    // Recreating a channel preserves the user's existing importance and sound settings.
    manager.createNotificationChannel(
        NotificationChannel(
            AlarmReminderScheduler.CHANNEL,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
    )
  }

  fun enabled(): Boolean =
      NotificationManagerCompat.from(context).areNotificationsEnabled() &&
          manager.getNotificationChannel(AlarmReminderScheduler.CHANNEL)?.importance !=
              NotificationManager.IMPORTANCE_NONE

  fun cancel(tag: String) {
    manager.cancel(tag, 0)
  }

  // enabled checks both app permission and channel access immediately before notify.
  @SuppressLint("MissingPermission")
  fun show(tag: String, title: String, text: String) {
    if (!enabled()) return
    val notification =
        NotificationCompat.Builder(context, AlarmReminderScheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()
    manager.notify(tag, 0, notification)
  }
}
