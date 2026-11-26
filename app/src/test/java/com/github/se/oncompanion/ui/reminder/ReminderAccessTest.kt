// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.reminder

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.reminder.FakeReminderPermissionHistory
import com.github.se.oncompanion.model.reminder.PreferencesReminderPermissionHistory
import com.github.se.oncompanion.model.reminder.ReminderPermissionHistoryProvider
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import kotlinx.coroutines.runBlocking
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
  private val history = FakeReminderPermissionHistory()
  private val manager = context.getSystemService(NotificationManager::class.java)

  @Before
  fun setUp() {
    shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
  }

  @Test
  fun allowedAccessAndBlockedChannelHaveDifferentStates() {
    assertEquals(ReminderAccess.READY, reminderAccess(context, history))
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL, "Care reminders", NotificationManager.IMPORTANCE_NONE)
    )
    assertEquals(ReminderAccess.CHANNEL_BLOCKED, reminderAccess(context, history))
  }

  @Test
  fun blockedAppOpensItsOwnAndroidNotificationSettings() {
    shadowOf(manager).setNotificationsEnabled(false)
    history.markRequested()
    assertEquals(ReminderAccess.APP_BLOCKED, reminderAccess(context, history))
    openReminderSettings(context, ReminderAccess.APP_BLOCKED)
    val intent = shadowOf(context).nextStartedActivity
    assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
    assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
  }

  @Test
  @Config(sdk = [33])
  fun persistedHistoryKeepsTheExistingKeyAndSurvivesStoreRecreationAndAccountSwitch() =
      runBlocking {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val preferences = context.getSharedPreferences("reminder_permission_history", 0)
        val auth = FakeAuthRepository { AuthUser(it) }
        auth.signInWithGoogle("patient-a")
        // A flag written by the old implementation must still prevent another dialog after upgrade.
        preferences.edit().putBoolean("notifications_requested", true).commit()
        assertEquals(
            ReminderAccess.APP_BLOCKED,
            reminderAccess(context, PreferencesReminderPermissionHistory(preferences)),
        )
        preferences.edit().clear().commit()
        val stored = PreferencesReminderPermissionHistory(preferences)
        assertEquals(ReminderAccess.OFFER, reminderAccess(context, stored))
        stored.markRequested()
        auth.signOut()
        auth.signInWithGoogle("patient-b")
        val reopened =
            PreferencesReminderPermissionHistory(
                context.getSharedPreferences("reminder_permission_history", 0)
            )
        assertEquals(ReminderAccess.APP_BLOCKED, reminderAccess(context, reopened))
      }

  @Test
  fun providerReusesDeviceHistoryAndPreservesAnInjectedFake() {
    val original = ReminderPermissionHistoryProvider.history
    try {
      ReminderPermissionHistoryProvider.history = null
      val stored = ReminderPermissionHistoryProvider.of(context)
      assertSame(stored, ReminderPermissionHistoryProvider.of(context))
      stored.markRequested()
      ReminderPermissionHistoryProvider.history = null
      assertTrue(ReminderPermissionHistoryProvider.of(context).requested)
      ReminderPermissionHistoryProvider.history = history
      assertSame(history, ReminderPermissionHistoryProvider.of(context))
    } finally {
      ReminderPermissionHistoryProvider.history = original
    }
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
    assertEquals(ReminderAccess.OFFER, reminderAccess(context, history))
    history.markRequested()
    assertEquals(ReminderAccess.APP_BLOCKED, reminderAccess(context, history))
    shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    assertEquals(ReminderAccess.READY, reminderAccess(context, history))
  }

  @Test
  @Config(sdk = [32, 33, 34])
  fun exactAccessHasASeparateSettingsDestination() {
    ShadowAlarmManager.setCanScheduleExactAlarms(false)
    assertEquals(ReminderAccess.EXACT_BLOCKED, reminderAccess(context, history))
    openReminderSettings(context, ReminderAccess.EXACT_BLOCKED)
    val intent = shadowOf(context).nextStartedActivity
    assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent.action)
    assertEquals("package:${context.packageName}", intent.dataString)
  }

  @Test
  @Config(sdk = [28])
  fun unavailableExactAlarmApiUsesAppNotificationSettings() {
    openReminderSettings(context, ReminderAccess.EXACT_BLOCKED)
    assertEquals(
        Settings.ACTION_APP_NOTIFICATION_SETTINGS,
        shadowOf(context).nextStartedActivity.action,
    )
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
