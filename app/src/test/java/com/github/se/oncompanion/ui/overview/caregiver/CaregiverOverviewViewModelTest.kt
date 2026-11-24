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
import com.github.se.oncompanion.model.medication.FakeTakenDosesRepository
import com.github.se.oncompanion.model.medication.TakenDosesRepository
import com.github.se.oncompanion.model.planning.FakePlanningRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
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
   * planning from [planningOf], and the journal and the medications from [journalOf], the same
   * patient unless a test makes them disagree.
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
      medications: FakePlanningRepository = FakePlanningRepository(),
      doses: TakenDosesRepository = FakeTakenDosesRepository(),
  ) =
      CaregiverOverviewViewModel(
              patientUid = patientUid,
              authRepository = auth,
              profileRepository = profiles,
              observePlanning =
                  ObserveCarePlanning(planningOf.sectionAccess, planning, planningOf.circle),
              symptomRepository = symptoms,
              observeSection = ObserveCareSection(journalOf.sectionAccess),
              medications = medications,
              takenDoses = doses,
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
  fun dayStarted_onlyOnceSomethingPlannedTodayHasStarted() {
    // Planned items to whether the day has started, at 9:00
    val cases =
        listOf(
            emptyList<PlanningItem>() to false,
            listOf(appointment("blood", now.withHour(10))) to false,
            // Alex's only appointment was at 8:00: nothing is upcoming, but something was planned
            listOf(appointment("blood", now.withHour(8))) to true,
            // Starting right now counts as started
            listOf(appointment("blood", now)) to true,
            // Yesterday's appointment isn't today's
            listOf(appointment("blood", now.minusDays(1))) to false,
        )
    for ((items, started) in cases) {
      val state =
          viewModel(FollowedPatient(PLANNING), planning(*items.toTypedArray())).uiState.value
      assertEquals("$items", started, state.dayStarted)
    }
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
            // A strength for some symptoms only: the others have none, and a leftover overall
            // intensity isn't used for them
            entry("new", yesterday, intensity = 6, strengths = mapOf(FATIGUE to 4)) to
                (listOf(NAUSEA to null, FATIGUE to 4) to null),
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

    // The last one deleted too: the journal is empty, still shared, and nothing waits
    runBlocking { symptoms.deleteSymptom("patient", "old") }
    ShadowLooper.idleMainLooper()
    val state = vm.uiState.value
    assertNull(state.latestEntry)
    assertEquals(SHOWN, state.sections[SYMPTOMS])
    assertFalse(state.isLoading)
    assertFalse(state.hasError)
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

  // ----- Today's medications -----

  /** A medication of [day]'s, as the prescriptions give it to the Overview. */
  private fun medication(
      id: String,
      frequency: String?,
      day: LocalDate = now.toLocalDate(),
      confirmed: Boolean = true,
  ) =
      PlanningItem(
          PlanningSource.Medication("prescription", id),
          PlanningTiming.DateOnly(day),
          id.replaceFirstChar { it.uppercase() },
          frequency = frequency,
          dosage = "8 mg",
          isConfirmed = confirmed,
      )

  private fun medications(vararg items: PlanningItem) =
      FakePlanningRepository().apply { this.items.value = items.toList() }

  /** Doses the patient ticked on [day], by medication ID. */
  private fun ticked(vararg taken: Pair<String, Int>, day: LocalDate = now.toLocalDate()) =
      FakeTakenDosesRepository().apply {
        runBlocking { taken.forEach { (id, count) -> setTaken("patient", day, id, count) } }
      }

  /** A [FakeTakenDosesRepository] that counts whose doses are read, for which day. */
  private class CountingDoses(val fake: FakeTakenDosesRepository = FakeTakenDosesRepository()) :
      TakenDosesRepository by fake {
    val reads = mutableListOf<Pair<String, LocalDate>>()

    override fun observeDay(uid: String, date: LocalDate) =
        fake.observeDay(uid, date).also { reads += uid to date }
  }

  @Test
  fun medications_showTheirFrequencyAndTheDosesTicked_onlyConfirmedOnesOfToday() {
    val medications =
        medications(
            medication("ondansetron", "1-1-1"),
            medication("dexamethasone", "1-0-0"),
            medication("paracetamol", "si besoin"),
            // A caregiver added it: not part of the treatment until the patient confirms it
            medication("aprepitant", "1-0-0", confirmed = false),
            medication("lorazepam", "1-0-0", day = now.toLocalDate().plusDays(1)),
        )

    val state =
        viewModel(
                FollowedPatient(PRESCRIPTIONS),
                medications = medications,
                doses = ticked("ondansetron" to 1, "dexamethasone" to 1),
            )
            .uiState
            .value

    assertEquals(
        listOf(
            Triple("Ondansetron 8 mg", 3, 1),
            Triple("Dexamethasone 8 mg", 1, 1),
            // Taken only if needed: nothing to count
            Triple("Paracetamol 8 mg", null, 0),
        ),
        state.todayMedications.map { Triple(it.name, it.dosesPerDay, it.taken) },
    )
    assertFalse(state.isLoading)
    // Only the patient's prescriptions, for today: never their appointments nor a whole year
    assertEquals(listOf("patient"), medications.owners)
    assertEquals(listOf(setOf(PRESCRIPTIONS)), medications.sections)
    assertEquals(
        listOf(PlanningRange(now.toLocalDate(), now.toLocalDate().plusDays(1), zone)),
        medications.ranges,
    )
  }

  @Test
  fun prescriptionsPrivate_neitherTheMedicationsNorTheDosesAreRead() {
    val medications = medications(medication("ondansetron", "1-1-1"))
    val doses = CountingDoses()

    val state =
        viewModel(FollowedPatient(PLANNING), medications = medications, doses = doses).uiState.value

    assertEquals(PRIVATE, state.sections[PRESCRIPTIONS])
    assertTrue(state.todayMedications.isEmpty())
    assertFalse(state.isLoading)
    assertTrue(medications.owners.isEmpty())
    assertTrue(doses.reads.isEmpty())
  }

  @Test
  fun dosesCantBeRead_theMedicationsShowWithoutDoses_neverLoadingNorAnError() {
    // A read that fails at once, and one whose listener throws when asked: neither loses the card
    val failures =
        listOf<TakenDosesRepository>(
            FakeTakenDosesRepository(readFailure = IllegalStateException("Permission denied")),
            object : TakenDosesRepository by FakeTakenDosesRepository() {
              override fun observeDay(uid: String, date: LocalDate) =
                  throw IllegalStateException("No listener")
            },
        )
    for (doses in failures) {
      val state =
          viewModel(
                  FollowedPatient(PRESCRIPTIONS),
                  medications = medications(medication("ondansetron", "1-1-1")),
                  doses = doses,
              )
              .uiState
              .value

      assertEquals(listOf("Ondansetron 8 mg"), state.todayMedications.map { it.name })
      // Without the doses, nothing shows as ticked: no bar, no "0 of 3"
      assertNull(state.todayMedications.single().dosesPerDay)
      assertFalse(state.isLoading)
      assertFalse(state.hasError)
    }
  }

  @Test
  fun medicationsCantBeRead_giveAnError_andRetryLoadsThem() {
    val medications = medications(medication("ondansetron", "1-1-1")).apply { fail = true }
    val vm = viewModel(FollowedPatient(PRESCRIPTIONS), medications = medications)
    assertTrue(vm.uiState.value.hasError)
    assertFalse(vm.uiState.value.isLoading)

    medications.fail = false
    vm.retry()
    ShadowLooper.idleMainLooper()

    assertFalse(vm.uiState.value.hasError)
    assertEquals(listOf("Ondansetron 8 mg"), vm.uiState.value.todayMedications.map { it.name })
  }

  @Test
  fun aDoseTickedWhileOpen_showsAtOnce() {
    val doses = FakeTakenDosesRepository()
    val vm =
        viewModel(
            FollowedPatient(PRESCRIPTIONS),
            medications = medications(medication("ondansetron", "1-1-1")),
            doses = doses,
        )
    assertEquals(0, vm.uiState.value.todayMedications.single().taken)

    runBlocking { doses.setTaken("patient", now.toLocalDate(), "ondansetron", 2) }
    ShadowLooper.idleMainLooper()

    assertEquals(2, vm.uiState.value.todayMedications.single().taken)
  }

  @Test
  fun prescriptionsWithdrawnWhileOpen_dropTheMedicationsAtOnce_evenBeforeTheirListenerFollows() {
    val patient = FollowedPatient(PRESCRIPTIONS)
    // The medications' own listener still sees them shared, as when it answers later
    val stillShared = FollowedPatient(PRESCRIPTIONS)
    val vm =
        viewModel(
            patient,
            journalOf = stillShared,
            medications = medications(medication("ondansetron", "1-1-1")),
        )
    assertEquals(1, vm.uiState.value.todayMedications.size)

    patient.shares(PLANNING)
    ShadowLooper.idleMainLooper()

    assertEquals(PRIVATE, vm.uiState.value.sections[PRESCRIPTIONS])
    assertTrue(vm.uiState.value.todayMedications.isEmpty())
    assertFalse(vm.uiState.value.isLoading)
  }

  @Test
  fun prescriptionsStartedSharingWhileOpen_staysLoading_untilTheirListenerFollows() {
    val patient = FollowedPatient(PLANNING)
    val notYetShared = FollowedPatient(PLANNING)
    val vm =
        viewModel(
            patient,
            journalOf = notYetShared,
            medications = medications(medication("ondansetron", "1-1-1")),
        )

    patient.shares(PLANNING, PRESCRIPTIONS)
    ShadowLooper.idleMainLooper()
    assertEquals(SHOWN, vm.uiState.value.sections[PRESCRIPTIONS])
    assertTrue(vm.uiState.value.isLoading)

    notYetShared.shares(PLANNING, PRESCRIPTIONS)
    ShadowLooper.idleMainLooper()
    assertFalse(vm.uiState.value.isLoading)
    assertEquals(1, vm.uiState.value.todayMedications.size)
  }

  @Test
  fun twoPatients_neverShowTheOtherPatientsTicks() {
    val patient = FollowedPatient(PRESCRIPTIONS)
    patient.circle.setMembers(
        "other",
        listOf(
            CareCircleMember(
                uid = "caregiver",
                firstName = "Sam",
                permissions = setOf(PRESCRIPTIONS),
            )
        ),
    )
    // Both take ondansetron; only the first ticked it
    val medications =
        FakePlanningRepository().apply {
          itemsOf["patient"] = MutableStateFlow(listOf(medication("ondansetron", "1-1-1")))
          itemsOf["other"] = MutableStateFlow(listOf(medication("ondansetron", "1-1-1")))
        }
    val doses = CountingDoses(ticked("ondansetron" to 2))

    val first = viewModel(patient, medications = medications, doses = doses)
    val second = viewModel(patient, medications = medications, doses = doses, patientUid = "other")

    assertEquals(2, first.uiState.value.todayMedications.single().taken)
    assertEquals(0, second.uiState.value.todayMedications.single().taken)
    assertEquals(setOf("patient", "other"), doses.reads.map { it.first }.toSet())
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
    assertTrue(vm.uiState.value.dayStarted)
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

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun followToday_atMidnight_theTicksStartOverForTheNewDay() = runTestOnVirtualMain {
    val today = now.toLocalDate()
    val tomorrow = today.plusDays(1)
    val lateEvening = now.withHour(23).withMinute(59)
    val clock = VirtualClock(testScheduler, lateEvening.toInstant(zone), zone)
    // Ticked today; the prescription goes on tomorrow
    val doses = CountingDoses(ticked("ondansetron" to 3))
    val vm =
        viewModel(
            FollowedPatient(PRESCRIPTIONS),
            clock = clock,
            medications =
                medications(
                    medication("ondansetron", "1-1-1"),
                    medication("ondansetron", "1-1-1", day = tomorrow),
                ),
            doses = doses,
        )
    val following = vm.followToday()
    runCurrent()
    assertEquals(3, vm.uiState.value.todayMedications.single().taken)

    advanceTimeBy(1.minutes)
    runCurrent()

    assertEquals(0, vm.uiState.value.todayMedications.single().taken)
    assertEquals("patient" to tomorrow, doses.reads.last())
    following.cancel()
  }
}
