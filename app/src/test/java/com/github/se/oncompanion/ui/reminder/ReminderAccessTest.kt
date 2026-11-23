// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.reminder

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** Android access checks distinguish runtime, app, channel and exact-alarm settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 32, 33, 34])
class ReminderAccessTest {
  private val context: Application = ApplicationProvider.getApplicationContext()
  private val manager = context.getSystemService(NotificationManager::class.java)

  @Before
  fun setUp() {
    shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    context.getSharedPreferences(HISTORY, Context.MODE_PRIVATE).edit().clear().commit()
  }

  @Test
  fun allowedAccessAndBlockedChannelHaveDifferentStates() {
    assertEquals(ReminderAccess.READY, reminderAccess(context))
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL, "Care reminders", NotificationManager.IMPORTANCE_NONE)
    )
    assertEquals(ReminderAccess.CHANNEL_BLOCKED, reminderAccess(context))
  }

  @Test
  fun blockedAppOpensItsOwnAndroidNotificationSettings() {
    shadowOf(manager).setNotificationsEnabled(false)
    context
        .getSharedPreferences(HISTORY, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(REQUESTED, true)
        .commit()
    assertEquals(ReminderAccess.APP_BLOCKED, reminderAccess(context))
    openReminderSettings(context, ReminderAccess.APP_BLOCKED)
    val intent = shadowOf(context).nextStartedActivity
    assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
    assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
  }

  @Test
  fun channelSettingsTargetOnlyCareReminders() {
    openReminderSettings(context, ReminderAccess.CHANNEL_BLOCKED)
    val intent = shadowOf(context).nextStartedActivity
    assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
    assertEquals(CHANNEL, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
  }

  @Test
  @Config(sdk = [33, 34])
  fun firstRuntimeRequestIsOfferedButRefusalLeadsToSettings() {
    shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    assertEquals(ReminderAccess.OFFER, reminderAccess(context))
    context
        .getSharedPreferences(HISTORY, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(REQUESTED, true)
        .commit()
    assertEquals(ReminderAccess.APP_BLOCKED, reminderAccess(context))
    shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    assertEquals(ReminderAccess.READY, reminderAccess(context))
  }

  @Test
  @Config(sdk = [32, 33, 34])
  fun exactAccessHasASeparateSettingsDestination() {
    ShadowAlarmManager.setCanScheduleExactAlarms(false)
    assertEquals(ReminderAccess.EXACT_BLOCKED, reminderAccess(context))
    openReminderSettings(context, ReminderAccess.EXACT_BLOCKED)
    val intent = shadowOf(context).nextStartedActivity
    assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent.action)
    assertEquals("package:${context.packageName}", intent.dataString)
  }

  @Test
  fun unavailableSpecificSettingsFallsBackToAppDetails() {
    val started = mutableListOf<android.content.Intent>()
    val wrapper =
        object : android.content.ContextWrapper(context) {
          override fun startActivity(intent: android.content.Intent) {
            started += intent
            if (started.size == 1) throw android.content.ActivityNotFoundException()
          }
        }
    openReminderSettings(wrapper, ReminderAccess.APP_BLOCKED)
    assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.last().action)
    assertEquals("package:${context.packageName}", started.last().dataString)
  }
}
