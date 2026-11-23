// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview.caregiver

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.domain.carecircle.FollowedPatient
import com.github.se.oncompanion.domain.carecircle.ObserveCareSection
import com.github.se.oncompanion.domain.planning.ObserveCarePlanning
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.CarePermission.PLANNING
import com.github.se.oncompanion.model.carecircle.CarePermission.PRESCRIPTIONS
import com.github.se.oncompanion.model.carecircle.CarePermission.SYMPTOMS
import com.github.se.oncompanion.model.planning.FakePlanningRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.model.symptom.FakeSymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.model.symptom.SymptomType.FATIGUE
import com.github.se.oncompanion.model.symptom.SymptomType.NAUSEA
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.ui.VirtualClock
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.overview.caregiver.SectionVisibility.PRIVATE
import com.github.se.oncompanion.ui.overview.caregiver.SectionVisibility.SHOWN
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
class CaregiverOverviewViewModelTest {

  private val zone = ZoneOffset.UTC

  /** Saturday 3 October 2026, 9:00. */
  private val now = LocalDateTime.of(2026, 10, 3, 9, 0)

  private val fixedClock = Clock.fixed(now.toInstant(zone), zone)

  private fun appointment(id: String, time: LocalDateTime) =
      PlanningItem(
          PlanningSource.Appointment(id),
          PlanningTiming.Timed(time.toInstant(zone)),
          "Appointment $id",
          subtitle = "Place $id",
      )

  private fun planning(vararg items: PlanningItem) =
      FakePlanningRepository().apply { this.items.value = items.toList() }

  /**
   * The Overview of "caregiver", who follows [patient]: the access comes from [patient], the
   * planning from [planningOf] and the journal from [journalOf], the same patient unless a test
   * makes them disagree.
   */
  private fun viewModel(
      patient: FollowedPatient,
      planning: FakePlanningRepository = planning(),
      clock: Clock = fixedClock,
      planningOf: FollowedPatient = patient,
      symptoms: SymptomRepository = FakeSymptomRepository(),
      journalOf: FollowedPatient = patient,
      patientUid: String = "patient",
      auth: AuthRepository = patient.auth,
      profiles: FakeUserProfileRepository = patient.profiles,
  ) =
      CaregiverOverviewViewModel(
              patientUid = patientUid,
              authRepository = auth,
              profileRepository = profiles,
              observePlanning =
                  ObserveCarePlanning(planningOf.sectionAccess, planning, planningOf.circle),
              symptomRepository = symptoms,
              observeSection = ObserveCareSection(journalOf.sectionAccess),
              resolveAccess = patient.careAccess,
              clock = clock,
              zoneId = zone,
          )
          // The access and the planning are combined on the main thread
          .also { ShadowLooper.idleMainLooper() }

  // ----- Greeting -----

  @Test
  fun greeting_namesTheCaregiver_notThePatient() {
    // FollowedPatient's caregiver is "Sam" in their profile
    assertEquals("Sam", viewModel(FollowedPatient(PLANNING)).uiState.value.firstName)
  }

  @Test
  fun greeting_withoutAProfileName_usesTheGoogleName_orNone() {
    val cases =
        listOf(
            // Signed out: no name at all
            FakeAuthRepository() to null,
            signedIn(AuthUser("caregiver", displayName = "Marc Dupont")) to "Marc",
            // Neither a profile nor a Google name: no made-up "Caregiver"
            signedIn(AuthUser("caregiver")) to null,
        )
    for ((auth, expected) in cases) {
      val vm =
          viewModel(FollowedPatient(PLANNING), auth = auth, profiles = FakeUserProfileRepository())
      assertEquals(expected, vm.uiState.value.firstName)
    }
  }

  private fun signedIn(user: AuthUser) =
      FakeAuthRepository(onSignIn = { user }).also { runBlocking { it.signInWithGoogle("token") } }

  // ----- What the patient shares -----

  @Test
  fun eachSectionOfTheDay_isShownOrPrivate_asThePatientShares() {
    val cases =
        listOf(
            setOf(PLANNING, PRESCRIPTIONS, SYMPTOMS) to listOf(SHOWN, SHOWN, SHOWN),
            setOf(PRESCRIPTIONS, SYMPTOMS) to listOf(PRIVATE, SHOWN, SHOWN),
            setOf(PLANNING) to listOf(SHOWN, PRIVATE, PRIVATE),
            // Events aren't a card of the day
            setOf(CarePermission.EVENTS) to listOf(PRIVATE, PRIVATE, PRIVATE),
        )
    // The cards' order, written out: Planning, then Prescriptions, then the journal
    val privateSections =
        listOf(
            emptyList(),
            listOf(PLANNING),
            listOf(PRESCRIPTIONS, SYMPTOMS),
            listOf(PLANNING, PRESCRIPTIONS, SYMPTOMS),
        )
    for ((case, expectedPrivate) in cases.zip(privateSections)) {
      val (shared, expected) = case
      val state = viewModel(FollowedPatient(*shared.toTypedArray())).uiState.value
      assertEquals(
          shared.toString(),
          listOf(PLANNING, PRESCRIPTIONS, SYMPTOMS),
          state.sections.keys.toList(),
      )
      assertEquals(shared.toString(), expected, state.sections.values.toList())
      assertEquals(shared.toString(), expectedPrivate, state.privateSections)
      assertFalse(shared.toString(), state.isLoading)
    }
  }

  @Test
  fun planningShared_showsThePatientsNextUp_readFromTheirPlanning() {
    val planning =
        planning(appointment("chemo", now.plusDays(2)), appointment("past", now.minusHours(2)))

    val state = viewModel(FollowedPatient(PLANNING), planning).uiState.value

    assertEquals("chemo", state.nextUp?.id)
    assertEquals("Place chemo", state.nextUp?.place)
    assertFalse(state.hasError)
    assertEquals(listOf("patient"), planning.owners.distinct())
  }

  @Test
  fun planningPrivate_showsNothingOfIt_andIsNeverRead() {
    val planning = planning(appointment("chemo", now.plusDays(2)))

    val state = viewModel(FollowedPatient(SYMPTOMS), planning).uiState.value

    assertEquals(PRIVATE, state.sections[PLANNING])
    assertNull(state.nextUp)
    assertFalse(state.isLoading)
    assertTrue(planning.owners.isEmpty())
  }

  @Test
  fun planningWithdrawnWhileOpen_isPrivateAtOnce_evenBeforeThePlanningFollows() {
    val patient = FollowedPatient(PLANNING)
    // The planning's own listener still sees it shared, as when it answers later than the access
    val planningStillShared = FollowedPatient(PLANNING)
    val vm =
        viewModel(
            patient,
            planning(appointment("chemo", now.plusDays(2))),
            planningOf = planningStillShared,
        )
    assertEquals("chemo", vm.uiState.value.nextUp?.id)

    patient.shares(SYMPTOMS)
    ShadowLooper.idleMainLooper()

    assertEquals(PRIVATE, vm.uiState.value.sections[PLANNING])
    assertNull(vm.uiState.value.nextUp)

    // Shared again: it comes back
    patient.shares(PLANNING)
    ShadowLooper.idleMainLooper()
    assertEquals("chemo", vm.uiState.value.nextUp?.id)
  }

  @Test
  fun planningStartedSharingWhileOpen_staysLoading_untilThePlanningFollows() {
    val patient = FollowedPatient(SYMPTOMS)
    // The planning's own listener answers later than the access, so it still says "not shared"
    val planningNotYetShared = FollowedPatient(SYMPTOMS)
    val vm =
        viewModel(
            patient,
            planning(appointment("chemo", now.plusDays(2))),
            planningOf = planningNotYetShared,
        )

    patient.shares(PLANNING, SYMPTOMS)
    ShadowLooper.idleMainLooper()

    // Not "Nothing planned today" while the patient has a chemo
    assertEquals(SHOWN, vm.uiState.value.sections[PLANNING])
    assertTrue(vm.uiState.value.isLoading)

    planningNotYetShared.shares(PLANNING, SYMPTOMS)
    ShadowLooper.idleMainLooper()
    assertFalse(vm.uiState.value.isLoading)
    assertEquals("chemo", vm.uiState.value.nextUp?.id)
  }

  @Test
  fun planningNotLoadedYet_isLoading_evenOnceTheAccessIsKnown() {
    // The access is known, but the planning's listener isn't confirmed yet
    val state =
        viewModel(
                FollowedPatient(PLANNING),
                planningOf = FollowedPatient(PLANNING).apply { isUnconfirmed() },
            )
            .uiState
            .value

    assertEquals(SHOWN, state.sections[PLANNING])
    assertTrue(state.isLoading)
  }

  @Test
  fun accessNotConfirmedYet_isLoading_evenOnceThePlanningIsKnown() {
    val state =
        viewModel(
                FollowedPatient(PLANNING).apply { isUnconfirmed() },
                planning(appointment("chemo", now.plusDays(2))),
                planningOf = FollowedPatient(PLANNING),
            )
            .uiState
            .value

    assertTrue(state.isLoading)
    assertTrue(state.sections.isEmpty())
  }

  @Test
  fun removedFromTheCircle_showsNoSectionAndNoNextUp() {
    val patient = FollowedPatient(PLANNING)
    val vm = viewModel(patient, planning(appointment("chemo", now.plusDays(2))))

    patient.removesTheCaregiver()
    ShadowLooper.idleMainLooper()

    assertTrue(vm.uiState.value.sections.isEmpty())
    assertNull(vm.uiState.value.nextUp)
    assertFalse(vm.uiState.value.isLoading)
  }

  // ----- The journal -----

  private fun entry(
      id: String,
      at: LocalDateTime,
      intensity: Int? = null,
      strengths: Map<SymptomType, Int> = emptyMap(),
  ) =
      SymptomEntry(
          id = id,
          types = listOf(NAUSEA, FATIGUE),
          occurredAt = at.toInstant(zone),
          intensity = intensity,
          strengths = strengths,
      )

  private fun journal(vararg entries: SymptomEntry) =
      FakeSymptomRepository().apply { seed("patient", *entries) }

  @Test
  fun latestEntry_isTheNewest_withAStrengthPerSymptom_orItsOneIntensity() {
    val yesterday = now.minusDays(1).withHour(18).withMinute(10)
    val cases =
        listOf(
            // A strength per symptom (#615): each symptom has its own, no overall one
            entry("new", yesterday, intensity = 7, strengths = mapOf(NAUSEA to 7, FATIGUE to 6)) to
                (listOf(NAUSEA to 7, FATIGUE to 6) to null),
            // Saved before #615: one intensity for the whole entry
            entry("new", yesterday, intensity = 6) to
                (listOf(NAUSEA to null, FATIGUE to null) to 6),
            // No number at all: only the names
            entry("new", yesterday) to (listOf(NAUSEA to null, FATIGUE to null) to null),
        )
    for ((newest, expected) in cases) {
      // The older entry comes after the newest one, so the order of the list doesn't matter
      val symptoms =
          object : SymptomRepository by journal() {
            override fun observeSymptoms(uid: String) = flow {
              emit(listOf(entry("old", yesterday.minusDays(3), intensity = 2), newest))
            }
          }

      val latest =
          viewModel(FollowedPatient(SYMPTOMS), symptoms = symptoms).uiState.value.latestEntry

      assertEquals(newest.toString(), "new", latest?.entry?.id)
      assertEquals(newest.toString(), yesterday, latest?.at)
      assertEquals(newest.toString(), expected.first, latest?.symptoms)
      assertEquals(newest.toString(), expected.second, latest?.overall)
    }
  }

  @Test
  fun emptyJournal_hasNoEntry_andIsNotLoading() {
    val state = viewModel(FollowedPatient(SYMPTOMS), symptoms = journal()).uiState.value

    assertNull(state.latestEntry)
    assertEquals(SHOWN, state.sections[SYMPTOMS])
    assertFalse(state.isLoading)
    assertFalse(state.hasError)
  }

  @Test
  fun journalChangesWhileOpen_theEntryFollows() {
    val symptoms = journal(entry("old", now.minusDays(2)))
    val vm = viewModel(FollowedPatient(SYMPTOMS), symptoms = symptoms)
    assertEquals("old", vm.uiState.value.latestEntry?.entry?.id)

    symptoms.seed("patient", entry("new", now.minusHours(1)))
    ShadowLooper.idleMainLooper()
    assertEquals("new", vm.uiState.value.latestEntry?.entry?.id)

    runBlocking { symptoms.deleteSymptom("patient", "new") }
    ShadowLooper.idleMainLooper()
    assertEquals("old", vm.uiState.value.latestEntry?.entry?.id)
  }

  @Test
  fun symptomsWithdrawnWhileOpen_hidesTheEntryAtOnce_evenBeforeTheJournalFollows() {
    val patient = FollowedPatient(SYMPTOMS)
    // The journal's own listener still sees it shared, as when it answers later than the access
    val vm =
        viewModel(
            patient,
            symptoms = journal(entry("new", now.minusHours(1))),
            journalOf = FollowedPatient(SYMPTOMS),
        )
    assertEquals("new", vm.uiState.value.latestEntry?.entry?.id)

    patient.shares(PLANNING)
    ShadowLooper.idleMainLooper()

    assertEquals(PRIVATE, vm.uiState.value.sections[SYMPTOMS])
    assertNull(vm.uiState.value.latestEntry)
  }

  @Test
  fun symptomsStartedSharingWhileOpen_staysLoading_untilTheJournalFollows() {
    val patient = FollowedPatient(PLANNING)
    // The journal's own listener answers later than the access, so it still says "not shared"
    val journalNotYetShared = FollowedPatient(PLANNING)
    val vm =
        viewModel(
            patient,
            symptoms = journal(entry("new", now.minusHours(1))),
            journalOf = journalNotYetShared,
        )

    patient.shares(PLANNING, SYMPTOMS)
    ShadowLooper.idleMainLooper()

    // Not "No entries yet" while the patient logged one an hour ago
    assertEquals(SHOWN, vm.uiState.value.sections[SYMPTOMS])
    assertTrue(vm.uiState.value.isLoading)

    journalNotYetShared.shares(PLANNING, SYMPTOMS)
    ShadowLooper.idleMainLooper()
    assertFalse(vm.uiState.value.isLoading)
    assertEquals("new", vm.uiState.value.latestEntry?.entry?.id)
  }

  @Test
  fun journalNotReadYet_isLoading_onlyWhileTheSymptomsAreShared() {
    // A journal that never answers, as offline with nothing cached
    val silent =
        object : SymptomRepository by journal() {
          override fun observeSymptoms(uid: String) =
              flow<List<SymptomEntry>> { awaitCancellation() }
        }

    assertTrue(viewModel(FollowedPatient(SYMPTOMS), symptoms = silent).uiState.value.isLoading)
    // Kept private, the journal isn't waited for
    assertFalse(viewModel(FollowedPatient(PLANNING), symptoms = silent).uiState.value.isLoading)
  }

  // ----- Failures -----

  @Test
  fun journalFails_givesAnError_andRetryLoadsIt() {
    val symptoms =
        journal(entry("new", now.minusHours(1))).apply { observeError = IllegalStateException() }
    val vm = viewModel(FollowedPatient(SYMPTOMS), symptoms = symptoms)
    assertTrue(vm.uiState.value.hasError)
    assertFalse(vm.uiState.value.isLoading)

    symptoms.observeError = null
    vm.retry()
    ShadowLooper.idleMainLooper()

    assertFalse(vm.uiState.value.hasError)
    assertEquals("new", vm.uiState.value.latestEntry?.entry?.id)
  }

  @Test
  fun planningFails_givesAnError_andRetryLoadsIt() {
    val planning = planning(appointment("chemo", now.plusDays(2))).apply { fail = true }
    val vm = viewModel(FollowedPatient(PLANNING), planning)
    assertTrue(vm.uiState.value.hasError)
    assertFalse(vm.uiState.value.isLoading)

    planning.fail = false
    vm.retry()
    ShadowLooper.idleMainLooper()

    assertFalse(vm.uiState.value.hasError)
    assertEquals("chemo", vm.uiState.value.nextUp?.id)
  }

  @Test
  fun retry_stopsFollowingTheOldDay_beforeFollowingItAgain() {
    val patient = FollowedPatient(PLANNING)
    val vm = viewModel(patient, planning(appointment("chemo", now.plusDays(2))))
    val following = patient.circle.activeObservations

    repeat(3) {
      vm.retry()
      ShadowLooper.idleMainLooper()
    }

    assertEquals(following, patient.circle.activeObservations)
    assertEquals("chemo", vm.uiState.value.nextUp?.id)
  }

  @Test
  fun accessFlowThrows_givesAnError_neverALoadingLeftRunning_andRetryRecovers() {
    val patient = FollowedPatient(PLANNING)
    patient.circle.observeError = IllegalStateException("unexpected")
    val vm = viewModel(patient, planning(appointment("chemo", now.plusDays(2))))

    assertTrue(vm.uiState.value.hasError)
    assertFalse(vm.uiState.value.isLoading)

    patient.circle.observeError = null
    vm.retry()
    ShadowLooper.idleMainLooper()

    assertFalse(vm.uiState.value.hasError)
    assertEquals("chemo", vm.uiState.value.nextUp?.id)
  }

  // ----- Switching patient -----

  @Test
  fun twoPatients_eachViewModelShowsOnlyItsOwnPatient() {
    val patient = FollowedPatient(PLANNING)
    // A second patient who keeps their planning private from the same caregiver
    patient.circle.setMembers(
        "other",
        listOf(
            CareCircleMember(uid = "caregiver", firstName = "Sam", permissions = setOf(SYMPTOMS))
        ),
    )
    val planning = planning(appointment("chemo", now.plusDays(2)))

    val first = viewModel(patient, planning)
    val second = viewModel(patient, planning, patientUid = "other")

    assertEquals("chemo", first.uiState.value.nextUp?.id)
    assertEquals(SHOWN, first.uiState.value.sections[PLANNING])
    assertNull(second.uiState.value.nextUp)
    assertEquals(PRIVATE, second.uiState.value.sections[PLANNING])
    assertEquals(listOf("patient"), planning.owners.distinct())
  }

  // ----- Keeping today current -----

  /** Runs [test] with `viewModelScope` on the test's virtual time. */
  @OptIn(ExperimentalCoroutinesApi::class)
  private fun runTestOnVirtualMain(test: suspend TestScope.() -> Unit) = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      test()
    } finally {
      Dispatchers.resetMain()
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun followToday_movesNextUpOnWhenItStarts() = runTestOnVirtualMain {
    val clock = VirtualClock(testScheduler, now.toInstant(zone), zone)
    val planning =
        planning(appointment("blood", now.withHour(10)), appointment("chemo", now.withHour(12)))
    val vm = viewModel(FollowedPatient(PLANNING), planning, clock)
    val following = vm.followToday()
    runCurrent()
    assertEquals("blood", vm.uiState.value.nextUp?.id)

    advanceTimeBy(1.hours)
    runCurrent()

    assertEquals("chemo", vm.uiState.value.nextUp?.id)
    following.cancel()
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun followToday_reloadsTheNewDayAtMidnight() = runTestOnVirtualMain {
    val lateEvening = now.withHour(23).withMinute(59)
    val planning = planning()
    val clock = VirtualClock(testScheduler, lateEvening.toInstant(zone), zone)
    val vm = viewModel(FollowedPatient(PLANNING), planning, clock)
    val following = vm.followToday()
    runCurrent()
    assertEquals(1, planning.ranges.size)

    advanceTimeBy(1.minutes)
    runCurrent()

    val tomorrow = now.toLocalDate().plusDays(1)
    assertEquals(tomorrow, vm.uiState.value.now.toLocalDate())
    assertEquals(tomorrow, planning.ranges.last().startDateInclusive)
    assertEquals(2, planning.ranges.size)
    following.cancel()
  }
}
