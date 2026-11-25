// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.domain.carecircle.FollowedPatient
import com.github.se.oncompanion.domain.medication.DailyDoses
import com.github.se.oncompanion.domain.medication.DoseCondition
import com.github.se.oncompanion.domain.planning.MedicationPlanningSource
import com.github.se.oncompanion.domain.planning.ObservePlanning
import com.github.se.oncompanion.domain.planning.followedPlanning
import com.github.se.oncompanion.domain.planning.ownPlanning
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.FakeTakenDosesRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.medication.TakenDosesRepository
import com.github.se.oncompanion.model.planning.FakePlanningRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.model.speech.FakeSpeechRecognizer
import com.github.se.oncompanion.model.symptom.FakeSymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.ui.VirtualClock
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper

// Robolectric provides the main dispatcher used by viewModelScope
@RunWith(AndroidJUnit4::class)
class OverviewViewModelTest {

  private val zone = ZoneOffset.UTC

  /** Saturday 3 October 2026, 9:00. */
  private val now = LocalDateTime.of(2026, 10, 3, 9, 0)

  /** A clock the tests can move forward. */
  private class TestClock(var time: LocalDateTime) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = time.toInstant(ZoneOffset.UTC)
  }

  private fun at(time: LocalDateTime): PlanningTiming = PlanningTiming.Timed(time.toInstant(zone))

  private fun appointment(id: String, time: LocalDateTime, place: String? = null) =
      PlanningItem(PlanningSource.Appointment(id), at(time), "Appointment $id", subtitle = place)

  private fun event(id: String, time: LocalDateTime) =
      PlanningItem(PlanningSource.Event(id), at(time), "Event $id", subtitle = "Place $id")

  private fun medication(
      id: String,
      date: LocalDate = now.toLocalDate(),
      frequency: String? = null,
  ) =
      PlanningItem(
          PlanningSource.Medication("prescription", id),
          PlanningTiming.DateOnly(date),
          "Medication $id",
          frequency = frequency,
      )

  private fun planning(vararg items: PlanningItem) =
      FakePlanningRepository().apply { this.items.value = items.toList() }

  private fun titlesOf(vm: OverviewViewModel) = vm.uiState.value.todayEntries.map { it.title }

  private fun statusesOf(vm: OverviewViewModel) =
      vm.uiState.value.todayEntries.associate { it.title to it.status }

  private fun signedIn(user: AuthUser) =
      FakeAuthRepository(onSignIn = { user }).also { runBlocking { it.signInWithGoogle("token") } }

  private fun viewModel(
      auth: AuthRepository = FakeAuthRepository(),
      profiles: FakeUserProfileRepository = FakeUserProfileRepository(),
      planning: PlanningRepository = FakePlanningRepository(),
      clock: Clock = TestClock(now),
      doses: TakenDosesRepository = FakeTakenDosesRepository(),
      symptoms: SymptomRepository = FakeSymptomRepository(),
  ) =
      OverviewViewModel(
          auth,
          profiles,
          ownPlanning(planning),
          clock,
          zone,
          takenDosesRepository = doses,
          symptomRepository = symptoms,
      )

  @Test
  fun canSpeak_onlyWhenThePhoneHasARecognizerThatCanListen() {
    val cases =
        listOf(
            FakeSpeechRecognizer(available = true) to true,
            FakeSpeechRecognizer(available = false) to false,
            // Before the app installed the phone's recognizer
            null to false,
        )
    for ((speech, expected) in cases) {
      val viewModel =
          OverviewViewModel(
              FakeAuthRepository(),
              FakeUserProfileRepository(),
              ownPlanning(FakePlanningRepository()),
              TestClock(now),
              zone,
              speechRecognizer = speech,
          )
      assertEquals("$speech", expected, viewModel.uiState.value.canSpeak)
    }
  }

  /** Saved medication days reach Overview without a time or a claim about intake status. */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun savedMedicationSourceShowsOnlyTodaysRecordedInformation() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val auth = FakeAuthRepository { AuthUser(it) }
      auth.signInWithGoogle("patient")
      val repository = FakeMedicationRepository()
      val today = now.toLocalDate()
      repository.addPrescription(
          "patient",
          Prescription(
              "p",
              prescribedOn = today,
              medications =
                  listOf(
                      Medication(
                          "active",
                          "p",
                          "Active medication",
                          dosage = "8 mg",
                          frequency = "As recorded",
                          startDate = today,
                          durationDays = 2,
                      ),
                      Medication("future", "p", "Future medication", startDate = today.plusDays(1)),
                      Medication(
                          "ended",
                          "p",
                          "Ended medication",
                          startDate = today.minusDays(1),
                          durationDays = 1,
                      ),
                  ),
          ),
      )
      val vm =
          viewModel(
              auth = auth,
              planning = ObservePlanning(MedicationPlanningSource(repository)),
          )
      runCurrent()
      val entry = vm.uiState.value.todayEntries.single()
      assertEquals("Active medication", entry.title)
      assertEquals("As recorded", entry.subtitle)
      assertEquals(TodayItemKind.MEDICATION, entry.kind)
      assertNull(entry.time)
      assertNull(entry.status)
      assertNull(vm.uiState.value.nextAppointment)
      // The patient's card names it with its dosage
      assertEquals(
          listOf(
              TodayMedication(
                  entry.key,
                  medicationId = "active",
                  name = "Active medication 8 mg",
                  frequency = "As recorded",
              )
          ),
          vm.uiState.value.todayMedications,
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  // ----- First name -----

  @Test
  fun signedOut_greetsWithoutAName() {
    assertNull(viewModel().uiState.value.firstName)
  }

  @Test
  fun firstName_comesFromTheProfile() {
    val profiles = FakeUserProfileRepository()
    profiles.seed(UserProfile(uid = "u1", role = Role.PATIENT, firstName = "Alex"))

    val state =
        viewModel(signedIn(AuthUser(uid = "u1", displayName = "Alexandra Martin")), profiles)

    assertEquals("Alex", state.uiState.value.firstName)
  }

  @Test
  fun withoutProfile_firstNameComesFromTheGoogleAccount() {
    val vm = viewModel(signedIn(AuthUser(uid = "u1", displayName = "Alexandra Martin")))

    assertEquals("Alexandra", vm.uiState.value.firstName)
  }

  @Test
  fun profileError_keepsTheGoogleName_andDoesNotBreakTheDay() {
    val profiles = FakeUserProfileRepository().apply { getProfileError = IllegalStateException() }

    val state =
        viewModel(signedIn(AuthUser(uid = "u1", displayName = "Alexandra Martin")), profiles)
            .uiState
            .value

    assertEquals("Alexandra", state.firstName)
    assertFalse(state.hasError)
  }

  @Test
  fun authFailure_greetsWithoutAName() {
    val brokenAuth =
        object : AuthRepository by FakeAuthRepository() {
          override val currentUser: AuthUser?
            get() = throw IllegalStateException("Firebase isn't initialized")
        }

    val state = viewModel(auth = brokenAuth).uiState.value

    assertNull(state.firstName)
    assertFalse(state.hasError)
  }

  // ----- Reading the planning data -----

  @Test
  fun emptyPlanning_givesAnEmptyLoadedDay() {
    val state = viewModel().uiState.value

    assertFalse(state.isLoading)
    assertNull(state.nextAppointment)
    assertTrue(state.todayEntries.isEmpty())
    assertEquals(now, state.now)
  }

  @Test
  fun planningIsObserved_fromTodayForAYear_inTheGivenZone() {
    val planning = FakePlanningRepository()
    viewModel(planning = planning)

    val range = planning.ranges.single()
    assertEquals(now.toLocalDate(), range.startDateInclusive)
    assertEquals(now.toLocalDate().plusYears(1), range.endDateExclusive)
    assertEquals(zone, range.zoneId)
  }

  @Test
  fun nextAppointment_monthsAway_isStillFound() {
    // Oncology check-ups are often months apart
    val vm = viewModel(planning = planning(appointment("check-up", now.plusMonths(4))))

    assertEquals(
        NextAppointment("check-up", "Appointment check-up", now.plusMonths(4)),
        vm.uiState.value.nextAppointment,
    )
    assertTrue(vm.uiState.value.todayEntries.isEmpty())
  }

  @Test
  fun stateIsLoading_untilThePlanningEmits() {
    val release = CompletableDeferred<Unit>()
    val slow =
        FakePlanningRepository().apply {
          observation = {
            flow {
              release.await()
              emit(emptyList())
            }
          }
        }
    val vm = viewModel(planning = slow)

    assertTrue(vm.uiState.value.isLoading)

    release.complete(Unit)
    ShadowLooper.idleMainLooper()

    assertFalse(vm.uiState.value.isLoading)
  }

  @Test
  fun failingPlanning_givesAnError_andReloadRecovers() {
    val planning = planning(event("yoga", now.withHour(10))).apply { fail = true }
    val vm = viewModel(planning = planning)

    assertTrue(vm.uiState.value.hasError)
    assertFalse(vm.uiState.value.isLoading)

    planning.fail = false
    vm.loadDay()

    assertFalse(vm.uiState.value.hasError)
    assertEquals(listOf("Event yoga"), titlesOf(vm))
  }

  @Test
  fun planningChanges_updateTheState() {
    val planning = FakePlanningRepository()
    val vm = viewModel(planning = planning)

    planning.items.value = listOf(event("yoga", now.withHour(10)))
    ShadowLooper.idleMainLooper()

    assertEquals(listOf("Event yoga"), titlesOf(vm))
  }

  // ----- Next appointment -----

  @Test
  fun nextAppointment_isTheFirstThatHasntStarted_withItsPlace() {
    val vm =
        viewModel(
            planning =
                planning(
                    appointment("started", now.minusMinutes(30)),
                    appointment("later", now.plusDays(3)),
                    appointment("soon", now.plusDays(1), place = "HUG"),
                    event("yoga", now.plusHours(2)),
                )
        )

    assertEquals(
        NextAppointment("soon", "Appointment soon", now.plusDays(1), "HUG"),
        vm.uiState.value.nextAppointment,
    )
  }

  @Test
  fun eventsAndMedications_areNeverTheNextAppointment() {
    val vm = viewModel(planning = planning(event("yoga", now.plusHours(2)), medication("m")))

    assertNull(vm.uiState.value.nextAppointment)
  }

  @Test
  fun nextAppointment_movesOn_onceItStarts() {
    val clock = TestClock(now)
    val vm =
        viewModel(
            planning =
                planning(appointment("a", now.plusHours(1)), appointment("b", now.plusDays(1))),
            clock = clock,
        )
    assertEquals("Appointment a", vm.uiState.value.nextAppointment?.title)

    clock.time = now.plusHours(2)
    vm.refreshNow()

    assertEquals("Appointment b", vm.uiState.value.nextAppointment?.title)
  }

  // ----- Today -----

  @Test
  fun today_keepsOnlyTodaysEntries_untimedFirst_thenByTime() {
    val vm =
        viewModel(
            planning =
                planning(
                    event("evening", now.withHour(18)),
                    appointment("tomorrow", now.plusDays(1)),
                    appointment("morning", now.withHour(10)),
                    medication("today", frequency = "Twice a day"),
                    medication("tomorrow", now.toLocalDate().plusDays(1)),
                )
        )

    val entries = vm.uiState.value.todayEntries
    assertEquals(
        listOf("Medication today", "Appointment morning", "Event evening"),
        entries.map { it.title },
    )
    assertEquals(listOf(null, LocalTime.of(10, 0), LocalTime.of(18, 0)), entries.map { it.time })
    assertEquals(
        listOf(TodayItemKind.MEDICATION, TodayItemKind.APPOINTMENT, TodayItemKind.EVENT),
        entries.map { it.kind },
    )
  }

  // ----- Patient's day (Overview UX Refactor) -----

  @Test
  fun nextUp_isTheNextEventOrAppointment_todayOrLater() {
    val today = now.toLocalDate()
    val tomorrow = today.plusDays(1)
    listOf(
            // Today's next one, an event or an appointment, before any later appointment
            listOf(appointment("later", now.plusDays(1)), event("yoga", now.withHour(10))) to
                "yoga",
            listOf(event("yoga", now.withHour(12)), appointment("blood", now.withHour(11))) to
                "blood",
            // One that has started, even this minute, isn't next any more
            listOf(appointment("started", now.withHour(8)), event("walk", now.withHour(12))) to
                "walk",
            listOf(
                event("now", now),
                appointment("past", now.withHour(8)),
                appointment("later", now.plusDays(1)),
            ) to "later",
            // Nothing more today: the next appointment or registered event, even weeks away
            listOf(
                event("tomorrow", tomorrow.atTime(10, 0)),
                appointment("check-up", now.plusWeeks(3)),
            ) to "tomorrow",
            listOf(
                appointment("check-up", tomorrow.atTime(9, 0)),
                event("tomorrow", tomorrow.atTime(10, 0)),
            ) to "check-up",
            listOf(appointment("check-up", now.plusWeeks(3))) to "check-up",
            listOf(event("tomorrow", tomorrow.atTime(10, 0)), medication("m")) to "tomorrow",
            listOf(medication("m")) to null,
        )
        .forEach { (items, expected) ->
          val vm = viewModel(planning = planning(*items.toTypedArray()))
          assertEquals(items.toString(), expected, vm.uiState.value.nextUp?.id)
        }
  }

  @Test
  fun nextUp_saysWhatItIs_whenAndWhere_andMovesOnOnceItStarts() {
    val clock = TestClock(now)
    val vm =
        viewModel(
            planning =
                planning(
                    event("yoga", now.withHour(10)),
                    appointment("check-up", now.plusDays(2), place = "HUG"),
                ),
            clock = clock,
        )
    assertEquals(
        NextUp(TodayItemKind.EVENT, "yoga", "Event yoga", now.withHour(10), "Place yoga"),
        vm.uiState.value.nextUp,
    )

    clock.time = now.withHour(10).plusMinutes(1)
    vm.refreshNow()

    assertEquals(
        NextUp(
            TodayItemKind.APPOINTMENT,
            "check-up",
            "Appointment check-up",
            now.plusDays(2),
            "HUG",
        ),
        vm.uiState.value.nextUp,
    )
  }

  @Test
  fun todayMedications_areTodaysConfirmedOnly_withTheirDosage_andFrequency() {
    val vm =
        viewModel(
            planning =
                planning(
                    medication("a", frequency = "Twice a day").copy(dosage = "8 mg"),
                    // Blank fields show nothing rather than an empty chip or a trailing space
                    medication("b", frequency = " ").copy(dosage = " "),
                    // A caregiver's prescription stays off the Overview until it is confirmed
                    medication("c").copy(addedByName = "Sam", isConfirmed = false),
                    medication("tomorrow", date = now.toLocalDate().plusDays(1)),
                    event("yoga", now.withHour(10)),
                )
        )

    assertEquals(
        listOf(
            Triple("a", "Medication a 8 mg", "Twice a day"),
            Triple("b", "Medication b", null),
        ),
        vm.uiState.value.todayMedications.map { Triple(it.medicationId, it.name, it.frequency) },
    )
  }

  // ----- Doses ticked on the Overview (US-28) -----

  private val patient = AuthUser(uid = "patient", displayName = "Alex Martin")

  /** A signed-in patient's Overview whose doses are kept in [doses]. */
  private fun patientViewModel(
      doses: FakeTakenDosesRepository,
      vararg items: PlanningItem,
      clock: Clock = TestClock(now),
  ) = viewModel(signedIn(patient), planning = planning(*items), clock = clock, doses = doses)

  private fun FakeTakenDosesRepository.saved(medicationId: String, count: Int) {
    days.value = days.value + (("patient" to now.toLocalDate()) to mapOf(medicationId to count))
  }

  private fun OverviewViewModel.medication(id: String) =
      uiState.value.todayMedications.single { it.medicationId == id }

  @Test
  fun eachMedication_hasItsDosesADay_fromItsFrequency_andTheDosesTickedToday() {
    // (frequency, saved count) to (doses a day, taken)
    listOf(
            ("3 times a day" to 1) to (3 to 1),
            ("Once a day" to 0) to (1 to 0),
            ("1-0-1" to 2) to (2 to 2),
            // Nothing to tick: taken only if needed, or a frequency that doesn't say how many
            ("If needed" to 0) to (null to 0),
            (null to 0) to (null to 0),
            // A count above today's doses (the frequency was edited since) shows as all taken
            ("Twice a day" to 5) to (2 to 2),
        )
        .forEach { (input, expected) ->
          val (frequency, saved) = input
          val doses = FakeTakenDosesRepository().apply { saved("m", saved) }
          val vm = patientViewModel(doses, medication("m", frequency = frequency))
          ShadowLooper.idleMainLooper()

          val shown = vm.medication("m")
          assertEquals("$input", expected, shown.dosesPerDay to shown.taken)
        }
  }

  @Test
  fun eachMedication_saysItsFrequencyInWords_whenItCan() {
    val vm =
        patientViewModel(
            FakeTakenDosesRepository(),
            medication("scheme", frequency = "1-0-0"),
            medication("condition", frequency = "1-1-1 si nausées"),
            medication("unknown", frequency = "en cas de toux"),
        )
    ShadowLooper.idleMainLooper()

    assertEquals(DailyDoses(1, null), vm.medication("scheme").dailyDoses)
    // Taken only if nauseous: said in words, but there is nothing to tick each day
    assertEquals(DailyDoses(3, DoseCondition.IF_NAUSEOUS), vm.medication("condition").dailyDoses)
    assertNull(vm.medication("condition").dosesPerDay)
    assertNull(vm.medication("unknown").dailyDoses)
  }

  @Test
  fun tickDose_ticksTheNextDose_upToTheDosesADay_neverMore() {
    val doses = FakeTakenDosesRepository()
    val vm = patientViewModel(doses, medication("m", frequency = "3 times a day"))
    ShadowLooper.idleMainLooper()

    repeat(4) {
      vm.tickDose("m")
      ShadowLooper.idleMainLooper()
    }

    assertEquals(listOf(1, 2, 3), doses.writes.map { it.count })
    assertEquals(3, vm.medication("m").taken)
    assertEquals(
        FakeTakenDosesRepository.Write("patient", now.toLocalDate(), "m", 1),
        doses.writes[0],
    )
  }

  @Test
  fun twoQuickTaps_tickTwoDoses_beforeTheRepositoryAnswers() = runTestOnVirtualMain {
    val doses = FakeTakenDosesRepository()
    val vm = patientViewModel(doses, medication("m", frequency = "3 times a day"))
    runCurrent()

    // The second tap comes before the first one's save: it must build on it, not save 1 again
    vm.tickDose("m")
    vm.tickDose("m")
    runCurrent()

    assertEquals(listOf(1, 2), doses.writes.map { it.count })
    assertEquals(2, vm.medication("m").taken)
  }

  @Test
  fun tickDose_onAMedicationTakenOnceADay_togglesIt() {
    val doses = FakeTakenDosesRepository()
    val vm = patientViewModel(doses, medication("m", frequency = "Once a day"))
    ShadowLooper.idleMainLooper()

    repeat(3) {
      vm.tickDose("m")
      ShadowLooper.idleMainLooper()
    }

    assertEquals(listOf(1, 0, 1), doses.writes.map { it.count })
    assertEquals(1, vm.medication("m").taken)
  }

  @Test
  fun tickDose_doesNothing_withoutDosesToTick_orForAnUnknownMedication() {
    val doses = FakeTakenDosesRepository()
    val vm = patientViewModel(doses, medication("m", frequency = "If needed"))
    ShadowLooper.idleMainLooper()

    vm.tickDose("m")
    vm.tickDose("unknown")
    ShadowLooper.idleMainLooper()

    assertEquals(emptyList<FakeTakenDosesRepository.Write>(), doses.writes)
  }

  @Test
  fun whenTodaysDosesCantBeRead_nothingCanBeTicked() {
    // A tick saves a count: ticking from a wrong 0 would overwrite the real one
    val doses = FakeTakenDosesRepository(readFailure = IllegalStateException("denied"))
    val vm = patientViewModel(doses, medication("m", frequency = "Twice a day"))
    ShadowLooper.idleMainLooper()

    vm.tickDose("m")
    ShadowLooper.idleMainLooper()

    assertNull(vm.medication("m").dosesPerDay)
    assertEquals(emptyList<FakeTakenDosesRepository.Write>(), doses.writes)
  }

  @Test
  fun aNewDay_startsWithNoDoseTicked() = runTestOnVirtualMain {
    val lateEvening = now.withHour(23).withMinute(59)
    val doses =
        FakeTakenDosesRepository().apply {
          days.value = mapOf(("patient" to now.toLocalDate()) to mapOf("m" to 1))
        }
    val vm =
        patientViewModel(
            doses,
            medication("m", frequency = "Once a day"),
            medication("m", date = now.toLocalDate().plusDays(1), frequency = "Once a day"),
            clock = VirtualClock(testScheduler, lateEvening.toInstant(zone), zone),
        )
    val following = vm.followToday()
    runCurrent()
    assertEquals(1, vm.medication("m").taken)

    advanceTimeBy(1.minutes)
    runCurrent()

    assertEquals(0, vm.medication("m").taken)
    vm.tickDose("m")
    runCurrent()
    assertEquals(now.toLocalDate().plusDays(1), doses.writes.single().date)
    following.cancel()
  }

  @Test
  fun signedOut_orAsACaregiver_nothingCanBeTicked() {
    val doses = FakeTakenDosesRepository()
    val signedOut =
        viewModel(planning = planning(medication("m", frequency = "Once a day")), doses = doses)
    ShadowLooper.idleMainLooper()
    signedOut.tickDose("m")
    ShadowLooper.idleMainLooper()

    assertNull(signedOut.medication("m").dosesPerDay)
    assertEquals(emptyList<FakeTakenDosesRepository.Write>(), doses.writes)

    // The caregiver's Overview shows no bar: the patient ticks their own doses
    val caregiver =
        caregiverViewModel(
            planning(medication("m", frequency = "Once a day")),
            CarePermission.PLANNING,
            CarePermission.PRESCRIPTIONS,
        )
    ShadowLooper.idleMainLooper()
    assertNull(caregiver.uiState.value.todayMedications.single().dosesPerDay)
  }

  // ----- The sheet to correct the doses (#546) -----

  private fun OverviewViewModel.sheetOf() = uiState.value.dosesSheet

  @Test
  fun openDoses_onlyForAMedicationTakenSeveralTimesADay_fromItsSavedCount() {
    val doses = FakeTakenDosesRepository().apply { saved("thrice", 1) }
    val vm =
        patientViewModel(
            doses,
            medication("thrice", frequency = "3 times a day"),
            medication("once", frequency = "Once a day"),
            medication("ifNeeded", frequency = "If needed"),
        )
    ShadowLooper.idleMainLooper()

    // Once a day toggles on the card, and there is nothing to correct without doses to tick
    listOf("once", "ifNeeded", "unknown").forEach { id ->
      vm.openDoses(id)
      assertNull(id, vm.sheetOf())
    }
    vm.openDoses("thrice")
    assertEquals(DosesSheet(vm.medication("thrice"), doses = 3, draft = 1), vm.sheetOf())
    // Opening one that can't be corrected leaves the open sheet as it is
    vm.openDoses("once")
    assertEquals("thrice", vm.sheetOf()?.medication?.medicationId)
  }

  @Test
  fun lessAndMore_changeOnlyTheDraft_from0ToTheDosesADay() {
    val doses = FakeTakenDosesRepository().apply { saved("m", 1) }
    val vm = patientViewModel(doses, medication("m", frequency = "3 times a day"))
    ShadowLooper.idleMainLooper()
    vm.openDoses("m")

    repeat(3) { vm.moreDoses() }
    assertEquals(3, vm.sheetOf()?.draft)
    repeat(4) { vm.lessDoses() }
    assertEquals(0, vm.sheetOf()?.draft)

    ShadowLooper.idleMainLooper()
    assertEquals(emptyList<FakeTakenDosesRepository.Write>(), doses.writes)
    assertEquals(1, vm.medication("m").taken)
  }

  @Test
  fun done_savesTheDraftOnTheDayShown_onceAndOnlyIfItChanged_andClosesTheSheet() {
    val doses = FakeTakenDosesRepository().apply { saved("m", 1) }
    val vm = patientViewModel(doses, medication("m", frequency = "3 times a day"))
    ShadowLooper.idleMainLooper()

    // Unchanged: nothing to save
    vm.openDoses("m")
    vm.saveDoses()
    ShadowLooper.idleMainLooper()
    assertNull(vm.sheetOf())
    assertEquals(emptyList<FakeTakenDosesRepository.Write>(), doses.writes)

    vm.openDoses("m")
    vm.lessDoses()
    vm.saveDoses()
    // A second tap on Done, before the sheet is gone, saves nothing more
    vm.saveDoses()
    ShadowLooper.idleMainLooper()
    assertNull(vm.sheetOf())
    assertEquals(
        listOf(FakeTakenDosesRepository.Write("patient", now.toLocalDate(), "m", 0)),
        doses.writes,
    )
    assertEquals(0, vm.medication("m").taken)

    // The server refuses it: the day's next snapshot brings the saved count back
    doses.saved("m", 1)
    ShadowLooper.idleMainLooper()
    assertEquals(1, vm.medication("m").taken)
  }

  @Test
  fun closingWithoutDone_orSigningOut_savesNothing_andReopeningStartsFromTheSavedCount() {
    val doses = FakeTakenDosesRepository().apply { saved("m", 1) }
    val auth = signedIn(patient)
    val vm =
        viewModel(
            auth,
            planning = planning(medication("m", frequency = "3 times a day")),
            doses = doses,
        )
    ShadowLooper.idleMainLooper()

    vm.openDoses("m")
    vm.moreDoses()
    vm.closeDoses()
    ShadowLooper.idleMainLooper()
    assertNull(vm.sheetOf())
    assertEquals(1, vm.medication("m").taken)

    // The draft went with the sheet
    vm.openDoses("m")
    assertEquals(1, vm.sheetOf()?.draft)

    vm.moreDoses()
    auth.signOut()
    vm.saveDoses()
    ShadowLooper.idleMainLooper()
    assertEquals(emptyList<FakeTakenDosesRepository.Write>(), doses.writes)
  }

  @Test
  fun aTickFromAnotherPhone_keepsTheDraft_andDoneStillSavesIt() {
    val doses = FakeTakenDosesRepository().apply { saved("m", 1) }
    val vm = patientViewModel(doses, medication("m", frequency = "3 times a day"))
    ShadowLooper.idleMainLooper()
    vm.openDoses("m")
    vm.moreDoses()

    doses.saved("m", 3)
    ShadowLooper.idleMainLooper()
    assertEquals(2, vm.sheetOf()?.draft)
    assertEquals(3, vm.sheetOf()?.medication?.taken)

    vm.saveDoses()
    ShadowLooper.idleMainLooper()
    assertEquals(2, doses.writes.single().count)
  }

  @Test
  fun theSheetFollowsItsMedication_closingWhenItCantBeCorrectedThereAnyMore() {
    val doses = FakeTakenDosesRepository()
    val plan = planning(medication("m", frequency = "3 times a day"))
    val vm = viewModel(signedIn(patient), planning = plan, doses = doses)
    ShadowLooper.idleMainLooper()
    vm.openDoses("m")
    repeat(3) { vm.moreDoses() }

    // Another medication added: the sheet and its draft stay
    plan.items.value =
        listOf(
            medication("m", frequency = "3 times a day"),
            medication("other", frequency = "Once a day"),
        )
    ShadowLooper.idleMainLooper()
    assertEquals(3, vm.sheetOf()?.draft)

    // The frequency is edited to twice a day: the draft stays within it
    plan.items.value = listOf(medication("m", frequency = "Twice a day"))
    ShadowLooper.idleMainLooper()
    assertEquals(DosesSheet(vm.medication("m"), doses = 2, draft = 2), vm.sheetOf())

    // Once a day, then deleted by a caregiver: each closes it, unsaved
    listOf(listOf(medication("m", frequency = "Once a day")), emptyList()).forEach { items ->
      plan.items.value = listOf(medication("m", frequency = "3 times a day"))
      ShadowLooper.idleMainLooper()
      vm.openDoses("m")
      plan.items.value = items
      ShadowLooper.idleMainLooper()
      assertNull("$items", vm.sheetOf())
    }
    assertEquals(emptyList<FakeTakenDosesRepository.Write>(), doses.writes)
  }

  @Test
  fun atMidnight_orWhenTheDayReloads_theSheetClosesUnsaved() = runTestOnVirtualMain {
    val lateEvening = now.withHour(23).withMinute(59)
    val doses = FakeTakenDosesRepository()
    val vm =
        patientViewModel(
            doses,
            medication("m", frequency = "3 times a day"),
            medication("m", date = now.toLocalDate().plusDays(1), frequency = "3 times a day"),
            clock = VirtualClock(testScheduler, lateEvening.toInstant(zone), zone),
        )
    val following = vm.followToday()
    runCurrent()
    vm.openDoses("m")
    vm.moreDoses()

    advanceTimeBy(1.minutes)
    runCurrent()

    // Its draft was for yesterday: Done would have saved it on the new day
    assertNull(vm.sheetOf())
    vm.saveDoses()
    runCurrent()
    assertEquals(emptyList<FakeTakenDosesRepository.Write>(), doses.writes)

    // Retry reloads the day too, which closes it the same way
    vm.openDoses("m")
    vm.moreDoses()
    vm.loadDay()
    runCurrent()
    assertNull(vm.sheetOf())
    assertEquals(emptyList<FakeTakenDosesRepository.Write>(), doses.writes)
    following.cancel()
  }

  @Test
  fun signedOut_asACaregiver_orBeforeTheTicksAreKnown_noSheetOpens() {
    val signedOut = viewModel(planning = planning(medication("m", frequency = "Twice a day")))
    val caregiver =
        caregiverViewModel(
            planning(medication("m", frequency = "Twice a day")),
            CarePermission.PLANNING,
            CarePermission.PRESCRIPTIONS,
        )
    val unknown =
        patientViewModel(
            FakeTakenDosesRepository(readFailure = IllegalStateException("denied")),
            medication("m", frequency = "Twice a day"),
        )
    ShadowLooper.idleMainLooper()

    listOf(signedOut, caregiver, unknown).forEach { vm ->
      vm.openDoses("m")
      assertNull(vm.sheetOf())
    }
  }

  @Test
  fun medication_showsItsFrequency_andNoStatus() {
    val vm = viewModel(planning = planning(medication("m", frequency = "Twice a day")))

    val entry = vm.uiState.value.todayEntries.single()
    assertEquals("Twice a day", entry.subtitle)
    // The app never decides whether a medication was taken
    assertNull(entry.status)
  }

  @Test
  fun medication_ofAnUnconfirmedPrescription_saysWhoAddedIt_andOnlyThen() {
    val vm =
        viewModel(
            planning =
                planning(
                    medication("pending").copy(addedByName = "Sam", isConfirmed = false),
                    medication("confirmed").copy(addedByName = "Sam"),
                )
        )

    assertEquals(
        mapOf("Medication pending" to "Sam", "Medication confirmed" to null),
        vm.uiState.value.todayEntries.associate { it.title to it.notConfirmedAddedBy },
    )
  }

  @Test
  fun timedEntries_areDoneNowNextOrLater_withTheClock() {
    val vm =
        viewModel(
            planning =
                planning(
                    event("over", now.withHour(7)),
                    appointment("now", now.minusMinutes(10)),
                    event("next", now.withHour(15)),
                    event("later", now.withHour(18)),
                )
        )

    assertEquals(
        mapOf(
            "Event over" to TodayItemStatus.DONE,
            "Appointment now" to TodayItemStatus.NOW,
            "Event next" to TodayItemStatus.NEXT,
            "Event later" to TodayItemStatus.LATER,
        ),
        statusesOf(vm),
    )
  }

  @Test
  fun anEntry_isNowForAnHour_thenDone() {
    val clock = TestClock(now)
    val vm =
        viewModel(planning = planning(appointment("chemo", now.minusMinutes(10))), clock = clock)
    assertEquals(TodayItemStatus.NOW, statusesOf(vm)["Appointment chemo"])

    clock.time = now.plusMinutes(50) // an hour after it started
    vm.refreshNow()

    assertEquals(TodayItemStatus.DONE, statusesOf(vm)["Appointment chemo"])
  }

  @Test
  fun anEventWithAnEndTime_isNowUntilItEnds() {
    val clock = TestClock(now)
    val start = now.minusMinutes(10)
    val workshop =
        event("workshop", start)
            .copy(
                timing =
                    PlanningTiming.Timed(
                        start.toInstant(zone),
                        start.plusMinutes(90).toInstant(zone),
                    )
            )
    val vm = viewModel(planning = planning(workshop), clock = clock)

    // (time, status): an hour after it started, the 90-minute workshop is still going on
    val cases =
        listOf(
            now.plusMinutes(50) to TodayItemStatus.NOW,
            now.plusMinutes(79) to TodayItemStatus.NOW,
            now.plusMinutes(80) to TodayItemStatus.DONE,
        )
    for ((time, expected) in cases) {
      clock.time = time
      vm.refreshNow()

      assertEquals("At $time", expected, statusesOf(vm)["Event workshop"])
    }
  }

  @Test
  fun whenEverythingIsOver_nothingIsNext() {
    val vm =
        viewModel(planning = planning(event("a", now.withHour(6)), event("b", now.withHour(7))))

    assertTrue(vm.uiState.value.todayEntries.all { it.status == TodayItemStatus.DONE })
  }

  @Test
  fun refreshNow_updatesTheTime_andTheStatuses() {
    val clock = TestClock(now)
    val vm = viewModel(planning = planning(event("lunch", now.withHour(13))), clock = clock)
    assertEquals(TodayItemStatus.NEXT, statusesOf(vm)["Event lunch"])

    clock.time = now.withHour(20)
    vm.refreshNow()

    assertEquals(now.withHour(20), vm.uiState.value.now)
    assertEquals(TodayItemStatus.DONE, statusesOf(vm)["Event lunch"])
  }

  @Test
  fun refreshNow_reloadsTheDay_onlyWhenTheDateChanges() {
    val clock = TestClock(now)
    val planning = FakePlanningRepository()
    val vm = viewModel(planning = planning, clock = clock)
    assertEquals(1, planning.ranges.size)

    clock.time = now.withHour(23)
    vm.refreshNow()
    assertEquals(1, planning.ranges.size)

    clock.time = now.plusDays(1).withHour(7)
    vm.refreshNow()
    assertEquals(2, planning.ranges.size)
    assertEquals(now.toLocalDate().plusDays(1), planning.ranges.last().startDateInclusive)
    assertEquals(now.plusDays(1).toLocalDate(), vm.uiState.value.now.toLocalDate())
  }

  // ----- Following the date -----

  /**
   * Runs [test] with `viewModelScope` on the test's virtual time, so the date alarm, which runs
   * there, can be fast-forwarded.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  private fun runTestOnVirtualMain(test: suspend TestScope.() -> Unit) = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      test()
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun followToday_refreshesTheTimeRightAway() = runTestOnVirtualMain {
    val clock = TestClock(now)
    val vm = viewModel(clock = clock)

    // Time passed while the Overview was in the background
    clock.time = now.withHour(20)
    val following = vm.followToday()
    runCurrent()

    assertEquals(now.withHour(20), vm.uiState.value.now)
    following.cancel()
  }

  @Test
  fun followToday_movesNextUpOnWhenItStarts_withoutLeavingTheScreen() {
    listOf(
            // The appointment at 10:00 is next until 10:00 exactly, then the 12:00 event
            listOf(appointment("blood", now.withHour(10)), event("yoga", now.withHour(12))),
            // Two entries starting together are both passed at once
            listOf(
                appointment("blood", now.withHour(10)),
                event("walk", now.withHour(10)),
                event("yoga", now.withHour(12)),
            ),
        )
        .forEach { items ->
          runTestOnVirtualMain {
            val clock = VirtualClock(testScheduler, now.toInstant(zone), zone)
            val vm = viewModel(planning = planning(*items.toTypedArray()), clock = clock)
            val following = vm.followToday()
            runCurrent()

            advanceTimeBy(59.minutes)
            runCurrent()
            // Nothing changed yet, so it hasn't refreshed: no timer every minute
            assertEquals(items.toString(), now, vm.uiState.value.now)
            assertEquals(items.toString(), now.withHour(10), vm.uiState.value.nextUp?.dateTime)

            advanceTimeBy(1.minutes)
            runCurrent()
            assertEquals(items.toString(), "yoga", vm.uiState.value.nextUp?.id)
            following.cancel()
          }
        }
  }

  @Test
  fun followToday_marksAnEntryDoneWhenItEnds() = runTestOnVirtualMain {
    val clock = VirtualClock(testScheduler, now.toInstant(zone), zone)
    // Started at 8:30, so happening until 9:30
    val vm =
        viewModel(planning = planning(appointment("blood", now.minusMinutes(30))), clock = clock)
    val following = vm.followToday()
    runCurrent()
    assertEquals(TodayItemStatus.NOW, statusesOf(vm)["Appointment blood"])

    advanceTimeBy(30.minutes)
    runCurrent()

    assertEquals(TodayItemStatus.DONE, statusesOf(vm)["Appointment blood"])
    following.cancel()
  }

  @Test
  fun followToday_marksAnEventDoneAtItsEndTime() = runTestOnVirtualMain {
    val clock = VirtualClock(testScheduler, now.toInstant(zone), zone)
    // From 8:30 to 10:00: still happening an hour after it started
    val start = now.minusMinutes(30)
    val workshop =
        event("workshop", start)
            .copy(
                timing =
                    PlanningTiming.Timed(
                        start.toInstant(zone),
                        start.plusMinutes(90).toInstant(zone),
                    )
            )
    val vm = viewModel(planning = planning(workshop), clock = clock)
    val following = vm.followToday()
    runCurrent()

    advanceTimeBy(59.minutes)
    runCurrent()
    assertEquals(TodayItemStatus.NOW, statusesOf(vm)["Event workshop"])

    advanceTimeBy(1.minutes)
    runCurrent()
    assertEquals(TodayItemStatus.DONE, statusesOf(vm)["Event workshop"])
    following.cancel()
  }

  @Test
  fun followToday_plansItsNextRefreshAgain_whenTheEntriesChange() {
    val blood = appointment("blood", now.withHour(10))
    val yoga = event("yoga", now.withHour(15))
    val walk = event("walk", now.withHour(11))
    // Without planning again, it would sleep until the start it first planned and miss the new one
    data class Case(
        val before: List<PlanningItem>,
        val after: List<PlanningItem>,
        val nextUpThen: String,
        val wait: kotlin.time.Duration,
        val nextUpAfter: String,
    )
    listOf(
            // Added before the 15:00 it was waiting for: Next up moves on at 10:00
            Case(listOf(yoga), listOf(yoga, blood), "blood", 1.hours, "yoga"),
            // Moved from 11:00 to 9:30, before the 10:00 it was waiting for
            Case(
                listOf(blood, walk),
                listOf(blood, event("walk", now.withHour(9).withMinute(30))),
                "walk",
                30.minutes,
                "blood",
            ),
        )
        .forEach { case ->
          runTestOnVirtualMain {
            val planning = planning(*case.before.toTypedArray())
            val clock = VirtualClock(testScheduler, now.toInstant(zone), zone)
            val vm = viewModel(planning = planning, clock = clock)
            val following = vm.followToday()
            runCurrent()

            planning.items.value = case.after
            runCurrent()
            assertEquals(case.toString(), case.nextUpThen, vm.uiState.value.nextUp?.id)

            advanceTimeBy(case.wait)
            runCurrent()
            assertEquals(case.toString(), case.nextUpAfter, vm.uiState.value.nextUp?.id)
            following.cancel()
          }
        }
  }

  /** A [VirtualClock] whose time can be set back, as when the phone's time is changed. */
  private class ShiftableClock(private val virtual: Clock) : Clock() {
    var shift: java.time.Duration = java.time.Duration.ZERO

    override fun getZone(): ZoneId = virtual.zone

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = virtual.instant().plus(shift)
  }

  @Test
  fun followToday_followsAClockSetBack_andPlansAgainFromIt() = runTestOnVirtualMain {
    val clock = ShiftableClock(VirtualClock(testScheduler, now.toInstant(zone), zone))
    val vm =
        viewModel(
            planning = planning(appointment("a", now.withHour(10)), event("b", now.withHour(11))),
            clock = clock,
        )
    val following = vm.followToday()
    runCurrent()

    // At 9:30 the phone's time is set back to 7:30
    advanceTimeBy(30.minutes)
    clock.shift = java.time.Duration.ofHours(-2)
    // The refresh planned for 10:00 still runs, and reads 8:00
    advanceTimeBy(30.minutes)
    runCurrent()
    assertEquals(now.withHour(8), vm.uiState.value.now)
    assertEquals("a", vm.uiState.value.nextUp?.id)

    // It plans again from the clock: "a" starts when the clock reads 10:00
    advanceTimeBy(2.hours)
    runCurrent()
    assertEquals("b", vm.uiState.value.nextUp?.id)
    following.cancel()
  }

  @Test
  fun followToday_movesOn_inTheHourRepeatedWhenTheClocksGoBack(): Unit = runTestOnVirtualMain {
    val zurich = ZoneId.of("Europe/Zurich")
    // 25 October 2026, 02:10 the second time: at 03:00 summer time the clocks went back to 02:00
    val clock = VirtualClock(testScheduler, Instant.parse("2026-10-25T01:10:00Z"), zurich)
    val planning =
        planning(
            // 02:45, also the second time
            PlanningItem(
                PlanningSource.Appointment("blood"),
                PlanningTiming.Timed(Instant.parse("2026-10-25T01:45:00Z")),
                "Appointment blood",
            ),
            PlanningItem(
                PlanningSource.Event("yoga"),
                PlanningTiming.Timed(Instant.parse("2026-10-25T09:00:00Z")),
                "Event yoga",
            ),
        )
    val vm =
        OverviewViewModel(
            FakeAuthRepository(),
            FakeUserProfileRepository(),
            ownPlanning(planning),
            clock,
            zurich,
        )
    val following = vm.followToday()
    runCurrent()
    assertEquals("blood", vm.uiState.value.nextUp?.id)

    // Planned in local times, the wake-up at 02:45 was read in summer time, already past: it never
    // suspended and froze the screen
    advanceTimeBy(35.minutes)
    runCurrent()

    assertEquals("yoga", vm.uiState.value.nextUp?.id)
    following.cancel()
  }

  @Test
  fun followToday_reloadsTheNewDayAtMidnight() = runTestOnVirtualMain {
    val lateEvening = now.withHour(23).withMinute(59)
    val planning = FakePlanningRepository()
    val clock = VirtualClock(testScheduler, lateEvening.toInstant(zone), zone)
    val vm = viewModel(planning = planning, clock = clock)
    val following = vm.followToday()
    runCurrent()
    assertEquals(1, planning.ranges.size)

    advanceTimeBy(1.minutes)
    runCurrent()

    val tomorrow = now.toLocalDate().plusDays(1)
    assertEquals(tomorrow, vm.uiState.value.now.toLocalDate())
    assertEquals(2, planning.ranges.size)
    assertEquals(tomorrow, planning.ranges.last().startDateInclusive)
    following.cancel()
  }

  // ----- Caregiver mode (US-20) -----

  private fun caregiverViewModel(planning: FakePlanningRepository, vararg shared: CarePermission) =
      caregiverViewModel(planning, FollowedPatient(*shared), shared.toSet())

  /** The Overview of the patient a caregiver follows, with [patient] deciding the tiles. */
  private fun caregiverViewModel(
      planning: FakePlanningRepository,
      patient: FollowedPatient,
      planningShared: Set<CarePermission> = setOf(CarePermission.PLANNING),
  ) =
      OverviewViewModel(
          authRepository = patient.auth,
          observePlanning = followedPlanning(planning, planningShared),
          clock = TestClock(now),
          zoneId = zone,
          patientUid = "patient",
          resolveAccess = patient.careAccess,
      )

  @Test
  fun caregiver_sharedSections_areThoseThePatientShares() {
    for (shared in listOf(setOf(CarePermission.SYMPTOMS), setOf(CarePermission.PLANNING))) {
      assertEquals(
          shared,
          caregiverViewModel(planning(), *shared.toTypedArray()).uiState.value.sharedSections,
      )
    }
  }

  @Test
  fun caregiver_sharedSections_followThePatient_andComeBackAfterAnErrorOnRetry() {
    val patient = FollowedPatient(CarePermission.SYMPTOMS)
    patient.circle.observeError = IllegalStateException("offline")
    val vm = caregiverViewModel(planning(), patient)
    assertTrue(vm.uiState.value.sharedSections.isEmpty())

    patient.circle.observeError = null
    vm.loadDay()
    ShadowLooper.idleMainLooper()
    assertEquals(setOf(CarePermission.SYMPTOMS), vm.uiState.value.sharedSections)

    // The patient stops sharing their symptoms
    patient.shares(CarePermission.PLANNING)
    ShadowLooper.idleMainLooper()
    assertEquals(setOf(CarePermission.PLANNING), vm.uiState.value.sharedSections)
  }

  @Test
  fun caregiver_planningShared_showsThePatientsNextAppointment() {
    val planning = planning(appointment("chemo", now.plusDays(2)))

    val state = caregiverViewModel(planning, CarePermission.PLANNING).uiState.value

    assertEquals("chemo", state.nextAppointment?.id)
    assertFalse(state.isNotShared)
    assertTrue(planning.owners.all { it == "patient" })
  }

  @Test
  fun caregiver_planningNotShared_isNotShared_withNothingToShow() {
    val planning = planning(appointment("chemo", now.plusDays(2)))

    val state = caregiverViewModel(planning, CarePermission.SYMPTOMS).uiState.value

    assertTrue(state.isNotShared)
    assertFalse(state.isLoading)
    assertNull(state.nextAppointment)
    assertTrue(state.todayEntries.isEmpty())
    assertTrue(planning.owners.isEmpty())
  }

  @Test
  fun caregiver_sharedSections_followEachSection() {
    assertEquals(
        setOf(CarePermission.SYMPTOMS, CarePermission.PRESCRIPTIONS),
        caregiverViewModel(planning(), CarePermission.SYMPTOMS, CarePermission.PRESCRIPTIONS)
            .uiState
            .value
            .sharedSections,
    )
  }

  // ----- The entry saved today, on the check-in (#544) -----

  /** An entry felt at [felt] and saved at [saved] (`null` while it is still being saved). */
  private fun entry(
      id: String,
      felt: LocalDateTime,
      saved: LocalDateTime? = felt,
      addedBy: String? = null,
  ) =
      SymptomEntry(
          id = id,
          types = listOf(SymptomType.NAUSEA),
          occurredAt = felt.toInstant(zone),
          strengths = mapOf(SymptomType.NAUSEA to 7),
          createdAt = saved?.toInstant(zone),
          addedBy = addedBy,
      )

  private fun journalViewModel(
      symptoms: SymptomRepository,
      clock: Clock = TestClock(now),
      auth: AuthRepository = signedIn(patient),
  ) = viewModel(auth, clock = clock, symptoms = symptoms)

  private fun OverviewViewModel.savedId() = uiState.value.savedToday?.entry?.id

  @Test
  fun savedToday_isTheEntrySavedLastAmongThoseFeltToday() {
    val at8 = now.withHour(8)
    // The entries in the journal to the one the check-in shows
    listOf(
            emptyList<SymptomEntry>() to null,
            listOf(entry("yesterday", now.minusDays(1))) to null,
            // Saved last, although felt earlier
            listOf(
                entry("a", at8),
                entry("b", at8.minusHours(1), saved = at8.plusMinutes(5)),
            ) to "b",
            // A caregiver's entry isn't the patient's to undo
            listOf(entry("own", at8), entry("sam's", at8.plusMinutes(5), addedBy = "sam")) to "own",
            listOf(entry("sam's", at8, addedBy = "sam")) to null,
            // Still being saved: no creation time yet, but the newest
            listOf(entry("a", at8), entry("pending", now.withHour(6), saved = null)) to "pending",
        )
        .forEach { (entries, expected) ->
          val symptoms = FakeSymptomRepository().apply { seed("patient", *entries.toTypedArray()) }
          val vm = journalViewModel(symptoms)
          ShadowLooper.idleMainLooper()

          assertEquals("$entries", expected, vm.savedId())
        }
  }

  @Test
  fun savedToday_isTodayInThePatientsZone_notInUtc() {
    val zurich = ZoneId.of("Europe/Zurich")
    val today = now.toLocalDate()
    fun felt(time: LocalDateTime) =
        entry("", now)
            .copy(
                occurredAt = time.atZone(zurich).toInstant(),
                createdAt = time.atZone(zurich).toInstant(),
            )
    // 00:30 in Zurich is 22:30 UTC the day before; 23:30 the day before is yesterday there
    val symptoms =
        FakeSymptomRepository().apply {
          seed(
              "patient",
              felt(today.atTime(0, 30)).copy(id = "after midnight"),
              felt(today.minusDays(1).atTime(23, 30)).copy(id = "yesterday"),
          )
        }
    val vm =
        OverviewViewModel(
            signedIn(patient),
            FakeUserProfileRepository(),
            ownPlanning(FakePlanningRepository()),
            Clock.fixed(now.atZone(zurich).toInstant(), zurich),
            zurich,
            symptomRepository = symptoms,
        )
    ShadowLooper.idleMainLooper()
    assertEquals("after midnight", vm.savedId())
    assertEquals(LocalTime.of(0, 30), vm.uiState.value.savedToday?.at?.toLocalTime())

    runBlocking { symptoms.deleteSymptom("patient", "after midnight") }
    ShadowLooper.idleMainLooper()
    assertNull(vm.savedId())
  }

  private suspend fun FakeSymptomRepository.feltAt(time: LocalDateTime) {
    updateSymptom("patient", symptoms("patient").single().copy(occurredAt = time.toInstant(zone)))
  }

  @Test
  fun savedToday_followsTheJournal_andANewDayStartsWithTheTiles() = runBlocking {
    val clock = TestClock(now)
    val symptoms = FakeSymptomRepository(clock = { clock.instant() })
    val vm = journalViewModel(symptoms, clock)
    ShadowLooper.idleMainLooper()
    assertNull(vm.savedId())

    val id = symptoms.addSymptom("patient", entry("", now.minusMinutes(5), saved = null))
    ShadowLooper.idleMainLooper()
    assertEquals(id, vm.savedId())
    assertEquals(listOf(SymptomType.NAUSEA to 7), vm.uiState.value.savedToday?.symptoms)

    // Moved to yesterday in the form: not today's anymore, then back
    symptoms.feltAt(now.minusDays(1))
    ShadowLooper.idleMainLooper()
    assertNull(vm.savedId())
    symptoms.feltAt(now.minusHours(1))
    ShadowLooper.idleMainLooper()
    assertEquals(id, vm.savedId())

    clock.time = now.toLocalDate().plusDays(1).atTime(0, 1)
    vm.refreshNow()
    ShadowLooper.idleMainLooper()
    assertNull(vm.savedId())
  }

  @Test
  fun savedToday_isNull_whenTheJournalCantBeRead_nobodyIsSignedIn_orForACaregiver() {
    val unreadable =
        FakeSymptomRepository().apply {
          seed("patient", entry("a", now))
          observeError = IllegalStateException("offline")
        }
    val failed = journalViewModel(unreadable)
    ShadowLooper.idleMainLooper()
    assertNull(failed.savedId())

    val symptoms = FakeSymptomRepository().apply { seed("patient", entry("a", now)) }
    val signedOut = journalViewModel(symptoms, auth = FakeAuthRepository())
    ShadowLooper.idleMainLooper()
    assertNull(signedOut.savedId())

    // The caregiver's Overview has no check-in: their own journal isn't read
    val followed = FollowedPatient(CarePermission.SYMPTOMS)
    val caregiverUid = checkNotNull(followed.auth.currentUser).uid
    val own = FakeSymptomRepository().apply { seed(caregiverUid, entry("a", now)) }
    val caregiver =
        OverviewViewModel(
            authRepository = followed.auth,
            observePlanning = followedPlanning(planning(), setOf(CarePermission.PLANNING)),
            clock = TestClock(now),
            zoneId = zone,
            patientUid = "patient",
            resolveAccess = followed.careAccess,
            symptomRepository = own,
        )
    ShadowLooper.idleMainLooper()
    assertNull(caregiver.savedId())
  }

  /** A journal whose deletions wait for [gate], as on a slow network. */
  private class SlowDeletes(val fake: FakeSymptomRepository) : SymptomRepository by fake {
    val gate = CompletableDeferred<Unit>()

    override suspend fun deleteSymptom(uid: String, id: String) {
      gate.await()
      fake.deleteSymptom(uid, id)
    }
  }

  @Test
  fun undoSaved_hidesTheEntryAtOnce_deletesIt_andAQuickSecondTapDoesntDeleteThePreviousOne() {
    val fake =
        FakeSymptomRepository().apply {
          seed("patient", entry("earlier", now.withHour(7)), entry("last", now.withHour(8)))
        }
    val symptoms = SlowDeletes(fake)
    val vm = journalViewModel(symptoms)
    ShadowLooper.idleMainLooper()
    assertEquals("last", vm.savedId())

    vm.undoSaved("last")
    ShadowLooper.idleMainLooper()
    // The previous entry of the day shows before the deletion is confirmed
    assertEquals("earlier", vm.savedId())

    // The second tap of a double tap still names the entry undone
    vm.undoSaved("last")
    symptoms.gate.complete(Unit)
    ShadowLooper.idleMainLooper()

    assertEquals(listOf("earlier"), fake.symptoms("patient").map { it.id })
    assertEquals("earlier", vm.savedId())
    assertFalse(vm.uiState.value.undoFailed)
  }

  @Test
  fun undoSaved_again_andAgain_undoesEachEntryOfTheDay_untilTheTiles() {
    val symptoms =
        FakeSymptomRepository().apply {
          seed(
              "patient",
              entry("first", now.withHour(7)),
              entry("second", now.withHour(8)),
              entry("third", now.withHour(9)),
          )
        }
    val vm = journalViewModel(symptoms)
    ShadowLooper.idleMainLooper()

    // Each Undo deletes the entry shown, and the one saved before it takes its place
    for ((undone, next) in listOf("third" to "second", "second" to "first", "first" to null)) {
      assertEquals(undone, vm.savedId())
      vm.undoSaved(undone)
      ShadowLooper.idleMainLooper()
      assertEquals("after undoing $undone", next, vm.savedId())
    }
    assertTrue(symptoms.symptoms("patient").isEmpty())
    assertFalse(vm.uiState.value.undoFailed)
  }

  @Test
  fun undoSaved_failing_bringsTheEntryBack_andSaysSoOnce() {
    val symptoms = FakeSymptomRepository().apply { seed("patient", entry("last", now)) }
    val auth = signedIn(patient)
    val vm = journalViewModel(symptoms, auth = auth)
    ShadowLooper.idleMainLooper()

    symptoms.writeError = IllegalStateException("offline")
    vm.undoSaved("last")
    ShadowLooper.idleMainLooper()
    assertEquals("last", vm.savedId())
    assertTrue(vm.uiState.value.undoFailed)

    vm.undoFailureShown()
    assertFalse(vm.uiState.value.undoFailed)

    // Signed out meanwhile: nothing can be deleted, and the patient is told
    symptoms.writeError = null
    auth.signOut()
    vm.undoSaved("last")
    ShadowLooper.idleMainLooper()
    assertTrue(vm.uiState.value.undoFailed)
    assertEquals(listOf("last"), symptoms.symptoms("patient").map { it.id })
  }

  @Test
  fun undoSaved_rejectedByTheServerLater_showsTheEntryAgain() {
    val symptoms =
        FakeSymptomRepository().apply {
          seed("patient", entry("earlier", now.withHour(7)), entry("last", now.withHour(8)))
          serverRejectsWrites = true
        }
    val vm = journalViewModel(symptoms)
    ShadowLooper.idleMainLooper()

    // Deleted on the device: the previous entry shows
    vm.undoSaved("last")
    ShadowLooper.idleMainLooper()
    assertEquals("earlier", vm.savedId())

    // The server rejects it after the delete returned: Firestore puts it back, and so does the card
    symptoms.rejectPendingWrites()
    ShadowLooper.idleMainLooper()
    assertEquals("last", vm.savedId())
  }
}
