// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.notification

import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleInvite
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CareCircleMemberDetails
import com.github.se.oncompanion.model.carecircle.FakeCareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.medication.PrescriptionConfirmation
import com.github.se.oncompanion.model.notification.CircleNotification
import com.github.se.oncompanion.model.notification.FakeNotificationRepository
import com.github.se.oncompanion.model.notification.NotificationKind
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CircleSheetViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val zurich = ZoneId.of("Europe/Zurich")
  private val today = LocalDate.of(2026, 10, 3)
  /** Saturday 3 October 2026 at 09:30 in Zurich. */
  private val now = at(today, 9, 30)
  private val clock = Clock.fixed(now, zurich)
  private val uid = "alex"

  private val notifications = FakeNotificationRepository(clock = { now })
  private val circle = FakeCareCircleRepository()
  private val invites = FakeCareCircleInviteRepository()
  private val medications = FakeMedicationRepository()
  private lateinit var auth: FakeAuthRepository

  private fun at(date: LocalDate, hour: Int, minute: Int): Instant =
      date.atTime(hour, minute).atZone(zurich).toInstant()

  private fun member(uid: String, firstName: String) = CareCircleMember(uid, firstName)

  private fun invite(code: String, firstName: String, createdAt: Instant) =
      CareCircleInvite(
          inviteCode = code,
          ownerUid = uid,
          ownerFirstName = "Alex",
          details = CareCircleMemberDetails(firstName),
          createdAt = createdAt,
      )

  private fun prescription(
      id: String,
      confirmation: PrescriptionConfirmation = PrescriptionConfirmation.PENDING,
  ) =
      Prescription(
          id = id,
          prescribedBy = "Dr. Martin",
          prescribedOn = today,
          medications =
              listOf(
                  Medication(
                      id = "m-$id",
                      prescriptionId = id,
                      name = "Ondansetron",
                      startDate = today,
                  )
              ),
          addedBy = "marc",
          addedByName = "Marc",
          confirmation = confirmation,
      )

  private fun added(itemId: String, at: Instant, seen: Boolean = false, by: String = "Marc") =
      CircleNotification(
          kind = NotificationKind.PRESCRIPTION_ADDED,
          itemId = itemId,
          actorUid = by.lowercase(),
          actorFirstName = by,
          createdAt = at,
          seen = seen,
      )

  private val created = mutableListOf<CircleSheetViewModel>()

  private fun viewModel(clock: Clock = this.clock) =
      CircleSheetViewModel(
              notificationRepository = notifications,
              careCircleRepository = circle,
              inviteRepository = invites,
              medicationRepository = medications,
              authRepository = auth,
              clock = clock,
          )
          .also { created += it }

  /**
   * Runs [test] on virtual time, then stops the ViewModels it created: one following the day waits
   * for the next midnight forever, so the test would never run out of work. They are stopped even
   * when the test fails, or the failure would hang instead of being reported.
   */
  private fun runViewModelTest(test: suspend TestScope.() -> Unit) =
      runTest(dispatcher) {
        try {
          test()
        } finally {
          created.forEach { it.viewModelScope.cancel() }
        }
      }

  private val CircleSheetViewModel.circleState: CircleSheetUiState.Circle
    get() = uiState.value as CircleSheetUiState.Circle

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    auth = FakeAuthRepository(onSignIn = { AuthUser(uid = it) })
    runBlocking { auth.signInWithGoogle(uid) }
    circle.setMembers(uid, listOf(member("marc", "Marc"), member("sophie", "Sophie")))
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun startsLoading_thenShowsTheCircle_withItsNotificationsByDay_newestFirst() = runViewModelTest {
    medications.seed(uid, prescription("p1"))
    medications.seed(uid, prescription("p2"))
    notifications.deliver(uid, added("p1", at(today, 8, 5)))
    notifications.deliver(uid, added("p2", at(today.minusDays(1), 18, 30), by = "Sophie"))
    notifications.deliver(uid, added("p3", at(today, 7, 50), by = "Sophie"))
    val vm = viewModel()
    assertEquals(CircleSheetUiState.Loading, vm.uiState.value)

    runCurrent()

    val state = vm.circleState
    assertEquals(listOf("Marc", "Sophie"), state.joined)
    assertEquals(today, state.today)
    assertEquals(listOf(today, today.minusDays(1)), state.days.map { it.date })
    assertEquals(listOf("p1", "p3"), state.days[0].cards.map { it.notification.itemId })
    // The time in the phone's time zone, as the card shows it
    assertEquals(LocalTime.of(8, 5), state.days[0].cards[0].time)
    assertEquals("p2", state.days[1].cards.single().notification.itemId)
  }

  @Test
  fun aNotificationJustAfterMidnight_isUnderThatDay_inThePhonesTimeZone() = runViewModelTest {
    // 00:30 in Zurich is still the day before in UTC
    notifications.deliver(uid, added("p1", at(today, 0, 30)))
    val vm = viewModel()
    runCurrent()

    val day = vm.circleState.days.single()
    assertEquals(today, day.date)
    assertEquals(LocalTime.of(0, 30), day.cards.single().time)
  }

  @Test
  fun eachCard_showsItsPrescriptionAsItIsNow_orNoneOnceItIsGone() = runViewModelTest {
    medications.seed(uid, prescription("p1"))
    notifications.deliver(uid, added("p1", at(today, 8, 5)))
    val vm = viewModel()
    runCurrent()
    assertEquals(
        PrescriptionConfirmation.PENDING,
        vm.circleState.days[0].cards[0].prescription?.confirmation,
    )

    // The patient confirms it elsewhere: the card follows
    medications.seed(uid, prescription("p1", PrescriptionConfirmation.CONFIRMED))
    runCurrent()
    assertEquals(
        PrescriptionConfirmation.CONFIRMED,
        vm.circleState.days[0].cards[0].prescription?.confirmation,
    )

    // Deleted: the notification stays, with nothing to open
    medications.deletePrescription(uid, "p1")
    runCurrent()
    assertNull(vm.circleState.days[0].cards[0].prescription)
  }

  @Test
  fun aPrescriptionThePatientRejects_showsAsRejected_untilTheCaregiverDeletesIt() =
      runViewModelTest {
        val waiting = prescription("p1")
        medications.seed(uid, waiting)
        notifications.deliver(uid, added("p1", at(today, 8, 5)))
        val vm = viewModel()
        runCurrent()

        medications.rejectPrescription(uid, waiting)
        runCurrent()
        val card = vm.circleState.days[0].cards[0]
        assertEquals(PrescriptionConfirmation.REJECTED, card.prescription?.confirmation)
        // Still with what it held, so the patient knows which one it was
        assertEquals(listOf("Ondansetron"), card.prescription?.medications?.map { it.name })

        // Marc saw the rejection and deleted it: now it is gone
        medications.deleteRejectedPrescription(uid, "p1")
        runCurrent()
        assertNull(vm.circleState.days[0].cards[0].prescription)
      }

  @Test
  fun invitations_stillValid_areListedAsInvited_expiredOnesAreNot() = runViewModelTest {
    invites.setInvites(
        listOf(
            invite("JULIEJULIE", "Julie", now.minus(Duration.ofDays(1))),
            invite("PAULPAULPA", "Paul", now.minus(Duration.ofDays(8))),
        )
    )
    val vm = viewModel()
    runCurrent()

    assertEquals(listOf("Julie"), vm.circleState.invited)
  }

  @Test
  fun nobodyInTheCircle_noInvitation_andNothingReceived_isNobodyYet() = runViewModelTest {
    circle.setMembers(uid, emptyList())
    val vm = viewModel()
    runCurrent()
    assertEquals(CircleSheetUiState.NobodyYet, vm.uiState.value)
  }

  @Test
  fun onlyAnInvitation_isTheCircle() = runViewModelTest {
    circle.setMembers(uid, emptyList())
    invites.setInvites(listOf(invite("JULIEJULIE", "Julie", now)))
    val vm = viewModel()
    runCurrent()
    assertEquals(listOf("Julie"), vm.circleState.invited)
  }

  @Test
  fun onlyANotification_fromSomeoneWhoLeftTheCircle_isTheCircle() = runViewModelTest {
    circle.setMembers(uid, emptyList())
    notifications.deliver(uid, added("p1", at(today, 8, 5)))
    val vm = viewModel()
    runCurrent()
    assertEquals(1, vm.circleState.days.single().cards.size)
  }

  @Test
  fun theSailboat_offersToAddSomeone_untilSomethingArrives_thenSaysWhenItIsNew() =
      runViewModelTest {
        circle.setMembers(uid, emptyList())
        val vm = viewModel()
        runCurrent()
        assertEquals(SailboatBadge.ADD, vm.uiState.value.sailboatBadge)

        notifications.deliver(uid, added("p1", at(today, 8, 5)))
        runCurrent()
        assertEquals(SailboatBadge.NEW, vm.uiState.value.sailboatBadge)

        notifications.markSeen(
            uid,
            listOf(CircleNotification.idOf(NotificationKind.PRESCRIPTION_ADDED, "p1")),
        )
        runCurrent()
        assertEquals(SailboatBadge.NONE, vm.uiState.value.sailboatBadge)
        // Nothing to point out while loading or after an error
        assertEquals(SailboatBadge.NONE, CircleSheetUiState.Loading.sailboatBadge)
        assertEquals(SailboatBadge.NONE, CircleSheetUiState.Error.sailboatBadge)
      }

  @Test
  fun hasUnseen_whileOneNotificationIsUnseen() = runViewModelTest {
    notifications.deliver(uid, added("p1", at(today, 8, 5), seen = true))
    val vm = viewModel()
    runCurrent()
    assertFalse(vm.uiState.value.hasUnseen)

    notifications.deliver(uid, added("p2", at(today, 9, 0)))
    runCurrent()
    assertTrue(vm.uiState.value.hasUnseen)

    notifications.markSeen(
        uid,
        listOf(CircleNotification.idOf(NotificationKind.PRESCRIPTION_ADDED, "p2")),
    )
    runCurrent()
    assertFalse(vm.uiState.value.hasUnseen)
    // Only a circle can hold something unseen
    assertFalse(CircleSheetUiState.Loading.hasUnseen)
  }

  @Test
  fun aReadFailure_isAnError_andRetryReadsAgain() = runViewModelTest {
    medications.readError = IOException("refused")
    val vm = viewModel()
    runCurrent()
    assertEquals(CircleSheetUiState.Error, vm.uiState.value)

    medications.readError = null
    vm.retry()
    // Loading again until the reads answer, not the old error
    assertEquals(CircleSheetUiState.Loading, vm.uiState.value)
    runCurrent()
    assertEquals(listOf("Marc", "Sophie"), vm.circleState.joined)
    // The first observation stopped: only one follows the account and writes the state
    assertEquals(1, auth.userObservers)
  }

  @Test
  fun aReadFailure_stillFollowsTheAccount_soTheNextOneSeesItsCircle() = runViewModelTest {
    medications.readError = IOException("refused")
    val vm = viewModel()
    runCurrent()
    assertEquals(CircleSheetUiState.Error, vm.uiState.value)

    // Without tapping Retry: another account signs in and reads its own circle
    medications.readError = null
    auth.signOut()
    circle.setMembers("sam", listOf(member("lea", "Léa")))
    auth.signInWithGoogle("sam")
    runCurrent()
    assertEquals(listOf("Léa"), vm.circleState.joined)
  }

  @Test
  fun aFailureToFollowTheSignedInUser_isAnError() = runViewModelTest {
    auth.observeError = IllegalStateException("auth")
    val vm = viewModel()
    runCurrent()
    assertEquals(CircleSheetUiState.Error, vm.uiState.value)
  }

  @Test
  fun signingOut_dropsTheAccountsCircle_andAnotherAccountSeesOnlyItsOwn() = runViewModelTest {
    notifications.deliver(uid, added("p1", at(today, 8, 5)))
    val vm = viewModel()
    runCurrent()
    assertEquals(1, vm.circleState.days.single().cards.size)

    auth.signOut()
    runCurrent()
    assertEquals(CircleSheetUiState.Error, vm.uiState.value)

    circle.setMembers("sam", listOf(member("lea", "Léa")))
    auth.signInWithGoogle("sam")
    runCurrent()
    assertEquals(listOf("Léa"), vm.circleState.joined)
    assertTrue(vm.circleState.days.isEmpty())
  }

  @Test
  fun onlyOlderDays_isNothingNewToday() = runViewModelTest {
    notifications.deliver(uid, added("p0", at(today.minusDays(1), 9, 0)))
    notifications.deliver(uid, added("p9", at(today.minusDays(2), 9, 0)))
    val vm = viewModel()
    runCurrent()
    assertTrue(vm.circleState.nothingToday)
  }

  @Test
  fun atMidnight_todayMovesOn_soTodaysCardsBecomeYesterdays() = runViewModelTest {
    // A clock the test moves past midnight
    var time = at(today, 23, 59)
    val movingClock =
        object : Clock() {
          override fun getZone() = zurich

          override fun withZone(zone: ZoneId?) = this

          override fun instant() = time
        }
    notifications.deliver(uid, added("p1", at(today, 8, 5)))
    val vm = viewModel(movingClock)
    // As the sheet does while it's on screen
    vm.followToday()
    runCurrent()
    assertEquals(today, vm.circleState.today)
    assertFalse(vm.circleState.nothingToday)

    time = at(today.plusDays(1), 0, 1)
    dispatcher.scheduler.advanceTimeBy(Duration.ofMinutes(2).toMillis())
    runCurrent()
    assertEquals(today.plusDays(1), vm.circleState.today)
    // Today's card is yesterday's now: nothing new today
    assertTrue(vm.circleState.nothingToday)
  }

  @Test
  fun anInvitationExpiringWhileTheSheetIsOpen_leaves_andAloneLeavesNobodyYet() = runViewModelTest {
    // A clock that moves with the test's time
    val movingClock =
        object : Clock() {
          override fun getZone() = zurich

          override fun withZone(zone: ZoneId?) = this

          override fun instant() = now.plusMillis(dispatcher.scheduler.currentTime)
        }
    circle.setMembers(uid, emptyList())
    // One minute left, and nothing else in the circle
    val sentAt = now.minus(CareCircleInvite.VALIDITY).plusSeconds(60)
    invites.setInvites(listOf(invite("JULIEJULIE", "Julie", sentAt)))
    val vm = viewModel(movingClock)
    runCurrent()
    assertEquals(listOf("Julie"), vm.circleState.invited)

    // Nothing is sent when it expires: the sheet still drops it, leaving nobody
    dispatcher.scheduler.advanceTimeBy(Duration.ofMinutes(2).toMillis())
    runCurrent()
    assertEquals(CircleSheetUiState.NobodyYet, vm.uiState.value)
  }

  @Test
  fun backAfterSleepingPastMidnight_todayMovesOnRightAway() = runViewModelTest {
    var time = at(today, 23, 0)
    val movingClock =
        object : Clock() {
          override fun getZone() = zurich

          override fun withZone(zone: ZoneId?) = this

          override fun instant() = time
        }
    notifications.deliver(uid, added("p1", at(today, 8, 5)))
    val vm = viewModel(movingClock)
    // Following while the screen is on: it read the day and waits for midnight
    val first = vm.followToday()
    runCurrent()
    // The sheet stops following when the screen goes off
    first.cancel()

    // Asleep overnight: the clock moves on, but no timer fires (Android's delay counts uptime)
    time = at(today.plusDays(1), 8, 0)
    // The screen comes back
    val following = vm.followToday()
    runCurrent()

    assertEquals(today.plusDays(1), vm.circleState.today)
    // Yesterday's card is no longer under "Today"
    assertEquals(today, vm.circleState.days.single().date)
    following.cancel()
  }

  @Test
  fun defaultRepositories_canBeCreatedWithoutFirebase() {
    // The providers' Firestore repositories only reach Firebase when first used
    CircleSheetViewModel(authRepository = FakeAuthRepository())
  }
}
