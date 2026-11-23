// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview.caregiver

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.domain.carecircle.FollowedPatient
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
   * The Overview of "caregiver", who follows [patient]: the access comes from [patient] and the
   * planning from [planningOf], the same patient unless a test makes the two disagree.
   */
  private fun viewModel(
      patient: FollowedPatient,
      planning: FakePlanningRepository = planning(),
      clock: Clock = fixedClock,
      planningOf: FollowedPatient = patient,
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
    for ((shared, expected) in cases) {
      val state = viewModel(FollowedPatient(*shared.toTypedArray())).uiState.value
      assertEquals(
          shared.toString(),
          listOf(PLANNING, PRESCRIPTIONS, SYMPTOMS).zip(expected).toMap(),
          state.sections,
      )
      assertEquals(
          shared.toString(),
          state.sections.filterValues { it == PRIVATE }.keys.toList(),
          state.privateSections,
      )
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
  fun accessNotConfirmedYet_isLoading_withNoSection() {
    val patient = FollowedPatient(PLANNING).apply { isUnconfirmed() }

    val state = viewModel(patient).uiState.value

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

  // ----- Failures -----

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
