// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.domain.reminder

import com.github.se.oncompanion.model.appointment.*
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.reminder.*
import com.github.se.oncompanion.model.session.SessionAccess
import com.github.se.oncompanion.model.user.*
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.*
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavior from #489/#453: saved choices, snapshot reconciliation and isolated patient accounts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReconcileAppointmentRemindersTest {
  private val now = Instant.parse("2030-01-01T12:00:00Z")
  private val clock = Clock.fixed(now, ZoneOffset.UTC)
  private val text =
      object : AppointmentReminderText {
        override fun format(appointment: Appointment, zoneId: ZoneId) =
            ReminderText(
                appointment.title,
                "${appointment.scheduledAt} ${appointment.location.orEmpty()}",
            )
      }

  private class Fixture {
    val auth = FakeAuthRepository { AuthUser(it) }
    val appointments = FakeAppointmentRepository()
    val profiles = FakeUserProfileRepository()
    val scheduler = FakeReminderScheduler()
    val plans = FakeAppointmentReminderPlans()
  }

  private suspend fun fixture() =
      Fixture().apply {
        auth.signInWithGoogle("patient")
        profiles.seed(UserProfile("patient", Role.PATIENT, "Alex"))
      }

  private fun Fixture.coordinator(
      receipts: AppointmentReminderPlans = plans,
      account: AuthRepository = auth,
      source: AppointmentRepository = appointments,
  ) =
      ReconcileAppointmentReminders(
          text,
          source,
          account,
          profiles,
          scheduler,
          receipts,
          clock,
          ZoneOffset.UTC,
      )

  private fun appointment(
      id: String = "visit",
      at: Instant = now.plusSeconds(172800),
      choice: AppointmentReminder = AppointmentReminder.ONE_HOUR,
  ) =
      Appointment(
          id,
          "Consultation",
          at,
          AppointmentType.CONSULTATION,
          location = "Fictional clinic",
          reminder = choice,
      )

  private fun TestScope.watch(
      f: Fixture,
      states: MutableList<AppointmentReminderSync> = mutableListOf(),
      receipts: AppointmentReminderPlans = f.plans,
      account: AuthRepository = f.auth,
      source: AppointmentRepository = f.appointments,
  ): kotlinx.coroutines.Job {
    val job = backgroundScope.launch {
      f.coordinator(receipts, account, source).observe().collect { states += it }
    }
    runCurrent()
    return job
  }

  private fun appointmentKey(id: String = "visit") = ReminderKey("patient", "appointment", id)

  private fun TestScope.change(f: Fixture, appointment: Appointment) {
    f.appointments.seed("patient", appointment)
    runCurrent()
  }

  private fun TestScope.stop(job: kotlinx.coroutines.Job) {
    job.cancel()
    runCurrent()
  }

  @Before fun setUp() = SessionAccess.reopen()

  @After fun tearDown() = SessionAccess.reopen()

  @Test
  fun savedChoicesAndTimeBoundariesDetermineAlarms() = runTest {
    val f = fixture()
    val offsets =
        listOf(
            AppointmentReminder.FIFTEEN_MINUTES to 900L,
            AppointmentReminder.ONE_HOUR to 3600L,
            AppointmentReminder.THREE_HOURS to 10800L,
            AppointmentReminder.ONE_DAY to 86400L,
        )
    f.appointments.seed(
        "patient",
        *offsets.map { appointment(it.first.name, choice = it.first) }.toTypedArray(),
        appointment("none", choice = AppointmentReminder.NONE),
        appointment("past", now.minusSeconds(1)),
        appointment("now", now),
        appointment("late", now.plusSeconds(60)),
        appointment("exact", now.plusSeconds(3600)),
        appointment("next", now.plusSeconds(3601)),
        appointment("bad").copy(title = ""),
    )
    val coordinator = f.coordinator()
    assertTrue("construction must do nothing", f.scheduler.scheduled.isEmpty())
    backgroundScope.launch { coordinator.observe().collect {} }
    runCurrent()
    assertEquals(7, f.scheduler.scheduled.size)
    for ((id, expected) in
        listOf("late" to now, "exact" to now, "next" to now.plusSeconds(1))) assertEquals(
        expected,
        f.scheduler.reminders.getValue(appointmentKey(id)).at,
    )
    for ((choice, seconds) in offsets) {
      val alarm = f.scheduler.reminders.getValue(appointmentKey(choice.name))
      assertEquals(appointment().scheduledAt.minusSeconds(seconds), alarm.at)
      assertEquals("Consultation", alarm.title)
      assertTrue(alarm.text.contains("Fictional clinic"))
    }
  }

  @Test
  fun immediateReminderReplaysOnlyWhenTimingChanges() = runTest {
    val f = fixture()
    val visit = appointment(at = now.plusSeconds(60))
    f.appointments.seed("patient", visit)
    var observer = watch(f)
    assertEquals(now, f.scheduler.scheduled.single().at)
    f.appointments.seed(
        "patient",
        visit.copy(notes = "Changed notes", title = "Updated title", createdAt = now),
    )
    runCurrent()
    assertEquals(1, f.scheduler.scheduled.size)
    stop(observer)
    observer = watch(f)
    assertEquals("receipts survive coordinator recreation", 1, f.scheduler.scheduled.size)
    change(f, visit.copy(scheduledAt = now.plusSeconds(120)))
    assertEquals(2, f.scheduler.scheduled.size)
    assertEquals(now, f.scheduler.scheduled.last().at)
    change(f, visit.copy(reminder = AppointmentReminder.FIFTEEN_MINUTES))
    assertEquals(3, f.scheduler.scheduled.size)
    observer.cancel()
  }

  @Test
  fun contentChangesReplaceAndNoneOrDeletionCancel() = runTest {
    val f = fixture()
    val visit = appointment()
    f.appointments.seed("patient", visit, appointment("other"))
    val event = Reminder(ReminderKey("patient", "event", "visit"), now, "Event", "Event")
    f.scheduler.schedule(event)
    watch(f)
    val baseline = f.scheduler.scheduled.size
    val edited =
        visit.copy(
            title = "Changed appointment",
            location = "Another clinic",
            addedBy = "caregiver",
            addedByName = "Sam",
        )
    change(f, edited)
    assertEquals(baseline + 1, f.scheduler.scheduled.size)
    assertEquals("Changed appointment", f.scheduler.scheduled.last().title)
    change(f, edited.copy(notes = "Private note"))
    assertEquals(baseline + 1, f.scheduler.scheduled.size)
    change(f, edited.copy(reminder = AppointmentReminder.NONE))
    assertFalse(f.scheduler.reminders.containsKey(appointmentKey("visit")))
    assertTrue(f.scheduler.reminders.containsKey(event.key))
    f.appointments.deleteAppointment("patient", "other")
    runCurrent()
    assertEquals(setOf(event.key), f.scheduler.reminders.keys)
    assertTrue(f.plans.read("patient").isEmpty())
  }

  @Test
  fun blockedSchedulingIsNotRememberedAsAccepted_andCanBeRetried() = runTest {
    for (blocked in
        listOf(
            ReminderScheduleResult.NOTIFICATIONS_BLOCKED,
            ReminderScheduleResult.EXACT_ALARMS_BLOCKED,
        )) {
      val f = fixture()
      f.appointments.seed("patient", appointment(at = now.plusSeconds(60)))
      f.scheduler.result = blocked
      val states = mutableListOf<AppointmentReminderSync>()
      val job = watch(f, states)
      assertEquals(AppointmentReminderSync.BLOCKED, states.last())
      assertTrue(f.plans.read("patient").isEmpty())
      stop(job)
      f.scheduler.result = ReminderScheduleResult.SCHEDULED
      val retry = watch(f, states)
      assertEquals(AppointmentReminderSync.SYNCHRONIZED, states.last())
      assertEquals(now, f.scheduler.reminders.values.single().at)
      stop(retry)
    }
  }

  @Test
  fun accountChangesObserveOnlyTheCurrentPatient() = runTest {
    val f = fixture()
    f.appointments.seed("patient", appointment())
    f.appointments.seed("second", appointment("second-visit"))
    f.profiles.seed(UserProfile("second", Role.PATIENT, "Taylor"))
    watch(f)
    f.auth.signInWithGoogle("second")
    runCurrent()
    assertEquals("second", f.scheduler.scheduled.last().key.ownerUid)
    val scheduled = f.scheduler.scheduled.size
    f.auth.signOut()
    runCurrent()
    assertEquals(scheduled, f.scheduler.scheduled.size)
    f.profiles.seed(UserProfile("carer", Role.CAREGIVER, "Sam"))
    for (uid in listOf("carer", "unfinished-profile")) {
      f.appointments.seed(uid, appointment())
      f.auth.signInWithGoogle(uid)
      runCurrent()
      assertEquals(scheduled, f.scheduler.scheduled.size)
    }
  }

  @Test
  fun staleAccountSnapshotOrClosedSessionCannotSchedule() = runTest {
    val f = fixture()
    f.appointments.seed("patient", appointment())
    for (current in listOf(AuthUser("someone-else"), null)) {
      val staleAuth =
          object : AuthRepository by f.auth {
            override val currentUser = current
          }
      val stale = watch(f, account = staleAuth)
      assertTrue(f.scheduler.scheduled.isEmpty())
      stop(stale)
    }
    SessionAccess.block()
    val states = mutableListOf<AppointmentReminderSync>()
    watch(f, states)
    assertEquals(AppointmentReminderSync.FAILED, states.last())
    assertTrue(f.scheduler.scheduled.isEmpty())
  }

  @Test
  fun readFailurePreservesAlarmsAndCanRecover() = runTest {
    val f = fixture()
    f.appointments.seed("patient", appointment())
    val states = mutableListOf<AppointmentReminderSync>()
    val job = watch(f, states)
    f.appointments.failActiveObservations(IllegalStateException("fictional failure"))
    runCurrent()
    assertEquals(AppointmentReminderSync.FAILED, states.last())
    assertEquals(1, f.scheduler.reminders.size)
    assertEquals(1, f.plans.read("patient").size)
    stop(job)
    watch(f, states)
    assertEquals(AppointmentReminderSync.SYNCHRONIZED, states.last())
    assertEquals(1, f.scheduler.scheduled.size)
    change(f, appointment().copy(reminder = AppointmentReminder.THREE_HOURS))
    assertEquals(2, f.scheduler.scheduled.size)
    assertEquals(AppointmentReminderSync.SYNCHRONIZED, states.last())
    f.profiles.getProfileError = IllegalStateException()
    f.auth.signInWithGoogle("second")
    runCurrent()
    assertEquals(AppointmentReminderSync.FAILED, states.last())
    f.profiles.getProfileError = null
    f.auth.signInWithGoogle("patient")
    runCurrent()
    assertEquals(AppointmentReminderSync.SYNCHRONIZED, states.last())
  }

  @Test
  fun corruptReceiptsAreRebuiltAndAppointmentChangesRemainObserved() = runTest {
    val context =
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
    context
        .getSharedPreferences("appointment_reminder_plans", 0)
        .edit()
        .putString("patient", "corrupt")
        .commit()
    val f = fixture()
    f.appointments.seed("patient", appointment())
    val receipts = AppointmentReminderPlansPreferences(context)
    val states = mutableListOf<AppointmentReminderSync>()
    watch(f, states, receipts)
    assertEquals(AppointmentReminderSync.SYNCHRONIZED, states.last())
    assertEquals(1, receipts.read("patient").size)
    assertEquals(1, f.scheduler.scheduled.size)
    change(f, appointment().copy(reminder = AppointmentReminder.NONE))
    assertTrue(receipts.read("patient").isEmpty())
    assertFalse(f.scheduler.reminders.containsKey(appointmentKey()))
  }

  @Test
  fun sameCoordinatorFormatsWithCurrentPhoneZoneUnlessExplicitlyOverridden() = runTest {
    val original = TimeZone.getDefault()
    try {
      for (override in listOf(null, ZoneOffset.UTC)) {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Zurich"))
        val f = fixture()
        f.appointments.seed("patient", appointment())
        val zoneText = AppointmentReminderText { visit, zone ->
          ReminderText(visit.title, visit.scheduledAt.atZone(zone).toString())
        }
        val coordinator =
            ReconcileAppointmentReminders(
                zoneText,
                f.appointments,
                f.auth,
                f.profiles,
                f.scheduler,
                f.plans,
                clock,
                override,
            )
        backgroundScope.launch { coordinator.observe().collect {} }
        runCurrent()
        assertEquals(
            appointment().scheduledAt.atZone(override ?: ZoneId.of("Europe/Zurich")).toString(),
            f.scheduler.reminders.getValue(appointmentKey()).text,
        )
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        // A new snapshot formats again on the same instance; no title or timing change is needed.
        change(f, appointment().copy(notes = "Unrelated edit"))
        assertEquals(
            appointment().scheduledAt.atZone(override ?: ZoneId.of("America/New_York")).toString(),
            f.scheduler.reminders.getValue(appointmentKey()).text,
        )
        assertEquals(if (override == null) 2 else 1, f.scheduler.scheduled.size)
      }
    } finally {
      TimeZone.setDefault(original)
    }
  }

  @Test
  fun durableReceiptsPreventReplayWithoutCopyingContent() = runTest {
    val context =
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
    val f = fixture()
    f.appointments.seed("patient", appointment(at = now.plusSeconds(60)))
    var job = watch(f, receipts = AppointmentReminderPlansPreferences(context))
    assertEquals(1, f.scheduler.scheduled.size)
    stop(job)
    job = watch(f, receipts = AppointmentReminderPlansPreferences(context))
    assertEquals(1, f.scheduler.scheduled.size)
    val stored =
        context.getSharedPreferences("appointment_reminder_plans", 0).getString("patient", "")!!
    assertFalse(stored.contains("Consultation"))
    assertFalse(stored.contains("Fictional clinic"))
    f.appointments.deleteAppointment("patient", "visit")
    runCurrent()
    assertFalse(context.getSharedPreferences("appointment_reminder_plans", 0).contains("patient"))
    job.cancel()
  }

  @Test
  fun blankIdentitiesAreIgnored_andUpstreamCancellationIsNotAnErrorState() = runTest {
    val f = fixture()
    val malformed =
        object : AppointmentRepository by f.appointments {
          override fun observeAppointments(uid: String) =
              flowOf(listOf(appointment().copy(id = "")))
        }
    val job = watch(f, source = malformed)
    assertTrue(f.scheduler.scheduled.isEmpty())
    stop(job)
    val cancelled =
        object : AuthRepository by f.auth {
          override fun observeCurrentUser() =
              flow<AuthUser?> { throw CancellationException("cancelled") }
        }
    val states = mutableListOf<AppointmentReminderSync>()
    val observation = watch(f, states, account = cancelled)
    assertTrue(observation.isCancelled)
    assertTrue(states.isEmpty())
  }

  @Test
  fun authenticationFailureReportsFailure_withoutReadingAppointments() = runTest {
    val f = fixture()
    f.auth.observeError = IllegalStateException()
    val states = mutableListOf<AppointmentReminderSync>()
    watch(f, states)
    assertEquals(listOf(AppointmentReminderSync.FAILED), states)
    assertTrue(f.scheduler.scheduled.isEmpty())
  }
}
