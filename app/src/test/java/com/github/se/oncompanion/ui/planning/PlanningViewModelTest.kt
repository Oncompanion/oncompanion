// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.planning

import androidx.lifecycle.SavedStateHandle
import com.github.se.oncompanion.domain.planning.AppointmentPlanningSource
import com.github.se.oncompanion.domain.planning.PatientProfiles
import com.github.se.oncompanion.domain.planning.PlanningRepositoryProvider
import com.github.se.oncompanion.domain.planning.followedPlanning
import com.github.se.oncompanion.domain.planning.ownPlanning
import com.github.se.oncompanion.domain.planning.signedInPatient
import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentType
import com.github.se.oncompanion.model.appointment.FakeAppointmentRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.planning.FakePlanningRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import com.github.se.oncompanion.ui.VirtualClock
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Checks calendar selection and patient isolation across agenda updates and failures. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanningViewModelTest {
  /** Signing out clears a loaded patient's agenda even after its listener has failed. */
  @Test fun signOutAfterReadFailureClearsPatientAgenda() = checkAccountChangeAfterReadFailure(true)

  /** A new account loads its own agenda without retrying the previous account's failed read. */
  @Test
  fun accountSwitchAfterReadFailureLoadsNewPatientAgenda() =
      checkAccountChangeAfterReadFailure(false)

  /** A retry that fails again keeps the shown entries only until the account changes. */
  @Test
  fun failedRetryThenAccountSwitch_neverShowsThePreviousAccountsEntries() =
      runTest(dispatcher) {
        val auth = FakeAuthRepository { AuthUser(it) }
        auth.signInWithGoogle("a")
        val appointments = FakeAppointmentRepository()
        val time = clock.instant()
        appointments.seed("a", Appointment("a", "Patient A", time, AppointmentType.CONSULTATION))
        val vm =
            PlanningViewModel(
                ownPlanning(AppointmentPlanningSource(appointments), auth),
                clock,
                zone,
                SavedStateHandle(),
            )
        drain()
        appointments.failActiveObservations(IllegalStateException("Read failed"))
        drain()
        // The retry fails too, keeping Patient A on screen while it lasts
        appointments.observeError = IllegalStateException("Still failing")
        vm.retry()
        drain()
        assertTrue(vm.uiState.value.hasError)
        assertEquals("Patient A", vm.uiState.value.items.single().title)

        auth.signInWithGoogle("b")
        drain()

        assertTrue(vm.uiState.value.items.none { it.title == "Patient A" })
      }

  private fun checkAccountChangeAfterReadFailure(signOut: Boolean) =
      runTest(dispatcher) {
        val auth = FakeAuthRepository { AuthUser(it) }
        auth.signInWithGoogle("a")
        val appointments = FakeAppointmentRepository()
        val time = clock.instant()
        appointments.seed("a", Appointment("a", "Patient A", time, AppointmentType.CONSULTATION))
        appointments.seed("b", Appointment("b", "Patient B", time, AppointmentType.CONSULTATION))
        val vm =
            PlanningViewModel(
                ownPlanning(AppointmentPlanningSource(appointments), auth),
                clock,
                zone,
                SavedStateHandle(),
            )
        drain()
        assertEquals("Patient A", vm.uiState.value.items.single().title)
        appointments.failActiveObservations(IllegalStateException("Read failed"))
        drain()
        assertTrue(vm.uiState.value.hasError)
        if (signOut) auth.signOut() else auth.signInWithGoogle("b")
        drain()
        assertFalse(vm.uiState.value.hasError)
        assertFalse(vm.uiState.value.isLoading)
        if (signOut) assertTrue(vm.uiState.value.items.isEmpty())
        else assertEquals("Patient B", vm.uiState.value.items.single().title)
        appointments.seed(
            "a",
            Appointment("old", "Old patient update", time, AppointmentType.CONSULTATION),
        )
        drain()
        assertTrue(
            vm.uiState.value.items.none {
              it.title == "Old patient update" || it.title == "Patient A"
            }
        )
      }

  private val zone = ZoneId.of("Europe/Zurich")
  private val clock = Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), zone)

  private val originalPlanningRepository = PlanningRepositoryProvider.repository
  private val originalAuthRepository = AuthRepositoryProvider.repository
  private val originalProfileRepository = UserProfileRepositoryProvider.repository

  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setMain() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun resetMain() {
    PlanningRepositoryProvider.repository = originalPlanningRepository
    AuthRepositoryProvider.repository = originalAuthRepository
    UserProfileRepositoryProvider.repository = originalProfileRepository
    Dispatchers.resetMain()
  }

  /** Default dependencies use the providers and continue observing account changes. */
  @Test
  fun defaultRepositoriesFollowProviderSessionChanges() =
      runTest(dispatcher) {
        val auth = FakeAuthRepository { AuthUser(it) }
        val appointments = FakeAppointmentRepository()
        appointments.seed(
            "a",
            Appointment("a", "Patient A", clock.instant(), AppointmentType.CONSULTATION),
        )
        appointments.seed(
            "b",
            Appointment("b", "Patient B", clock.instant(), AppointmentType.CONSULTATION),
        )
        PlanningRepositoryProvider.repository = AppointmentPlanningSource(appointments)
        AuthRepositoryProvider.repository = auth
        UserProfileRepositoryProvider.repository = PatientProfiles
        auth.signInWithGoogle("a")
        val vm =
            PlanningViewModel(clock = clock, zoneId = zone, savedStateHandle = SavedStateHandle())
        drain()
        assertEquals("Patient A", vm.uiState.value.items.single().title)
        auth.signInWithGoogle("b")
        drain()
        assertEquals("Patient B", vm.uiState.value.items.single().title)
        auth.signOut()
        drain()
        assertTrue(vm.uiState.value.items.isEmpty())
        assertFalse(vm.uiState.value.isLoading)
      }

  /** Navigation can construct Planning with only the saved state and system time defaults. */
  @Test
  fun savedStateOnlyConstructorUsesSystemDateAndZone() =
      runTest(dispatcher) {
        PlanningRepositoryProvider.repository = FakePlanningRepository()
        AuthRepositoryProvider.repository = FakeAuthRepository()
        val before = LocalDate.now()
        val vm = PlanningViewModel(savedStateHandle = SavedStateHandle())
        val after = LocalDate.now()
        drain()
        assertEquals(ZoneId.systemDefault(), vm.zoneId)
        assertTrue(vm.uiState.value.today in listOf(before, after))
        assertFalse(vm.uiState.value.isLoading)
      }

  private fun drain() {
    dispatcher.scheduler.runCurrent()
  }

  private fun item(id: String, instant: String) =
      PlanningItem(
          PlanningSource.Appointment(id),
          PlanningTiming.Timed(Instant.parse(instant)),
          "Consultation",
      )

  @Test
  fun selectsTodayAndNavigatesAcrossYearBoundary() =
      runTest(dispatcher) {
        val repository = FakePlanningRepository()
        val vm =
            PlanningViewModel(
                ownPlanning(repository),
                clock,
                zone,
                SavedStateHandle(),
            )
        drain()
        assertEquals(LocalDate.of(2026, 10, 2), vm.uiState.value.selectedDate)
        assertEquals(LocalDate.of(2026, 9, 28), vm.uiState.value.weekStart)
        assertFalse(vm.uiState.value.isLoading)
        vm.selectDate(LocalDate.of(2026, 12, 31))
        vm.nextPeriod()
        drain()
        assertEquals(LocalDate.of(2027, 1, 7), vm.uiState.value.selectedDate)
        vm.previousPeriod()
        vm.goToToday()
        drain()
        assertEquals(LocalDate.of(2026, 10, 2), vm.uiState.value.selectedDate)
      }

  @Test
  fun groupsInDisplayZoneAndOrdersEqualTimesByIdentity() =
      runTest(dispatcher) {
        val repository = FakePlanningRepository()
        repository.items.value =
            listOf(
                item("b", "2026-10-02T08:00:00Z"),
                item("a", "2026-10-02T08:00:00Z"),
                item("early", "2026-10-01T22:30:00Z"),
                item("tomorrow", "2026-10-02T22:00:00Z"),
                item("outside", "2026-11-02T08:00:00Z"),
            )
        val vm =
            PlanningViewModel(
                ownPlanning(repository),
                clock,
                zone,
                SavedStateHandle(),
            )
        drain()
        assertTrue(vm.uiState.value.untimedItems.isEmpty())
        assertEquals(vm.uiState.value.items, vm.uiState.value.scheduledItems)
        assertEquals(
            listOf("early", "a", "b"),
            vm.uiState.value.items.map { (it.source as PlanningSource.Appointment).appointmentId },
        )
        vm.selectDate(LocalDate.of(2026, 10, 3))
        assertEquals(
            listOf("tomorrow"),
            vm.uiState.value.items.map { (it.source as PlanningSource.Appointment).appointmentId },
        )
        vm.selectDate(LocalDate.of(2026, 10, 4))
        assertTrue(vm.uiState.value.items.isEmpty())
        assertTrue(vm.uiState.value.untimedItems.isEmpty())
        assertTrue(vm.uiState.value.scheduledItems.isEmpty())
      }

  @Test
  fun errorCanBeRetried() =
      runTest(dispatcher) {
        val repository = FakePlanningRepository().apply { fail = true }
        val vm =
            PlanningViewModel(
                ownPlanning(repository),
                clock,
                zone,
                SavedStateHandle(),
            )
        drain()
        assertTrue(vm.uiState.value.hasError)
        assertFalse(vm.uiState.value.isLoading)
        repository.fail = false
        vm.retry()
        drain()
        assertFalse(vm.uiState.value.hasError)
        assertFalse(vm.uiState.value.isLoading)
      }

  @Test
  fun weekRangeAccountsForDaylightSaving() =
      runTest(dispatcher) {
        val repository = FakePlanningRepository()
        val vm =
            PlanningViewModel(
                ownPlanning(repository),
                clock,
                zone,
                SavedStateHandle(),
            )
        vm.selectView(PlanningView.WEEK)
        vm.selectDate(LocalDate.of(2026, 10, 25))
        drain()
        val range = repository.ranges.last()
        assertEquals(Instant.parse("2026-10-18T22:00:00Z"), range.startInclusive)
        assertEquals(Instant.parse("2026-10-25T23:00:00Z"), range.endExclusive)
      }

  @Test
  fun navigationStopsAtSupportedYearBoundaries() =
      runTest(dispatcher) {
        val vm =
            PlanningViewModel(
                ownPlanning(FakePlanningRepository()),
                clock,
                zone,
                SavedStateHandle(),
            )
        val first = LocalDate.of(1, 1, 1)
        vm.selectDate(first)
        vm.previousPeriod()
        assertEquals(first, vm.uiState.value.selectedDate)
        assertFalse(vm.uiState.value.canGoToPreviousPeriod)
        val last = LocalDate.of(9999, 12, 31)
        vm.selectDate(last)
        vm.nextPeriod()
        assertEquals(last, vm.uiState.value.selectedDate)
        assertFalse(vm.uiState.value.canGoToNextPeriod)
        drain()
      }

  @Test
  fun cachedItemsSurviveFlowFailureAndFailedRetry() =
      runTest(dispatcher) {
        val cached = item("cached", "2026-10-02T08:00:00Z")
        var reads = 0
        val retryFailure = CompletableDeferred<Unit>()
        val repository =
            FakePlanningRepository().apply {
              observation = {
                flow {
                  if (reads++ == 0) emit(listOf(cached)) else retryFailure.await()
                  throw IllegalStateException("Read failed")
                }
              }
            }
        val vm =
            PlanningViewModel(
                ownPlanning(repository),
                clock,
                zone,
                SavedStateHandle(),
            )
        drain()
        assertEquals(listOf(cached), vm.uiState.value.items)
        assertTrue(vm.uiState.value.hasError)
        vm.retry()
        assertEquals(listOf(cached), vm.uiState.value.items)
        assertTrue(vm.uiState.value.isLoading)
        retryFailure.complete(Unit)
        drain()
        assertEquals(listOf(cached), vm.uiState.value.items)
        assertTrue(vm.uiState.value.hasError)
        assertFalse(vm.uiState.value.isLoading)
      }

  @Test
  fun initialSelectionIsSavedBeforeAnyCalendarAction() =
      runTest(dispatcher) {
        val saved = SavedStateHandle()
        PlanningViewModel(
            ownPlanning(FakePlanningRepository()),
            clock,
            zone,
            saved,
        )
        drain()
        assertEquals("2026-10-02", saved.get<String>("planning_selected_date"))
      }

  @Test
  fun restoresSelectedDateAndObservesItsWeek() =
      runTest(dispatcher) {
        val saved = SavedStateHandle()
        val original =
            PlanningViewModel(
                ownPlanning(FakePlanningRepository()),
                clock,
                zone,
                saved,
            )
        val selected = LocalDate.of(2027, 1, 7)
        original.selectDate(selected)
        val restoredState =
            SavedStateHandle(
                mapOf("planning_selected_date" to saved.get<String>("planning_selected_date"))
            )
        val repository = FakePlanningRepository()
        val restored =
            PlanningViewModel(
                ownPlanning(repository),
                clock,
                zone,
                restoredState,
            )
        drain()
        assertEquals(selected, restored.uiState.value.selectedDate)
        assertEquals(LocalDate.of(2027, 1, 4), restored.uiState.value.weekStart)
        assertEquals(LocalDate.of(2026, 10, 2), restored.uiState.value.today)
        assertEquals(1, repository.ranges.size)
      }

  @Test
  fun invalidSavedDatesFallBackToToday() =
      runTest(dispatcher) {
        listOf("invalid", "0000-01-01", "+10000-01-01").forEach { value ->
          val vm =
              PlanningViewModel(
                  ownPlanning(FakePlanningRepository()),
                  clock,
                  zone,
                  SavedStateHandle(mapOf("planning_selected_date" to value)),
              )
          assertEquals(LocalDate.of(2026, 10, 2), vm.uiState.value.selectedDate)
        }
        drain()
      }

  @Test
  fun midnightRefreshKeepsSelectionAndDoesNotRestartObservation() =
      runTest(dispatcher) {
        var now = Instant.parse("2026-10-02T21:59:00Z")
        val movingClock =
            object : Clock() {
              override fun getZone(): ZoneId = zone

              override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)

              override fun instant(): Instant = now
            }
        val repository = FakePlanningRepository()
        val vm =
            PlanningViewModel(
                ownPlanning(repository),
                movingClock,
                zone,
                SavedStateHandle(),
            )
        drain()
        now = Instant.parse("2026-10-02T22:01:00Z")
        vm.refreshToday()
        assertEquals(LocalDate.of(2026, 10, 3), vm.uiState.value.today)
        assertEquals(LocalDate.of(2026, 10, 2), vm.uiState.value.selectedDate)
        assertEquals(1, repository.ranges.size)
        vm.goToToday()
        assertEquals(LocalDate.of(2026, 10, 3), vm.uiState.value.selectedDate)
        assertEquals(1, repository.ranges.size)
      }

  @Test
  fun followTodayRefreshesTheTodayMarkerRightAway() =
      runTest(dispatcher) {
        var now = Instant.parse("2026-10-02T10:00:00Z")
        val movingClock =
            object : Clock() {
              override fun getZone(): ZoneId = zone

              override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)

              override fun instant(): Instant = now
            }
        val vm =
            PlanningViewModel(
                ownPlanning(FakePlanningRepository()),
                movingClock,
                zone,
                SavedStateHandle(),
            )
        drain()

        // The date changed while Planning was in the background
        now = Instant.parse("2026-10-04T10:00:00Z")
        val following = vm.followToday()
        runCurrent()

        assertEquals(LocalDate.of(2026, 10, 4), vm.uiState.value.today)
        following.cancel()
      }

  @Test
  fun followTodayMovesTheTodayMarkerAtMidnightAndKeepsTheSelection() =
      runTest(dispatcher) {
        // 23:59 in Zurich on Friday 2 October 2026
        val clock = VirtualClock(testScheduler, Instant.parse("2026-10-02T21:59:00Z"), zone)
        val repository = FakePlanningRepository()
        val vm =
            PlanningViewModel(
                ownPlanning(repository),
                clock,
                zone,
                SavedStateHandle(),
            )
        drain()
        val following = vm.followToday()
        runCurrent()
        assertEquals(LocalDate.of(2026, 10, 2), vm.uiState.value.today)

        advanceTimeBy(1.minutes)
        runCurrent()

        assertEquals(LocalDate.of(2026, 10, 3), vm.uiState.value.today)
        assertEquals(LocalDate.of(2026, 10, 2), vm.uiState.value.selectedDate)
        assertEquals(1, repository.ranges.size)
        following.cancel()
      }

  @Test
  fun mixedEntriesKeepMedicationsFirstAndObserveChangesWithinTheWeek() =
      runTest(dispatcher) {
        val date = LocalDate.of(2026, 10, 2)
        fun medication(id: String, day: LocalDate = date, title: String = "Medication") =
            PlanningItem(
                PlanningSource.Medication("prescription", id),
                PlanningTiming.DateOnly(day),
                title,
                frequency = "Twice a day",
            )
        val first = medication("a", title = "A medication")
        val tie = medication("b", title = "A medication")
        val last = medication("c", title = "Z medication")
        val nextDay = first.copy(timing = PlanningTiming.DateOnly(date.plusDays(1)))
        val appointment = item("early", "2026-10-01T22:01:00Z")
        val event =
            PlanningItem(
                PlanningSource.Event("event"),
                PlanningTiming.Timed(Instant.parse("2026-10-02T12:00:00Z")),
                "Workshop",
            )
        val repository =
            FakePlanningRepository().apply {
              items.value =
                  listOf(
                      event,
                      last,
                      tie,
                      appointment,
                      first,
                      nextDay,
                      medication("outside", date.plusWeeks(1)),
                  )
            }
        val vm =
            PlanningViewModel(
                ownPlanning(repository),
                clock,
                zone,
                SavedStateHandle(),
            )
        drain()
        assertEquals(listOf(first, tie, last), vm.uiState.value.untimedItems)
        assertEquals(listOf(appointment, event), vm.uiState.value.scheduledItems)
        assertEquals(listOf(first, tie, last, appointment, event), vm.uiState.value.items)
        assertEquals(1, repository.ranges.size)
        repository.items.value = listOf(first.copy(frequency = "As recorded"), event)
        drain()
        assertEquals("As recorded", vm.uiState.value.items.first().frequency)
        assertEquals(2, vm.uiState.value.items.size)
        assertEquals(1, repository.ranges.size)
        repository.items.value = listOf(first, nextDay)
        drain()
        vm.selectDate(date.plusDays(1))
        assertEquals(listOf(nextDay), vm.uiState.value.untimedItems)
        assertTrue(vm.uiState.value.scheduledItems.isEmpty())
        assertEquals(listOf(nextDay), vm.uiState.value.items)
        assertEquals(1, repository.ranges.size)
      }

  /** Keeps medication names in locale order and identical titles in stable identity order. */
  @Test
  fun medicationNamesFollowLocaleOrderWithStableIdentityTies() =
      runTest(dispatcher) {
        val previousLocale = Locale.getDefault()
        try {
          // Fix the locale so case and accent ordering is independent of the test machine.
          Locale.setDefault(Locale.FRENCH)
          fun medication(id: String, title: String) =
              PlanningItem(
                  PlanningSource.Medication("prescription", id),
                  PlanningTiming.DateOnly(LocalDate.of(2026, 10, 2)),
                  title,
              )
          val lower = medication("a", "aspirine")
          val upper = medication("b", "Aspirine")
          val plain = medication("c", "eclair")
          val accented = medication("d", "éclair")
          val tie = medication("e", "éclair")
          val last = medication("f", "Zinc")
          val repository =
              FakePlanningRepository().apply {
                items.value = listOf(last, tie, upper, accented, plain, lower)
              }
          val vm =
              PlanningViewModel(
                  ownPlanning(repository),
                  clock,
                  zone,
                  SavedStateHandle(),
              )
          drain()
          assertEquals(
              listOf(lower, upper, plain, accented, tie, last),
              vm.uiState.value.untimedItems,
          )
          assertTrue(vm.uiState.value.scheduledItems.isEmpty())
        } finally {
          Locale.setDefault(previousLocale)
        }
      }

  @Test
  fun dateOnlyEntriesStayOnTheirDayInAnotherDisplayZone() =
      runTest(dispatcher) {
        val date = LocalDate.of(2026, 10, 2)
        val medication =
            PlanningItem(
                PlanningSource.Medication("p", "m"),
                PlanningTiming.DateOnly(date),
                "Medication",
            )
        val repository = FakePlanningRepository().apply { items.value = listOf(medication) }
        val vm =
            PlanningViewModel(
                ownPlanning(repository),
                clock,
                ZoneId.of("Pacific/Honolulu"),
                SavedStateHandle(),
            )
        drain()
        assertEquals(listOf(medication), vm.uiState.value.items)
        vm.selectDate(date.minusDays(1))
        assertTrue(vm.uiState.value.items.isEmpty())
        assertTrue(vm.uiState.value.untimedItems.isEmpty())
        assertTrue(vm.uiState.value.scheduledItems.isEmpty())
      }

  /** Default construction observes repositories supplied by the refactored providers. */
  @Test
  fun defaultsUsePlanningAndAuthProviders() =
      runTest(dispatcher) {
        val oldPlanning =
            com.github.se.oncompanion.domain.planning.PlanningRepositoryProvider.repository
        val oldAuth = com.github.se.oncompanion.model.auth.AuthRepositoryProvider.repository
        val repository = FakePlanningRepository()
        repository.items.value = listOf(item("provider", "2026-10-02T10:00:00Z"))
        try {
          com.github.se.oncompanion.domain.planning.PlanningRepositoryProvider.repository =
              repository
          com.github.se.oncompanion.model.auth.AuthRepositoryProvider.repository = signedInPatient()
          UserProfileRepositoryProvider.repository = PatientProfiles
          val vm =
              PlanningViewModel(clock = clock, zoneId = zone, savedStateHandle = SavedStateHandle())
          drain()
          assertEquals(repository.items.value, vm.uiState.value.items)
        } finally {
          com.github.se.oncompanion.domain.planning.PlanningRepositoryProvider.repository =
              oldPlanning
          com.github.se.oncompanion.model.auth.AuthRepositoryProvider.repository = oldAuth
        }
      }

  // ----- Caregiver mode (US-20) -----

  @Test
  fun caregiver_planningShared_showsThePatientsEntries() =
      runTest(dispatcher) {
        val repository = FakePlanningRepository()
        repository.items.value = listOf(item("chemo", "2026-10-02T09:00:00Z"))
        val vm =
            PlanningViewModel(
                followedPlanning(repository, setOf(CarePermission.PLANNING)),
                clock,
                zone,
                SavedStateHandle(),
                patientUid = "patient",
            )
        drain()

        assertEquals(repository.items.value, vm.uiState.value.items)
        assertEquals(
            mapOf(LocalDate.of(2026, 10, 2) to repository.items.value),
            vm.uiState.value.itemsByDay,
        )
        assertFalse(vm.uiState.value.isNotShared)
        assertTrue(repository.owners.all { it == "patient" })
      }

  @Test
  fun caregiver_planningNotShared_isNotShared_withNoEntries() =
      runTest(dispatcher) {
        val repository = FakePlanningRepository()
        repository.items.value = listOf(item("chemo", "2026-10-02T09:00:00Z"))
        val vm =
            PlanningViewModel(
                followedPlanning(repository, setOf(CarePermission.SYMPTOMS)),
                clock,
                zone,
                SavedStateHandle(),
                patientUid = "patient",
            )
        drain()

        assertTrue(vm.uiState.value.isNotShared)
        assertTrue(vm.uiState.value.items.isEmpty())
        assertTrue(vm.uiState.value.itemsByDay.isEmpty())
        assertFalse(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.hasError)
        assertTrue(repository.owners.isEmpty())
      }

  // ----- A caregiver adding appointments (US-41) -----

  @Test
  fun addIsOffered_inTheUsersOwnPlanning_andToACaregiverOnlyWhenThePatientLetsThem() =
      runTest(dispatcher) {
        val repository = FakePlanningRepository()
        val own = PlanningViewModel(ownPlanning(repository), clock, zone, SavedStateHandle())
        drain()
        assertTrue(own.uiState.value.canAdd)

        val cases =
            listOf(
                emptySet<CarePermission>() to false,
                setOf(CarePermission.SYMPTOMS) to false,
                setOf(CarePermission.PLANNING) to true,
            )
        for ((canAdd, expected) in cases) {
          val vm =
              PlanningViewModel(
                  followedPlanning(
                      repository,
                      setOf(CarePermission.PLANNING, CarePermission.SYMPTOMS),
                      canAdd,
                  ),
                  clock,
                  zone,
                  SavedStateHandle(),
                  patientUid = "patient",
              )
          // Not before the patient's planning shows
          assertFalse(vm.uiState.value.canAdd)
          drain()
          assertEquals("$canAdd", expected, vm.uiState.value.canAdd)
        }
      }

  // ----- Loading the period of each view (#575) -----

  /** Each view loads its own days, once: List its window, the grids their period. */
  @Test
  fun eachViewLoadsItsPeriodOnce() =
      runTest(dispatcher) {
        val repository = FakePlanningRepository()
        val vm = PlanningViewModel(ownPlanning(repository), clock, zone, SavedStateHandle())
        drain()
        // view -> first and last loaded days, or null when no new read is needed
        val cases =
            listOf(
                PlanningView.LIST to null,
                PlanningView.WEEK to ("2026-09-28" to "2026-10-05"),
                PlanningView.THREE_DAYS to ("2026-10-02" to "2026-10-05"),
                PlanningView.MONTH to ("2026-09-28" to "2026-11-02"),
                PlanningView.LIST to ("2026-09-04" to "2026-11-28"),
            )
        for ((view, days) in cases) {
          val reads = repository.ranges.size
          vm.selectView(view)
          drain()
          assertEquals(view, vm.uiState.value.view)
          assertEquals(LocalDate.of(2026, 10, 2), vm.uiState.value.selectedDate)
          assertFalse(vm.uiState.value.isLoading)
          if (days == null) {
            assertEquals("$view", reads, repository.ranges.size)
          } else {
            assertEquals("$view", reads + 1, repository.ranges.size)
            val range = repository.ranges.last()
            assertEquals(
                "$view",
                days,
                range.startDateInclusive.toString() to range.endDateExclusive.toString(),
            )
          }
        }
      }

  /** Three days stay put under a day picked inside them, and move by three days. */
  @Test
  fun threeDaysStayPutWhileThePickedDayIsInside() =
      runTest(dispatcher) {
        val repository = FakePlanningRepository()
        val vm = PlanningViewModel(ownPlanning(repository), clock, zone, SavedStateHandle())
        vm.selectView(PlanningView.THREE_DAYS)
        drain()
        val reads = repository.ranges.size
        // action -> selected day, first day of the three
        val cases =
            listOf<Triple<String, () -> Unit, Pair<String, String>>>(
                Triple(
                    "pick inside",
                    { vm.selectDate(LocalDate.of(2026, 10, 4)) },
                    "2026-10-04" to "2026-10-02",
                ),
                Triple("next", vm::nextPeriod, "2026-10-07" to "2026-10-05"),
                Triple("previous", vm::previousPeriod, "2026-10-04" to "2026-10-02"),
                Triple(
                    "pick outside",
                    { vm.selectDate(LocalDate.of(2026, 10, 10)) },
                    "2026-10-10" to "2026-10-10",
                ),
                Triple("today", vm::goToToday, "2026-10-02" to "2026-10-02"),
            )
        for ((name, action, expected) in cases) {
          action()
          drain()
          val state = vm.uiState.value
          assertEquals(
              name,
              expected,
              state.selectedDate.toString() to state.period.start.toString(),
          )
          assertEquals(name, PlanningView.THREE_DAYS, state.view)
        }
        // Every action but the pick inside loaded new days
        assertEquals(reads + 4, repository.ranges.size)
      }

  /**
   * The month loads its grid's days, and lists each entry under its day in the display zone, in
   * agenda order, as entries change; picking a day of the next month moves to that month.
   */
  @Test
  fun monthGroupsEveryLoadedEntryByDay() =
      runTest(dispatcher) {
        fun medication(id: String, title: String) =
            PlanningItem(
                PlanningSource.Medication("prescription", id),
                PlanningTiming.DateOnly(LocalDate.of(2026, 10, 2)),
                title,
            )
        val zinc = medication("z", "Zinc")
        val aspirine = medication("a", "Aspirine")
        val consultation = item("consultation", "2026-10-02T08:00:00Z")
        // Monday 28 September is in October's grid
        val september = item("september", "2026-09-28T06:00:00Z")
        // 23:30 UTC on 31 October is 00:30 on 1 November in Zurich
        val november = item("november", "2026-10-31T23:30:00Z")
        val outside = item("outside", "2026-11-02T08:00:00Z")
        val repository =
            FakePlanningRepository().apply {
              items.value = listOf(outside, november, consultation, zinc, september, aspirine)
            }
        val vm = PlanningViewModel(ownPlanning(repository), clock, zone, SavedStateHandle())
        vm.selectView(PlanningView.MONTH)
        drain()

        assertEquals(
            mapOf(
                LocalDate.of(2026, 9, 28) to listOf(september),
                LocalDate.of(2026, 10, 2) to listOf(aspirine, zinc, consultation),
                LocalDate.of(2026, 11, 1) to listOf(november),
            ),
            vm.uiState.value.itemsByDay,
        )
        assertEquals(
            listOf(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 11, 1)),
            vm.uiState.value.itemsByDay.keys.toList(),
        )
        assertEquals(listOf(aspirine, zinc), vm.uiState.value.untimedItems)
        assertEquals(listOf(consultation), vm.uiState.value.scheduledItems)

        // A change within the month shows without a new read
        val reads = repository.ranges.size
        val later = item("later", "2026-10-20T08:00:00Z")
        repository.items.value = repository.items.value + later
        drain()
        assertEquals(listOf(later), vm.uiState.value.itemsByDay[LocalDate.of(2026, 10, 20)])
        assertEquals(reads, repository.ranges.size)

        vm.selectDate(LocalDate.of(2026, 11, 1))
        drain()
        assertEquals(
            PlanningPeriod(PlanningView.MONTH, LocalDate.of(2026, 11, 1)),
            vm.uiState.value.period,
        )
        assertEquals(reads + 1, repository.ranges.size)
        assertEquals(listOf(november), vm.uiState.value.scheduledItems)
      }

  /** The view and its period survive the activity being recreated, and load once. */
  @Test
  fun restoresTheViewAndThePeriod() =
      runTest(dispatcher) {
        val saved = SavedStateHandle()
        val original = PlanningViewModel(ownPlanning(FakePlanningRepository()), clock, zone, saved)
        original.selectView(PlanningView.THREE_DAYS)
        original.selectDate(LocalDate.of(2026, 10, 3))
        drain()
        val repository = FakePlanningRepository()
        val restored =
            PlanningViewModel(
                ownPlanning(repository),
                clock,
                zone,
                SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }),
            )
        drain()

        assertEquals(LocalDate.of(2026, 10, 3), restored.uiState.value.selectedDate)
        assertEquals(
            PlanningPeriod(PlanningView.THREE_DAYS, LocalDate.of(2026, 10, 2)),
            restored.uiState.value.period,
        )
        assertEquals(LocalDate.of(2026, 10, 2), repository.ranges.single().startDateInclusive)
      }

  /**
   * A saved selection that can't be used falls back to List and today, as a whole; a save from the
   * previous version, with only the date, still restores that date in List.
   */
  @Test
  fun invalidSavedSelectionFallsBackToListAndToday() =
      runTest(dispatcher) {
        val today = LocalDate.of(2026, 10, 2)
        val saved = LocalDate.of(2026, 10, 20)
        val fallback = today to PlanningPeriod.of(PlanningView.LIST, today)
        // saved date, view and period start -> selected day and period shown
        val cases =
            listOf(
                Triple("2026-10-20", "AGENDA", "2026-10-19") to fallback,
                Triple("2026-10-20", "MONTH", "not a date") to fallback,
                // Not the 1st of a month
                Triple("2026-10-20", "MONTH", "2026-10-02") to fallback,
                // Doesn't hold the saved day
                Triple("2026-10-20", "THREE_DAYS", "2026-09-01") to fallback,
                Triple("2026-10-20", "MONTH", null) to fallback,
                Triple("2026-10-20", null, "2026-10-01") to fallback,
                Triple("invalid", "MONTH", "2026-10-01") to fallback,
                Triple("2026-10-20", "THREE_DAYS", "2026-10-19") to
                    (saved to PlanningPeriod(PlanningView.THREE_DAYS, saved.minusDays(1))),
                // Saved by the previous version: the date only
                Triple("2026-10-20", null, null) to
                    (saved to PlanningPeriod.of(PlanningView.LIST, saved)),
            )
        for ((input, expected) in cases) {
          val (date, view, start) = input
          val vm =
              PlanningViewModel(
                  ownPlanning(FakePlanningRepository()),
                  clock,
                  zone,
                  SavedStateHandle(
                      mapOf(
                          "planning_selected_date" to date,
                          "planning_view" to view,
                          "planning_period_start" to start,
                      )
                  ),
              )
          val state = vm.uiState.value
          assertEquals("$input", expected, state.selectedDate to state.period)
        }
        drain()
      }

  /**
   * Every state the screen gets is whole: the selected day's agenda comes from the loaded days'
   * entries, never from the previous day, period or window, as the selection, the window and the
   * entries change.
   */
  @Test
  fun everyEmittedStateMatchesItsSelection() =
      runTest(dispatcher) {
        val repository =
            FakePlanningRepository().apply {
              items.value =
                  listOf(
                      item("friday", "2026-10-02T08:00:00Z"),
                      item("saturday", "2026-10-03T08:00:00Z"),
                      item("monday", "2026-10-05T08:00:00Z"),
                  )
            }
        val vm = PlanningViewModel(ownPlanning(repository), clock, zone, SavedStateHandle())
        val states = mutableListOf<PlanningUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          vm.uiState.collect { states += it }
        }
        drain()
        // Same week, then the next week, then the month, then a change and a retry
        vm.selectDate(LocalDate.of(2026, 10, 3))
        vm.selectDate(LocalDate.of(2026, 10, 5))
        drain()
        // The List's window grows both ways, then is rebuilt far away
        vm.showLater()
        drain()
        vm.showEarlier()
        drain()
        vm.selectDate(LocalDate.of(2027, 6, 1))
        drain()
        vm.selectView(PlanningView.MONTH)
        drain()
        repository.items.value = repository.items.value + item("later", "2027-06-20T08:00:00Z")
        drain()
        // Back from the grid to a day of the List
        vm.openInList(LocalDate.of(2026, 10, 5), emptySet())
        drain()
        vm.retry()
        drain()

        assertTrue(states.size > 10)
        for (state in states) {
          assertEquals("$state", state.itemsByDay[state.selectedDate].orEmpty(), state.items)
          val range = state.loadedRange(zone)
          assertTrue(
              "$state",
              state.itemsByDay.keys.all {
                it >= range.startDateInclusive && it < range.endDateExclusive
              },
          )
        }
        assertEquals(
            listOf("monday"),
            states.last().items.map { (it.source as PlanningSource.Appointment).appointmentId },
        )
      }

  // ----- The List's window (#660) -----

  /**
   * The List opens 4 weeks before the day and 8 after, grows by 4 weeks either way with one read
   * each, keeps its window for a day inside it, builds a new one for a day outside it, and never
   * goes before year 1.
   */
  @Test
  fun listWindowOpensAroundTheDayAndGrowsFourWeeksAtATime() =
      runTest(dispatcher) {
        val repository = FakePlanningRepository()
        val vm = PlanningViewModel(ownPlanning(repository), clock, zone, SavedStateHandle())
        drain()
        fun window() = vm.uiState.value.listWindow.let { "${it.start}..${it.end}" }
        assertEquals("2026-09-04..2026-11-28", window())
        assertEquals(vm.uiState.value.listWindow.range(zone), repository.ranges.single())
        // action -> window after it, and whether it read again
        val cases =
            listOf<Triple<String, () -> Unit, Pair<String, Boolean>>>(
                Triple("later", vm::showLater, "2026-09-04..2026-12-26" to true),
                Triple("earlier", vm::showEarlier, "2026-08-07..2026-12-26" to true),
                Triple(
                    "pick inside",
                    { vm.selectDate(LocalDate.of(2026, 12, 20)) },
                    "2026-08-07..2026-12-26" to false,
                ),
                Triple(
                    "pick outside",
                    { vm.selectDate(LocalDate.of(2027, 3, 1)) },
                    "2027-02-01..2027-04-27" to true,
                ),
                Triple(
                    "first year",
                    { vm.selectDate(LocalDate.of(1, 1, 3)) },
                    "0001-01-01..0001-03-01" to true,
                ),
                Triple("nothing before year 1", vm::showEarlier, "0001-01-01..0001-03-01" to false),
                Triple("today", vm::goToToday, "2026-09-04..2026-11-28" to true),
            )
        for ((name, action, expected) in cases) {
          val reads = repository.ranges.size
          action()
          drain()
          val (days, readsAgain) = expected
          assertEquals(name, days, window())
          assertEquals(name, if (readsAgain) reads + 1 else reads, repository.ranges.size)
          assertEquals(name, vm.uiState.value.listWindow.range(zone), repository.ranges.last())
          assertFalse(name, vm.uiState.value.isLoading)
        }

        // The grids load their period: growing the List there does nothing
        vm.selectView(PlanningView.WEEK)
        drain()
        val window = vm.uiState.value.listWindow
        val reads = repository.ranges.size
        vm.showLater()
        vm.showEarlier()
        drain()
        assertEquals(window, vm.uiState.value.listWindow)
        assertEquals(reads, repository.ranges.size)
      }

  /**
   * While other days load, the entries of the days that stay loaded are shown, as loading more:
   * when the List grows, and when the view changes over days the List already has (S1 of #660).
   */
  @Test
  fun entriesOfDaysStillLoadedStayWhileTheOthersLoad() =
      runTest(dispatcher) {
        val friday = item("friday", "2026-10-02T08:00:00Z")
        val monday = item("monday", "2026-10-05T08:00:00Z")
        val later = item("later", "2026-12-10T08:00:00Z")
        val earlier = item("earlier", "2026-08-20T08:00:00Z")
        var answer = CompletableDeferred<Unit>()
        val repository =
            FakePlanningRepository().apply {
              // The first read answers at once; the next ones wait for the test
              observation = {
                flow {
                  if (ranges.size > 1) answer.await()
                  emit(listOf(earlier, friday, monday, later))
                }
              }
            }
        val vm = PlanningViewModel(ownPlanning(repository), clock, zone, SavedStateHandle())
        drain()
        assertEquals(listOf(friday, monday), vm.uiState.value.itemsByDay.values.flatten())
        // step -> entries shown while it loads, then once it has answered
        val cases =
            listOf<Triple<String, () -> Unit, Pair<List<PlanningItem>, List<PlanningItem>>>>(
                Triple(
                    "later",
                    vm::showLater,
                    listOf(friday, monday) to listOf(friday, monday, later),
                ),
                Triple(
                    "earlier",
                    vm::showEarlier,
                    listOf(friday, monday, later) to listOf(earlier, friday, monday, later),
                ),
                // The week is within the List's days: its entries stay, cut to the week
                Triple(
                    "list to week",
                    { vm.selectView(PlanningView.WEEK) },
                    listOf(friday) to listOf(friday),
                ),
                Triple(
                    "week to list",
                    { vm.selectView(PlanningView.LIST) },
                    listOf(friday) to listOf(earlier, friday, monday, later),
                ),
            )
        for ((name, step, expected) in cases) {
          val (whileLoading, answered) = expected
          answer = CompletableDeferred()
          step()
          drain()
          var state = vm.uiState.value
          assertTrue(name, state.isLoading && state.isLoadingMore)
          assertEquals(name, whileLoading, state.itemsByDay.values.flatten())
          assertEquals(name, listOf(friday), state.items)
          answer.complete(Unit)
          drain()
          state = vm.uiState.value
          assertFalse(name, state.isLoading || state.isLoadingMore)
          assertEquals(name, answered, state.itemsByDay.values.flatten())
        }
      }

  /**
   * Loading more needs entries read for some of the same days ([PlanningUiState.readDays]);
   * otherwise it is a first load, which shows no entries. An empty planning that grows is loading
   * more too.
   */
  @Test
  fun loadingMoreOnlyOverDaysAlreadyRead() =
      runTest(dispatcher) {
        var answer = CompletableDeferred<Unit>()
        var fails = false
        val repository =
            FakePlanningRepository().apply {
              observation = {
                flow {
                  answer.await()
                  if (fails) throw IllegalStateException("Read failed")
                  emit(emptyList())
                }
              }
            }
        val vm = PlanningViewModel(ownPlanning(repository), clock, zone, SavedStateHandle())
        fun readDays() =
            vm.uiState.value.readDays?.let { "${it.startDateInclusive}..${it.endDateExclusive}" }
        drain()
        // Nothing read yet
        assertTrue(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.isLoadingMore)
        // step, whether its read fails -> the days read while it loads (loading more if any)
        val cases =
            listOf<Triple<String, () -> Unit, Pair<Boolean, String?>>>(
                Triple("first read fails", {}, true to null),
                Triple("retry without anything read", vm::retry, false to null),
                Triple("empty planning grows", vm::showLater, false to "2026-09-04..2026-11-28"),
                Triple("loading more fails", vm::showLater, true to "2026-09-04..2026-12-26"),
                Triple("retry after it", vm::retry, false to "2026-09-04..2026-12-26"),
                Triple(
                    "grows twice before an answer",
                    {
                      vm.showLater()
                      vm.showLater()
                    },
                    false to "2026-09-04..2027-01-23",
                ),
                Triple(
                    "new days only",
                    { vm.selectDate(LocalDate.of(2027, 6, 1)) },
                    false to null,
                ),
                // The jump to October dropped June's entries before its read answered
                Triple(
                    "back to days a jump dropped",
                    {
                      vm.selectDate(LocalDate.of(2026, 10, 2))
                      vm.selectDate(LocalDate.of(2027, 6, 1))
                    },
                    false to null,
                ),
                Triple(
                    "list to week",
                    { vm.selectView(PlanningView.WEEK) },
                    false to "2027-05-31..2027-06-07",
                ),
                Triple("next week", vm::nextPeriod, false to null),
            )
        for ((name, step, expected) in cases) {
          val (failsNext, readWhileLoading) = expected
          step()
          drain()
          assertTrue(name, vm.uiState.value.isLoading)
          assertEquals(name, readWhileLoading, readDays())
          assertEquals(name, readWhileLoading != null, vm.uiState.value.isLoadingMore)
          fails = failsNext
          answer.complete(Unit)
          drain()
          answer = CompletableDeferred()
          assertFalse(name, vm.uiState.value.isLoading || vm.uiState.value.isLoadingMore)
          // A failure keeps the days read; an answer reads all the days loaded
          val read =
              if (failsNext) readWhileLoading
              else
                  vm.uiState.value.loadedRange(zone).let {
                    "${it.startDateInclusive}..${it.endDateExclusive}"
                  }
          assertEquals(name, read, readDays())
        }
      }

  /**
   * After another account signs in, or the patient stops sharing, nothing is read anymore: the next
   * read is a first load, not loading more.
   */
  @Test
  fun loadingMoreStartsOverAfterAnAccessChange() =
      runTest(dispatcher) {
        // Another account: its read doesn't answer yet
        val auth = FakeAuthRepository { AuthUser(it) }
        auth.signInWithGoogle("a")
        val repository = FakePlanningRepository()
        val own = PlanningViewModel(ownPlanning(repository, auth), clock, zone, SavedStateHandle())
        drain()
        assertNotNull(own.uiState.value.readDays)
        repository.observation = { flow { awaitCancellation() } }
        auth.signInWithGoogle("b")
        drain()
        own.showLater()
        assertTrue(own.uiState.value.isLoading)
        assertNull(own.uiState.value.readDays)
        assertFalse(own.uiState.value.isLoadingMore)

        // The patient stops sharing their planning with the caregiver
        val circle = FakeCareCircleRepository()
        val followed =
            PlanningViewModel(
                followedPlanning(
                    FakePlanningRepository(),
                    setOf(CarePermission.PLANNING),
                    circle = circle,
                ),
                clock,
                zone,
                SavedStateHandle(),
                patientUid = "patient",
            )
        drain()
        assertNotNull(followed.uiState.value.readDays)
        circle.setMembers(
            "patient",
            listOf(
                CareCircleMember(
                    uid = "caregiver",
                    firstName = "Sam",
                    permissions = setOf(CarePermission.SYMPTOMS),
                )
            ),
        )
        drain()
        assertTrue(followed.uiState.value.isNotShared)
        followed.showLater()
        assertNull(followed.uiState.value.readDays)
        assertFalse(followed.uiState.value.isLoadingMore)
      }

  /**
   * Offline, appointments come from the phone's copy: one saved there ten weeks ahead shows once
   * the window reaches it, without an error.
   */
  @Test
  fun offlineTheWiderWindowShowsTheSavedAppointments() =
      runTest(dispatcher) {
        val appointments = FakeAppointmentRepository(clock)
        val vm =
            PlanningViewModel(
                ownPlanning(AppointmentPlanningSource(appointments)),
                clock,
                zone,
                SavedStateHandle(),
            )
        val scan =
            Appointment(
                "",
                "Scan",
                Instant.parse("2026-12-11T09:00:00Z"),
                AppointmentType.CONSULTATION,
            )
        appointments.addAppointment("patient", scan)
        drain()
        assertTrue(vm.uiState.value.itemsByDay.isEmpty())

        vm.showLater()
        drain()

        val state = vm.uiState.value
        assertEquals(
            listOf("Scan"),
            state.itemsByDay[LocalDate.of(2026, 12, 11)].orEmpty().map { it.title },
        )
        assertFalse(state.hasError)
        assertFalse(state.isLoading)
      }

  /**
   * From a grid, openInList switches to List on the day, with a window around it, and keeps the
   * jump in the state until the List plays it: a newer jump replaces it, the List rebuilt meanwhile
   * still finds it, leaving the List drops it, and nothing about it is saved.
   */
  @Test
  fun openInListKeepsTheJumpUntilTheListPlaysIt() =
      runTest(dispatcher) {
        val saved = SavedStateHandle()
        val repository = FakePlanningRepository()
        val vm = PlanningViewModel(ownPlanning(repository), clock, zone, saved)
        vm.selectView(PlanningView.MONTH)
        drain()
        val states = mutableListOf<PlanningUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          vm.uiState.collect { states += it }
        }
        val day = LocalDate.of(2027, 6, 15)
        val keys =
            setOf(item("a", "2027-06-15T08:00:00Z").key, item("b", "2027-06-15T08:00:00Z").key)
        val first = PlanningListJump(day, keys)

        // Every state holding the jump already shows List on its day
        vm.openInList(day, keys)
        drain()
        val withJump = states.filter { it.pendingJump != null }
        assertTrue(withJump.isNotEmpty())
        for (state in withJump) {
          assertEquals(PlanningView.LIST, state.view)
          assertEquals(day, state.selectedDate)
          assertTrue(day in state.listWindow)
        }
        assertEquals(first, vm.uiState.value.pendingJump)
        assertEquals(PlanningListWindow.around(day), vm.uiState.value.listWindow)
        assertEquals(PlanningListWindow.around(day).range(zone), repository.ranges.last())

        // A second jump before the first is played: only the latest is kept, without a new read
        val reads = repository.ranges.size
        val latest = PlanningListJump(day.plusWeeks(2), setOf("x"))
        vm.openInList(latest.date, latest.keys)
        drain()
        assertEquals(reads, repository.ranges.size)
        assertEquals(latest, vm.uiState.value.pendingJump)
        // The first one played late doesn't clear it
        vm.jumpShown(first)
        assertEquals(latest, vm.uiState.value.pendingJump)
        // It stays while the List grows or another day is picked in it, until played once
        vm.showLater()
        vm.selectDate(day)
        drain()
        assertEquals(latest, vm.uiState.value.pendingJump)
        vm.jumpShown(latest)
        assertNull(vm.uiState.value.pendingJump)

        // Leaving the List drops a jump not played
        vm.openInList(day, keys)
        vm.selectView(PlanningView.WEEK)
        drain()
        assertNull(vm.uiState.value.pendingJump)
        // Only the selection is saved
        assertEquals(
            setOf("planning_selected_date", "planning_view", "planning_period_start"),
            saved.keys(),
        )
        assertThrows(IllegalArgumentException::class.java) {
          vm.openInList(LocalDate.of(0, 12, 31), keys)
        }
      }

  /** After a rotation or process death, the List opens on the saved day, in a new window. */
  @Test
  fun restoredDayGetsANewWindowAroundIt() =
      runTest(dispatcher) {
        val saved = SavedStateHandle()
        val original = PlanningViewModel(ownPlanning(FakePlanningRepository()), clock, zone, saved)
        original.showLater()
        original.showLater()
        val day = LocalDate.of(2026, 12, 20)
        original.selectDate(day)
        drain()
        assertEquals(LocalDate.of(2027, 1, 23), original.uiState.value.listWindow.end)

        val repository = FakePlanningRepository()
        val restored =
            PlanningViewModel(
                ownPlanning(repository),
                clock,
                zone,
                SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }),
            )
        drain()

        assertEquals(day, restored.uiState.value.selectedDate)
        assertEquals(PlanningListWindow.around(day), restored.uiState.value.listWindow)
        assertEquals(PlanningListWindow.around(day).range(zone), repository.ranges.single())
      }
}
