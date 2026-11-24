// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.session.PreferencesCleanupJournal
import com.github.se.oncompanion.model.session.SessionAccess
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Instant
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the device contract through Android's alarm, intent and notification services. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 32, 33, 34])
class AlarmReminderSchedulerTest {
  private val context: Application = ApplicationProvider.getApplicationContext()
  private val alarms = context.getSystemService(AlarmManager::class.java)
  private val notifications = context.getSystemService(NotificationManager::class.java)
  private val scheduler = AlarmReminderScheduler(context)
  private val originalAuth = AuthRepositoryProvider.repository
  private val key = ReminderKey("patient", "appointment", "visit")
  private val reminder =
      Reminder(
          key,
          Instant.parse("2030-01-01T13:00:00Z"),
          "Consultation",
          "1 January, 14:00 · Test clinic",
      )

  private fun signedIn(uid: String) = FakeAuthRepository {
    AuthUser(uid)
  }
      .also { kotlinx.coroutines.runBlocking { it.signInWithGoogle("fictional-test-token") } }

  @Before
  fun setUp() {
    shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
    SessionAccess.reopen()
    AuthRepositoryProvider.repository = signedIn("patient")
  }

  @After
  fun tearDown() {
    AuthRepositoryProvider.repository = originalAuth
    SessionAccess.reopen()
    ReminderSchedulerProvider.reset()
  }

  @Test
  fun replacingSameKeyKeepsOneExactIdleAwareAlarmWithLatestContent() {
    assertEquals(ReminderScheduleResult.SCHEDULED, scheduler.schedule(reminder))
    val later = reminder.copy(at = reminder.at.plusSeconds(600), title = "Changed title")
    scheduler.schedule(later)
    val alarm = shadowOf(alarms).scheduledAlarms.single()
    assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
    assertEquals(later.at.toEpochMilli(), alarm.triggerAtTime)
    assertTrue(alarm.allowWhileIdle)
    assertEquals(
        "Changed title",
        shadowOf(alarm.operation).savedIntent.getStringExtra(AlarmReminderScheduler.TITLE),
    )
    alarm.operation!!.send()
    org.robolectric.shadows.ShadowLooper.idleMainLooper()
    val notification = notifications.activeNotifications.single().notification
    assertEquals("Changed title", notification.extras.getString(Notification.EXTRA_TITLE))
    assertEquals(reminder.text, notification.extras.getString(Notification.EXTRA_TEXT))
    assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
  }

  @Test
  fun fullIdentitySeparatesAccountsFeaturesAndHashCollidingIds() {
    val keys =
        listOf(
            key,
            key.copy(ownerUid = "other"),
            key.copy(feature = "event"),
            key.copy(itemId = "Aa"),
            key.copy(itemId = "BB"),
            key.copy(itemId = "a/b"),
        )
    keys.forEach { scheduler.schedule(reminder.copy(key = it)) }
    assertEquals(keys.size, shadowOf(alarms).scheduledAlarms.size)
    scheduler.cancel(key)
    scheduler.cancel(key)
    assertEquals(keys.size - 1, shadowOf(alarms).scheduledAlarms.size)
  }

  @Test
  fun cancellationPreventsDeliveryAndRemovesDisplayedNotification() {
    scheduler.schedule(reminder)
    shadowOf(alarms).scheduledAlarms.single().operation!!.send()
    org.robolectric.shadows.ShadowLooper.idleMainLooper()
    assertEquals(1, notifications.activeNotifications.size)
    scheduler.cancel(key)
    scheduler.cancel(key)
    assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    assertTrue(notifications.activeNotifications.isEmpty())
  }

  @Test
  fun queuedDeliveryAfterCancellationOrReplacementNeverShowsOldContent() {
    for (replacement in listOf(false, true)) {
      scheduler.schedule(reminder)
      // Copy the delivery already handed to Android, independent of the cancelled PendingIntent.
      val queued = Intent(shadowOf(shadowOf(alarms).scheduledAlarms.single().operation).savedIntent)
      val freshScheduler = AlarmReminderScheduler(context)
      if (replacement) freshScheduler.schedule(reminder.copy(title = "Updated appointment"))
      else freshScheduler.cancel(key)

      ReminderReceiver().onReceive(context, queued)
      assertTrue(
          "obsolete delivery after replacement=$replacement",
          notifications.activeNotifications.isEmpty(),
      )
      if (replacement) {
        val current =
            Intent(shadowOf(shadowOf(alarms).scheduledAlarms.single().operation).savedIntent)
        ReminderReceiver().onReceive(context, current)
        assertEquals(
            "Updated appointment",
            notifications.activeNotifications
                .single()
                .notification
                .extras
                .getString(Notification.EXTRA_TITLE),
        )
        // A duplicate queued delivery must not post again after the current one was consumed.
        notifications.cancelAll()
        ReminderReceiver().onReceive(context, current)
        assertTrue(notifications.activeNotifications.isEmpty())
      }
      freshScheduler.cancel(key)
    }
  }

  @Test
  fun persistedCleanupBlocksColdDeliveryWithoutActivityEvenIfFirebaseRemembersAccount() {
    scheduler.schedule(reminder)
    val queued = Intent(shadowOf(shadowOf(alarms).scheduledAlarms.single().operation).savedIntent)
    val journal = PreferencesCleanupJournal(context.getSharedPreferences("session_cleanup", 0))
    try {
      journal.setPending(true)
      // A fresh process starts with an open in-memory gate; no Activity restores the disk flag.
      SessionAccess.reopen()
      assertEquals("patient", AuthRepositoryProvider.repository.currentUser?.uid)
      ReminderReceiver().onReceive(context, queued)
      assertTrue(notifications.activeNotifications.isEmpty())
      journal.setPending(false)
      ReminderReceiver().onReceive(context, queued)
      assertEquals(1, notifications.activeNotifications.size)
    } finally {
      journal.setPending(false)
    }
  }

  @Test
  fun channelIsCreatedOnceAndKeepsUsersImportance() {
    scheduler.schedule(reminder)
    val channel = notifications.getNotificationChannel(AlarmReminderScheduler.CHANNEL)
    channel.importance = NotificationManager.IMPORTANCE_LOW
    notifications.createNotificationChannel(channel)
    scheduler.schedule(reminder)
    assertEquals(1, notifications.notificationChannels.size)
    assertEquals(
        NotificationManager.IMPORTANCE_LOW,
        notifications.getNotificationChannel(AlarmReminderScheduler.CHANNEL).importance,
    )
  }

  @Test
  fun blockedNotificationsCancelOldAlarmAndNeverDeliver() {
    scheduler.schedule(reminder)
    val intent = shadowOf(shadowOf(alarms).scheduledAlarms.single().operation).savedIntent
    shadowOf(notifications).setNotificationsEnabled(false)
    assertEquals(ReminderScheduleResult.NOTIFICATIONS_BLOCKED, scheduler.schedule(reminder))
    assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    ReminderReceiver().onReceive(context, intent)
    assertTrue(notifications.activeNotifications.isEmpty())
  }

  @Test
  @Config(sdk = [32, 33, 34])
  fun missingExactAlarmAccessCancelsOldAlarmWithoutFallingBackToLateDelivery() {
    scheduler.schedule(reminder)
    org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
    assertEquals(ReminderScheduleResult.EXACT_ALARMS_BLOCKED, scheduler.schedule(reminder))
    assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
  }

  @Test
  fun disabledChannelIsReportedAsBlocked() {
    scheduler.schedule(reminder)
    val channel = notifications.getNotificationChannel(AlarmReminderScheduler.CHANNEL)
    channel.importance = NotificationManager.IMPORTANCE_NONE
    notifications.createNotificationChannel(channel)
    assertEquals(ReminderScheduleResult.NOTIFICATIONS_BLOCKED, scheduler.schedule(reminder))
  }

  @Test
  fun delayedBroadcastDoesNotRevealContentToAnotherAccountOrAfterSignOut() {
    scheduler.schedule(reminder)
    val intent = shadowOf(shadowOf(alarms).scheduledAlarms.single().operation).savedIntent
    for (user in listOf(AuthUser("other"), null)) {
      AuthRepositoryProvider.repository =
          if (user == null) FakeAuthRepository() else signedIn(user.uid)
      ReminderReceiver().onReceive(context, intent)
      assertTrue(notifications.activeNotifications.isEmpty())
    }
    AuthRepositoryProvider.repository = signedIn("patient")
    SessionAccess.block()
    ReminderReceiver().onReceive(context, intent)
    assertTrue(notifications.activeNotifications.isEmpty())
  }

  @Test
  fun malformedBroadcastsAreIgnored() {
    scheduler.schedule(reminder)
    val valid = Intent(shadowOf(shadowOf(alarms).scheduledAlarms.single().operation).savedIntent)
    val intents =
        listOf(
            Intent(),
            Intent(valid).setData(android.net.Uri.parse("other://local/patient/appointment/visit")),
            Intent(valid)
                .setData(
                    android.net.Uri.parse("oncompanion-reminder://other/patient/appointment/visit")
                ),
            Intent(valid).setData(android.net.Uri.parse("oncompanion-reminder://local/patient")),
            Intent(valid).apply { removeExtra(AlarmReminderScheduler.TITLE) },
            Intent(valid).apply { removeExtra(AlarmReminderScheduler.TEXT) },
            Intent(valid).apply { removeExtra(AlarmReminderScheduler.VERSION) },
            key.intent(context),
            key.intent(context).putExtra(AlarmReminderScheduler.TITLE, "Title"),
        )
    intents.forEach { ReminderReceiver().onReceive(context, it) }
    assertTrue(notifications.activeNotifications.isEmpty())
  }

  @Test
  fun blankIdentityIsRejected() {
    for (invalid in
        listOf(
            Triple("", "appointment", "id"),
            Triple("uid", " ", "id"),
            Triple("uid", "appointment", ""),
        )) {
      assertThrows(IllegalArgumentException::class.java) {
        ReminderKey(invalid.first, invalid.second, invalid.third)
      }
    }
  }

  @Test
  fun providerInstallsOnceWithoutSchedulingAndPreservesInjectedService() {
    ReminderSchedulerProvider.reset()
    assertThrows(IllegalStateException::class.java) { ReminderSchedulerProvider.scheduler }
    ReminderSchedulerProvider.install(context)
    val installed = ReminderSchedulerProvider.scheduler
    ReminderSchedulerProvider.install(context)
    assertSame(installed, ReminderSchedulerProvider.scheduler)
    assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    val fake = FakeReminderScheduler()
    ReminderSchedulerProvider.scheduler = fake
    ReminderSchedulerProvider.install(context)
    assertSame(fake, ReminderSchedulerProvider.scheduler)
  }
}
