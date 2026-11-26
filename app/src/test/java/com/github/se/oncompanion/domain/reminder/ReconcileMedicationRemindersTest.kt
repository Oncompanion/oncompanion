// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.reminder

import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.medication.PrescriptionConfirmation
import com.github.se.oncompanion.model.reminder.FakeMedicationReminderPlans
import com.github.se.oncompanion.model.reminder.FakeReminderScheduler
import com.github.se.oncompanion.model.reminder.Reminder
import com.github.se.oncompanion.model.reminder.ReminderKey
import com.github.se.oncompanion.model.reminder.ReminderScheduleResult
import com.github.se.oncompanion.model.session.SessionAccess
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The medication reminders of #553: which ones ring, and how changes update them (US-32). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReconcileMedicationRemindersTest {

  /** A clock the test moves forward, as the days pass. */
  private class MovingClock(var now: Instant) : Clock() {
    override fun instant() = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId) = this
  }

  private val today = LocalDate.of(2030, 1, 1)
  private val noon = Instant.parse("2030-01-01T12:00:00Z")
  private val clock = MovingClock(noon)
  private val auth = FakeAuthRepository { AuthUser(it) }
  private val medications = FakeMedicationRepository()
  private val scheduler = FakeReminderScheduler()
  private val plans = FakeMedicationReminderPlans()
  private val states = mutableListOf<MedicationReminderSync>()

  private fun medication(
      id: String = "med",
      times: List<String> = listOf("09:00", "18:00"),
      start: LocalDate = today,
      days: Int? = 2,
      dosage: String? = "1 tablet",
      frequency: String? = "Twice a day",
  ) =
      Medication(
          id = id,
          prescriptionId = "rx",
          name = "Ondansetron",
          dosage = dosage,
          frequency = frequency,
          startDate = start,
          durationDays = days,
          reminderTimes = times.map(LocalTime::parse),
      )

  private fun TestScope.save(
      vararg medication: Medication,
      confirmation: PrescriptionConfirmation = PrescriptionConfirmation.CONFIRMED,
  ) {
    medications.seed(
        "patient",
        Prescription(
            "rx",
            prescribedOn = today,
            medications = medication.toList(),
            confirmation = confirmation,
        ),
    )
    runCurrent()
  }

  private fun TestScope.watch(zone: ZoneId = ZoneOffset.UTC): Job {
    val reconcile = ReconcileMedicationReminders(medications, auth, scheduler, plans, clock, zone)
    val job = backgroundScope.launch { reconcile.observe().collect { states += it } }
    runCurrent()
    return job
  }

  private fun TestScope.stop(job: Job) {
    job.cancel()
    runCurrent()
  }

  private fun key(id: String) = ReminderKey("patient", "medication", id)

  /** When the pending alarms ring, earliest first. */
  private fun pending(): List<Instant> = scheduler.reminders.values.map(Reminder::at).sorted()

  private fun at(text: String) = Instant.parse(text)

  @Before
  fun setUp() = runTest {
    SessionAccess.reopen()
    auth.signInWithGoogle("patient")
  }

  @After fun tearDown() = SessionAccess.reopen()

  @Test
  fun eachTime_ringsFromTheStartDate_toTheEndOfTheDuration_withoutCatchingUp() = runTest {
    save(medication())
    watch()
    // 09:00 today has passed and the duration ends tomorrow
    assertEquals(
        listOf(at("2030-01-01T18:00:00Z"), at("2030-01-02T09:00:00Z"), at("2030-01-02T18:00:00Z")),
        pending(),
    )
    val reminder = scheduler.reminders.getValue(key("med@2030-01-01T18:00"))
    assertEquals("Ondansetron", reminder.title)
    assertEquals("1 tablet · Twice a day", reminder.text)
    assertEquals(MedicationReminderSync.SYNCHRONIZED, states.last())
  }

  @Test
  fun onlyTheNextDays_areScheduled_andOpeningTheAppLaterAddsTheFollowingOnes() = runTest {
    val cases =
        listOf(
            medication(days = null, times = listOf("20:00")) to 3,
            medication(start = today.plusDays(2), days = null, times = listOf("20:00")) to 1,
            medication(start = today.plusDays(3), times = listOf("20:00")) to 0,
            medication(start = today.minusDays(5), days = 5, times = listOf("20:00")) to 0,
            medication(times = emptyList()) to 0,
        )
    for ((entry, expected) in cases) {
      scheduler.reminders.clear()
      plans.write("patient", emptyMap())
      save(entry)
      val job = watch()
      assertEquals("$entry", expected, scheduler.reminders.size)
      stop(job)
    }
    save(medication(days = null, times = listOf("20:00")))
    clock.now = noon.plusSeconds(86_400)
    watch()
    assertEquals(at("2030-01-04T20:00:00Z"), pending().last())
  }

  @Test
  fun theTimeRings_whereThePatientIs() = runTest {
    val cases =
        listOf(
            ZoneId.of("Europe/Zurich") to at("2030-01-01T17:00:00Z"),
            ZoneId.of("America/New_York") to at("2030-01-01T23:00:00Z"),
        )
    for ((zone, expected) in cases) {
      scheduler.reminders.clear()
      plans.write("patient", emptyMap())
      save(medication(times = listOf("18:00"), days = 1))
      val job = watch(zone)
      assertEquals("$zone", listOf(expected), pending())
      stop(job)
    }
  }

  @Test
  fun changes_updateTheReminders_withoutDuplicates() = runTest {
    save(medication())
    watch()
    assertEquals(3, scheduler.scheduled.size)
    // The same snapshot again schedules nothing
    save(medication())
    assertEquals(3, scheduler.scheduled.size)
    // A new dosage replaces the text of the same alarms
    save(medication(dosage = "2 tablets"))
    assertEquals(6, scheduler.scheduled.size)
    assertEquals(3, scheduler.reminders.size)
    assertTrue(scheduler.reminders.values.all { it.text == "2 tablets · Twice a day" })
    // A moved time cancels the old alarms
    save(medication(times = listOf("09:00", "19:00"), dosage = "2 tablets"))
    assertEquals(
        listOf(key("med@2030-01-01T18:00"), key("med@2030-01-02T18:00")),
        scheduler.cancelled,
    )
    assertEquals(
        listOf(at("2030-01-01T19:00:00Z"), at("2030-01-02T09:00:00Z"), at("2030-01-02T19:00:00Z")),
        pending(),
    )
  }

  @Test
  fun turningRemindersOff_orDeletingTheMedication_cancelsThem() = runTest {
    val cases =
        listOf<TestScope.() -> Unit>(
            { save(medication(times = emptyList())) },
            { save(medication(id = "other")) },
            {
              medications.seed(
                  "patient",
                  Prescription("rx", prescribedOn = today, medications = emptyList()),
              )
              runCurrent()
            },
        )
    for (change in cases) {
      save(medication())
      val job = watch()
      assertEquals(3, scheduler.reminders.keys.count { it.itemId.startsWith("med@") })
      change()
      assertEquals(0, scheduler.reminders.keys.count { it.itemId.startsWith("med@") })
      stop(job)
    }
  }

  @Test
  fun aReminderThatRang_keepsItsNotification() = runTest {
    save(medication())
    watch()
    clock.now = at("2030-01-01T19:00:00Z")
    save(medication(times = emptyList()))
    // The one at 18:00 rang: only the two to come are cancelled
    assertEquals(
        listOf(key("med@2030-01-02T09:00"), key("med@2030-01-02T18:00")),
        scheduler.cancelled,
    )
    assertTrue(plans.read("patient").isEmpty())
  }

  @Test
  fun aCaregiversPrescription_remindsOfNothing_untilThePatientConfirmsIt() = runTest {
    save(medication(), confirmation = PrescriptionConfirmation.PENDING)
    watch()
    assertTrue(scheduler.scheduled.isEmpty())
    save(medication())
    assertEquals(3, scheduler.reminders.size)
  }

  @Test
  fun blockedReminders_areTriedAgain_theNextTime() = runTest {
    save(medication())
    scheduler.result = ReminderScheduleResult.NOTIFICATIONS_BLOCKED
    val job = watch()
    assertEquals(MedicationReminderSync.BLOCKED, states.last())
    assertTrue(plans.read("patient").isEmpty())
    stop(job)
    scheduler.result = ReminderScheduleResult.SCHEDULED
    watch()
    assertEquals(MedicationReminderSync.SYNCHRONIZED, states.last())
    assertEquals(3, scheduler.reminders.size)
  }

  @Test
  fun aReadFailure_cancelsNothing_andCollectingAgainRecovers() = runTest {
    save(medication())
    val job = watch()
    medications.failRunningReads(IllegalStateException("fictional failure"))
    runCurrent()
    assertEquals(MedicationReminderSync.FAILED, states.last())
    assertTrue(scheduler.cancelled.isEmpty())
    stop(job)
    watch()
    assertEquals(MedicationReminderSync.SYNCHRONIZED, states.last())
    assertEquals(3, scheduler.reminders.size)
  }

  @Test
  fun onlyTheSignedInAccount_isScheduled() = runTest {
    save(medication())
    watch()
    auth.signOut()
    runCurrent()
    assertEquals(MedicationReminderSync.IDLE, states.last())
    // Another account has no medications: the patient's reminders are left to sign-out (#454)
    auth.signInWithGoogle("other")
    runCurrent()
    assertEquals(MedicationReminderSync.SYNCHRONIZED, states.last())
    assertTrue(scheduler.cancelled.isEmpty())
    assertTrue(scheduler.reminders.keys.all { it.ownerUid == "patient" })
  }
}
