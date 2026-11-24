// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.reminder

import com.github.se.oncompanion.model.appointment.*
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.user.*
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.io.IOException
import java.time.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Eligibility follows the whole patient account, never the calendar's selected day. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderPermissionViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val auth = FakeAuthRepository { AuthUser(it) }
  private val profiles = FakeUserProfileRepository()
  private val appointments = FakeAppointmentRepository()
  private val start = Instant.parse("2030-01-01T12:00:00Z")
  private val clock =
      object : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: ZoneId) = this

        override fun instant() = start.plusMillis(dispatcher.scheduler.currentTime)
      }

  private fun visit(
      reminder: AppointmentReminder = AppointmentReminder.ONE_HOUR,
      at: Instant = start.plusSeconds(60),
  ) = Appointment("visit", "Fictional visit", at, AppointmentType.OTHER, reminder = reminder)

  private fun model() = ReminderPermissionViewModel(appointments, auth, profiles, clock)

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    runTest(dispatcher) {
      profiles.createProfile(UserProfile("patient", Role.PATIENT, "Test"))
      auth.signInWithGoogle("patient")
    }
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun onlyFutureAppointmentsWithAnEnabledChoiceQualify() =
      runTest(dispatcher) {
        val vm = model()
        for ((entry, expected) in
            listOf(
                visit(at = start.minusSeconds(1)) to false,
                visit(at = start) to false,
                visit(AppointmentReminder.NONE) to false,
                visit(AppointmentReminder.FIFTEEN_MINUTES, start.plusSeconds(90 * 86400)) to true,
                visit().copy(addedBy = "caregiver", addedByName = "Sam") to true,
            )) {
          appointments.seed("patient", entry)
          runCurrent()
          assertEquals(expected, vm.uiState.value.eligible)
        }
        appointments.deleteAppointment("patient", "visit")
        runCurrent()
        assertFalse(vm.uiState.value.eligible)
      }

  @Test
  fun pastDeadlineHidesNoticeWithoutUserInteraction() =
      runTest(dispatcher) {
        appointments.seed("patient", visit())
        val vm = model()
        runCurrent()
        assertTrue(vm.uiState.value.eligible)
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(vm.uiState.value.eligible)
      }

  @Test
  fun accountChangeAndCaregiverRoleNeverKeepPreviousPatientsInvitation() =
      runTest(dispatcher) {
        appointments.seed("patient", visit())
        val vm = model()
        runCurrent()
        assertTrue(vm.uiState.value.eligible)
        auth.signOut()
        vm.refresh(ReminderAccess.APP_BLOCKED)
        assertFalse(vm.uiState.value.eligible)
        profiles.createProfile(UserProfile("caregiver", Role.CAREGIVER, "Sam"))
        appointments.seed("caregiver", visit())
        auth.signInWithGoogle("caregiver")
        runCurrent()
        assertFalse(vm.uiState.value.eligible)
        auth.signOut()
        runCurrent()
        assertFalse(vm.uiState.value.eligible)
      }

  @Test
  fun returningFromSettingsUpdatesAccessWithoutSavingOrChangingAppointments() =
      runTest(dispatcher) {
        val entry = visit()
        appointments.seed("patient", entry)
        val vm = model()
        runCurrent()
        for (access in ReminderAccess.entries) {
          vm.refresh(access)
          assertEquals(access, vm.uiState.value.access)
          assertEquals(listOf(entry), appointments.appointments("patient"))
        }
      }

  @Test
  fun failedReadsHideInvitationAndResumeRetries() =
      runTest(dispatcher) {
        appointments.observeError = IOException()
        appointments.seed("patient", visit())
        val vm = model()
        runCurrent()
        assertFalse(vm.uiState.value.eligible)
        appointments.observeError = null
        vm.refresh(ReminderAccess.OFFER)
        runCurrent()
        assertTrue(vm.uiState.value.eligible)
      }
}
