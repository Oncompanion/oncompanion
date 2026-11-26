// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.reminder

import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.github.se.oncompanion.domain.auth.SignOut
import com.github.se.oncompanion.domain.reminder.ReconcileAppointmentReminders
import com.github.se.oncompanion.model.appointment.*
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.reminder.*
import com.github.se.oncompanion.model.session.*
import com.github.se.oncompanion.model.user.*
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Integration policy: cached appointments, settings retry and account-bound one-shot taps. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppointmentRemindersViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val auth = FakeAuthRepository { AuthUser(it) }
  private val profiles = FakeUserProfileRepository()
  private val appointments = FakeAppointmentRepository()
  private val alarms = FakeReminderScheduler()
  private val receipts = FakeAppointmentReminderPlans()
  private val store = ViewModelStore()
  private val now = Instant.parse("2030-01-01T12:00:00Z")
  private val key = ReminderKey("patient", "appointment", "visit")
  private var tick = now
  private val clock =
      object : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: ZoneId) = this

        override fun instant() = tick
      }
  private val signOutScope = CoroutineScope(SupervisorJob() + dispatcher)
  private val local = FakeLocalSessionRepository()
  private lateinit var signOut: SignOut

  @Before
  fun before() =
      runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        SessionAccess.reopen()
        profiles.seed(UserProfile("patient", Role.PATIENT, "Alex"))
        auth.signInWithGoogle("patient")
        signOut = SignOut(auth, local, signOutScope)
        signOut.initialize(FakeCleanupJournal())
      }

  @After
  fun after() {
    store.clear()
    signOutScope.cancel()
    Dispatchers.resetMain()
    SessionAccess.reopen()
  }

  private fun model(
      saved: SavedStateHandle = SavedStateHandle(),
      account: AuthRepository = auth,
  ): AppointmentRemindersViewModel {
    val text = AppointmentReminderText { appointment, _ ->
      ReminderText(appointment.title, "Fictional date")
    }
    val coordinator =
        ReconcileAppointmentReminders(
            text,
            appointments,
            account,
            profiles,
            alarms,
            receipts,
            clock,
        )
    return AppointmentRemindersViewModel(saved, coordinator, account, profiles, signOut).also {
      store.put("reminders", it)
    }
  }

  private suspend fun seed() {
    appointments.seed(
        "patient",
        Appointment("visit", "Fictional visit", now.plusSeconds(7200), AppointmentType.OTHER),
    )
  }

  @Test
  fun settingsRetry_restoresLostAlarm_butNeverReplaysAConsumedImmediateReminder() =
      runTest(dispatcher) {
        seed()
        alarms.result = ReminderScheduleResult.EXACT_ALARMS_BLOCKED
        val vm = model()
        runCurrent()
        assertTrue(receipts.read("patient").isEmpty())
        alarms.result = ReminderScheduleResult.SCHEDULED
        vm.refresh()
        runCurrent()
        assertEquals(1, alarms.reminders.size)
        // Android removed an accepted exact alarm while access was off; its due time passes.
        alarms.reminders.clear()
        tick = now.plusSeconds(4000)
        vm.refresh()
        runCurrent()
        assertEquals(tick, alarms.reminders[key]?.at)
        alarms.delivered += key
        val count = alarms.scheduled.size
        repeat(3) {
          vm.refresh()
          runCurrent()
        }
        assertEquals(count, alarms.scheduled.size)
      }

  @Test
  fun tap_waitsForSignInAndProfile_thenConsumesOnce_andRejectsOtherAccountsAndCaregivers() =
      runTest(dispatcher) {
        auth.signOut()
        val vm = model()
        vm.open("patient", "visit")
        runCurrent()
        assertNull(vm.uiState.value.appointmentId)
        auth.signInWithGoogle("patient")
        runCurrent()
        assertEquals("visit", vm.uiState.value.appointmentId)
        assertTrue(vm.consume())
        assertFalse(vm.consume())
        for (uid in listOf("other", "caregiver")) {
          profiles.seed(UserProfile(uid, Role.CAREGIVER, "Sam"))
          auth.signInWithGoogle(uid)
          vm.open("patient", "visit")
          runCurrent()
          assertNull(vm.uiState.value.appointmentId)
          assertFalse(vm.consume())
        }
        vm.open("caregiver", "visit")
        runCurrent()
        assertNull(vm.uiState.value.appointmentId)
      }

  @Test
  fun staleTap_isRejectedAtConsumption_andCleanupClearsSavedNavigation() =
      runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = model(saved)
        vm.open("patient", "visit")
        runCurrent()
        auth.signInWithGoogle("other") // Don't dispatch the observation yet.
        assertFalse(vm.consume())
        auth.signInWithGoogle("patient")
        vm.open("patient", "visit")
        runCurrent()
        SessionAccess.block()
        assertFalse(vm.consume())
        SessionAccess.reopen()
        vm.open("patient", "visit")
        runCurrent()
        signOut.begin {}
        runCurrent()
        assertNull(vm.uiState.value.appointmentId)
        assertNull(saved.get<List<String>>("appointment_reminder_request"))
      }

  @Test
  fun accountLostBeforeItsCallback_cannotResolveOrConsumeTheOldTap() =
      runTest(dispatcher) {
        var current: AuthUser? = AuthUser("patient")
        val account =
            object : AuthRepository by auth {
              override val currentUser
                get() = current
            }
        val vm = model(account = account)
        for (next in listOf(null, AuthUser("other"))) {
          current = next
          vm.open("patient", "visit")
          vm.refresh()
          runCurrent()
          assertNull(vm.uiState.value.appointmentId)
          assertFalse(vm.consume())
          runCurrent()
        }
        current = AuthUser("patient")
        vm.open("patient", "visit")
        runCurrent()
        assertEquals("visit", vm.uiState.value.appointmentId)
        current = null // No observable callback before navigation consumes the request.
        assertFalse(vm.consume())
      }

  @Test
  fun savedRequest_survivesRecreation_butHandledRequestDoesNot() =
      runTest(dispatcher) {
        val saved = SavedStateHandle()
        model(saved).open("patient", "visit")
        runCurrent()
        val restored =
            SavedStateHandle(
                mapOf(
                    "appointment_reminder_request" to
                        saved.get<List<String>>("appointment_reminder_request")
                )
            )
        store.clear()
        val vm = model(restored)
        runCurrent()
        assertEquals("visit", vm.uiState.value.appointmentId)
        assertTrue(vm.consume())
        vm.refresh()
        runCurrent()
        assertNull(vm.uiState.value.appointmentId)
        vm.open("", "visit")
        vm.open("patient", "")
        runCurrent()
        assertNull(vm.uiState.value.appointmentId)
      }

  @Test
  fun resume_retriesFailedReads_withoutInventingADeletionOrNavigationTarget() =
      runTest(dispatcher) {
        profiles.getProfileError = java.io.IOException()
        val vm = model()
        vm.open("patient", "visit")
        runCurrent()
        assertEquals(
            com.github.se.oncompanion.domain.reminder.AppointmentReminderSync.FAILED,
            vm.uiState.value.sync,
        )
        assertNull(vm.uiState.value.appointmentId)
        profiles.getProfileError = null
        vm.refresh()
        runCurrent()
        assertEquals("visit", vm.uiState.value.appointmentId)
        assertTrue(vm.consume())
        assertEquals(
            com.github.se.oncompanion.domain.reminder.AppointmentReminderSync.SYNCHRONIZED,
            vm.uiState.value.sync,
        )
      }

  @Test
  fun tap_waitsForAnUnknownProfile_toBecomeAPatient() =
      runTest(dispatcher) {
        auth.signInWithGoogle("new-patient")
        val vm = model()
        vm.open("new-patient", "visit")
        runCurrent()
        assertNull(vm.uiState.value.appointmentId)
        assertFalse(vm.consume())
        vm.open("new-patient", "visit")
        runCurrent()
        profiles.seed(UserProfile("new-patient", Role.PATIENT, "Alex"))
        runCurrent()
        assertEquals("visit", vm.uiState.value.appointmentId)
        assertTrue(vm.consume())
      }

  @Test
  fun intent_acceptsOnlyAppointmentKeys_andPreservesEncodedIdentifiers() {
    val cases =
        listOf(
            "oncompanion-reminder://local/patient/appointment/visit%2Fone" to
                ("patient" to "visit/one"),
            "oncompanion-reminder://local/patient/medication/visit" to null,
            "https://local/patient/appointment/visit" to null,
            "oncompanion-reminder://other/patient/appointment/visit" to null,
            "oncompanion-reminder://local/patient/appointment" to null,
            "oncompanion-reminder://local/%20/appointment/visit" to null,
        )
    for ((uri, expected) in cases) assertEquals(
        expected,
        Intent(Intent.ACTION_VIEW, uri.toUri()).appointmentReminder(),
    )
    assertNull(Intent().appointmentReminder())
    assertNull(Intent(Intent.ACTION_MAIN, cases.first().first.toUri()).appointmentReminder())
  }
}
