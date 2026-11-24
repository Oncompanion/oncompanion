// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.reminder

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.github.se.oncompanion.MainActivity
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.session.PreferencesCleanupJournal
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
    val version = intent.getStringExtra(AlarmReminderScheduler.VERSION) ?: return
    try {
      SessionAccess.immediate {
        synchronized(ReminderDeliveries.lock) {
          // Alarms can cold-start the process without MainActivity restoring the cleanup gate.
          val journal =
              PreferencesCleanupJournal(
                  context.getSharedPreferences("session_cleanup", Context.MODE_PRIVATE)
              )
          if (journal.pending) return@immediate
          val deliveries = ReminderDeliveries(context)
          if (!deliveries.isCurrent(uri.toString(), version)) return@immediate
          if (AuthRepositoryProvider.repository.currentUser?.uid != parts[0]) return@immediate
          val notifications = ReminderNotifications(context)
          notifications.createChannel()
          // Consume only after an accepted post; permission loss must remain retryable.
          if (notifications.show(uri.toString(), title, text)) deliveries.delivered(uri.toString())
        }
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
  fun show(tag: String, title: String, text: String): Boolean {
    if (!enabled()) return false
    val notification =
        NotificationCompat.Builder(context, AlarmReminderScheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(tag.toUri())
                        .addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        ),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
    manager.notify(tag, 0, notification)
    return true
  }
}
