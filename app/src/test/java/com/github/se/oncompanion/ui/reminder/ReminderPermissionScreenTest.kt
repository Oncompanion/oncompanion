// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.reminder

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.se.oncompanion.model.appointment.*
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.user.*
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.SMALL_LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.io.File
import java.time.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** The app owns only the invitation; permission dialogs/settings remain Android-owned. */
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class ReminderPermissionScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val context: Application = ApplicationProvider.getApplicationContext()
  private val now = Instant.parse("2030-01-01T12:00:00Z")

  @Composable
  private fun Content(access: ReminderAccess?, eligible: Boolean = true, onTap: () -> Unit = {}) {
    OncompanionTheme {
      Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ReminderPermissionContent(ReminderPermissionUiState(eligible, access), onTap)
      }
    }
  }

  @Test
  fun statesUseClearWordingAndOnlyExplicitTapsInvokeAnAction() {
    var access by mutableStateOf<ReminderAccess?>(null)
    var taps = 0
    compose.setContent { Content(access, onTap = { taps++ }) }
    compose.onNodeWithTag(C.Tag.reminder_permission_notice).assertDoesNotExist()
    for ((value, text) in
        listOf(
            ReminderAccess.OFFER to "Enable notifications for your appointment reminders.",
            ReminderAccess.APP_BLOCKED to
                "Notifications are blocked for oncompanion. You won't receive appointment reminders.",
            ReminderAccess.CHANNEL_BLOCKED to
                "Care reminders are blocked in Android settings. You won't receive appointment reminders.",
            ReminderAccess.EXACT_BLOCKED to
                "Allow alarms and reminders in Android settings so appointment reminders can arrive at the chosen time.",
        )) {
      compose.runOnIdle { access = value }
      compose.onNodeWithText(text).assertIsDisplayed()
      val before = taps
      compose.onNodeWithTag(C.Tag.reminder_permission_action).performClick()
      assertEquals(before + 1, taps)
    }
    assertEquals(4, taps)
    compose.runOnIdle { access = ReminderAccess.READY }
    compose.onNodeWithTag(C.Tag.reminder_permission_notice).assertDoesNotExist()
  }

  @Test
  fun noEligibleAppointmentsMeansNoInvitation() {
    compose.setContent { Content(ReminderAccess.OFFER, eligible = false) }
    compose.onNodeWithTag(C.Tag.reminder_permission_action).assertDoesNotExist()
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun landscapeActionsRemainFullyReachable() = checkLargeTextLayout("landscape")

  @Test
  @Config(qualifiers = SMALL_LANDSCAPE_PHONE)
  fun smallLandscapeActionsRemainFullyReachable() = checkLargeTextLayout("small-landscape")

  @Test fun portraitActionsRemainFullyReachable() = checkLargeTextLayout("portrait")

  private fun checkLargeTextLayout(name: String) {
    var access by mutableStateOf(ReminderAccess.OFFER)
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
        Content(access)
      }
    }
    for (value in
        listOf(
            ReminderAccess.OFFER,
            ReminderAccess.APP_BLOCKED,
            ReminderAccess.CHANNEL_BLOCKED,
            ReminderAccess.EXACT_BLOCKED,
        )) {
      compose.runOnIdle { access = value }
      compose
          .onNodeWithTag(C.Tag.reminder_permission_action)
          .performScrollTo()
          .assertIsFullyVisible()
      val file = File("build/outputs/screenshots/reminder-permissions/$name-${value.name}.png")
      file.parentFile.mkdirs()
      file.outputStream().use {
        compose
            .onRoot()
            .captureToImage()
            .asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
      }
    }
  }

  @Test
  fun planningKeepsItsAddActionAvailableBesideTheInvitation() = planningLayout("planning-portrait")

  @Test
  @Config(qualifiers = SMALL_LANDSCAPE_PHONE)
  fun planningNoticeScrollsOnSmallLandscapeWithoutCoveringAdd() =
      planningLayout("planning-landscape")

  private fun planningLayout(name: String) {
    val day = LocalDate.of(2030, 1, 1)
    var access by mutableStateOf(ReminderAccess.OFFER)
    compose.setContent {
      OncompanionTheme {
        com.github.se.oncompanion.ui.planning.PlanningContent(
            com.github.se.oncompanion.ui.planning.PlanningUiState(
                day,
                com.github.se.oncompanion.ui.planning.PlanningPeriod.of(
                    com.github.se.oncompanion.ui.planning.PlanningView.LIST,
                    day,
                ),
                isLoading = false,
                scheduledItems =
                    listOf(
                        com.github.se.oncompanion.model.planning.PlanningItem(
                            com.github.se.oncompanion.model.planning.PlanningSource.Appointment(
                                "a"
                            ),
                            com.github.se.oncompanion.model.planning.PlanningTiming.Timed(
                                now.plusSeconds(3600)
                            ),
                            "Follow-up appointment",
                            "Lakeside clinic, Room 2",
                        )
                    ),
            ),
            day,
            ZoneOffset.UTC,
            com.github.se.oncompanion.ui.planning.PlanningActions(
                {},
                {},
                {},
                {},
                {},
                onAddAppointment = {},
            ),
            reminderNotice = {
              ReminderPermissionContent(ReminderPermissionUiState(true, access), {})
            },
        )
      }
    }
    for (value in listOf(ReminderAccess.OFFER, ReminderAccess.APP_BLOCKED)) {
      compose.runOnIdle { access = value }
      compose
          .onNodeWithTag(C.Tag.reminder_permission_action)
          .performScrollTo()
          .assertIsFullyVisible()
      compose.onNodeWithTag(C.Tag.planning_add).assertIsFullyVisible()
      val file = File("build/outputs/screenshots/reminder-permissions/$name-${value.name}.png")
      file.parentFile.mkdirs()
      file.outputStream().use {
        compose
            .onRoot()
            .captureToImage()
            .asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
      }
    }
  }

  private fun model(): ReminderPermissionViewModel {
    val auth = FakeAuthRepository { AuthUser(it) }
    val profiles = FakeUserProfileRepository()
    val appointments = FakeAppointmentRepository()
    runTest {
      auth.signInWithGoogle("patient")
      profiles.createProfile(UserProfile("patient", Role.PATIENT, "Test"))
      appointments.seed(
          "patient",
          Appointment("a", "Fictional appointment", now.plusSeconds(3600), AppointmentType.OTHER),
      )
    }
    return ReminderPermissionViewModel(
        appointments,
        auth,
        profiles,
        Clock.fixed(now, ZoneOffset.UTC),
    )
  }

  private fun show(vm: ReminderPermissionViewModel) {
    compose.activity.setContent {
      OncompanionTheme { rememberReminderPermissionNotice(vm)?.invoke() }
    }
  }

  @Test
  @Config(sdk = [33])
  fun refusalAndRecreationDoNotAutomaticallyAskAgain() {
    shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    val vm = model()
    show(vm)
    compose.onNodeWithTag(C.Tag.reminder_permission_action).assertTextEquals("Enable")
    assertNull(shadowOf(compose.activity).lastRequestedPermission)
    compose.onNodeWithTag(C.Tag.reminder_permission_action).performClick()
    val request = shadowOf(compose.activity).lastRequestedPermission
    assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions)
    compose.runOnUiThread {
      compose.activity.onRequestPermissionsResult(
          request.requestCode,
          request.requestedPermissions,
          intArrayOf(PackageManager.PERMISSION_DENIED),
      )
    }
    compose
        .onNodeWithTag(C.Tag.reminder_permission_action)
        .assertTextEquals("Open Android settings")
    compose.activityRule.scenario.recreate()
    show(vm)
    compose
        .onNodeWithTag(C.Tag.reminder_permission_action)
        .assertTextEquals("Open Android settings")
    assertNull(shadowOf(compose.activity).lastRequestedPermission)
    assertTrue(vm.uiState.value.eligible)
  }

  @Test
  @Config(sdk = [33])
  fun returningWithBothPermissionsGrantedRemovesTheNotice() {
    shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    ShadowAlarmManager.setCanScheduleExactAlarms(false)
    val vm = model()
    show(vm)
    compose.onNodeWithTag(C.Tag.reminder_permission_action).assertIsDisplayed()
    compose.runOnUiThread { ShadowAlarmManager.setCanScheduleExactAlarms(true) }
    compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
    compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
    compose.onNodeWithTag(C.Tag.reminder_permission_notice).assertDoesNotExist()
    assertEquals(ReminderAccess.READY, vm.uiState.value.access)
  }
}
