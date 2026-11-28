// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CareCircleMembership
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.FakeCareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.carecircle.JoinedCareCircle
import com.github.se.oncompanion.model.carecircle.LastOpened
import com.github.se.oncompanion.model.carecircle.LastOpenedInMemory
import com.github.se.oncompanion.model.symptom.FakeSymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val ZURICH: ZoneId = ZoneId.of("Europe/Zurich")

/** Tests of [WhoseCareViewModel]. */
@OptIn(ExperimentalCoroutinesApi::class)
class WhoseCareViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val caregiver = "caregiver"

  private lateinit var auth: FakeAuthRepository
  private lateinit var invites: FakeCareCircleInviteRepository
  private lateinit var circles: FakeCareCircleRepository
  private val journals = FakeSymptomRepository()
  private val lastOpened = LastOpenedInMemory()
  private val clock = TestClock(at(9, 0))

  /** The phone's clock, which a test moves on. */
  private class TestClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZURICH

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
  }

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    auth = FakeAuthRepository(onSignIn = { AuthUser(uid = caregiver) })
    runBlocking { auth.signInWithGoogle("token") }
    invites = FakeCareCircleInviteRepository()
    circles = FakeCareCircleRepository()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  /** [patientUid]'s circle with the caregiver in it, sharing [sections]. */
  private fun follow(patientUid: String, firstName: String, vararg sections: CarePermission) {
    circles.setMembers(
        patientUid,
        listOf(
            CareCircleMember(uid = caregiver, firstName = "Sam", permissions = sections.toSet())
        ),
    )
    invites.setJoinedCircles(
        caregiver,
        invites.joinedCircles(caregiver) + JoinedCareCircle(patientUid, firstName),
    )
  }

  private fun viewModel(
      circleRepository: CareCircleRepository = circles,
      symptomRepository: SymptomRepository = journals,
      store: LastOpened = lastOpened,
  ) = WhoseCareViewModel(auth, invites, circleRepository, symptomRepository, store, clock, ZURICH)

  /** [hour]:[minute] on the test's day, in Zurich. */
  private fun at(hour: Int, minute: Int): Instant =
      LocalDate.of(2026, 11, 28).atTime(hour, minute).atZone(ZURICH).toInstant()

  /**
   * [uid] logged [id] at [loggedAt] (the server's time; `null` until it has it), felt at [felt],
   * written by [addedBy] if a caregiver wrote it for them.
   */
  private fun logs(
      uid: String,
      id: String,
      loggedAt: Instant?,
      felt: Instant = loggedAt ?: at(8, 0),
      addedBy: String? = null,
  ) =
      journals.seed(
          uid,
          SymptomEntry(
              id = id,
              types = listOf(SymptomType.NAUSEA),
              strengths = mapOf(SymptomType.NAUSEA to 3),
              occurredAt = felt,
              createdAt = loggedAt,
              addedBy = addedBy,
          ),
      )

  private fun opened(uid: String): Instant? = runBlocking {
    lastOpened.observe(caregiver).first()[uid]
  }

  private val WhoseCareViewModel.newIds
    get() = state.news.filterValues { it.isNew }.keys

  private val WhoseCareViewModel.state
    get() = uiState.value

  @Test
  fun loading_untilTheListArrives() = runTest {
    val vm = viewModel()
    assertTrue(vm.state.isLoading)
    advanceUntilIdle()
    assertFalse(vm.state.isLoading)
  }

  @Test
  fun lists_thePatients_withWhatTheyShare_sortedByFirstName() = runTest {
    follow("p-paul", "Paul", CarePermission.PLANNING, CarePermission.PRESCRIPTIONS)
    follow("p-anne", "Anne", CarePermission.PLANNING, CarePermission.SYMPTOMS)

    val vm = viewModel()
    advanceUntilIdle()

    assertEquals(
        listOf(
            FollowedPatient(
                "p-anne",
                "Anne",
                setOf(CarePermission.PLANNING, CarePermission.SYMPTOMS),
            ),
            FollowedPatient(
                "p-paul",
                "Paul",
                setOf(CarePermission.PLANNING, CarePermission.PRESCRIPTIONS),
            ),
        ),
        vm.state.patients,
    )
    assertFalse(vm.state.hasError)
  }

  @Test
  fun noCircle_isAnEmptyList() = runTest {
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals(emptyList<FollowedPatient>(), vm.state.patients)
    assertFalse(vm.state.hasError)
  }

  @Test
  fun aPatientWhoRemovedTheCaregiver_isLeftOut() = runTest {
    follow("p-anne", "Anne", CarePermission.PLANNING)
    // Joined, but no member document anymore
    invites.setJoinedCircles(
        caregiver,
        invites.joinedCircles(caregiver) + JoinedCareCircle("p-gone", "Gone"),
    )

    val vm = viewModel()
    advanceUntilIdle()

    assertEquals(listOf("p-anne"), vm.state.patients.map { it.uid })
  }

  @Test
  fun anAccessThatCantBeRead_leavesOnlyThatPatientOut() = runTest {
    follow("p-anne", "Anne", CarePermission.PLANNING)
    follow("p-paul", "Paul", CarePermission.SYMPTOMS)
    // The rules deny Paul's member document before his circle disappears from the list
    val denying =
        object : CareCircleRepository by circles {
          override fun observeMembership(
              ownerUid: String,
              memberUid: String,
          ): Flow<CareCircleMembership> =
              if (ownerUid == "p-paul") flow { throw IllegalStateException("denied") }
              else circles.observeMembership(ownerUid, memberUid)
        }

    val vm = viewModel(denying)
    advanceUntilIdle()

    assertEquals(listOf("p-anne"), vm.state.patients.map { it.uid })
    assertFalse(vm.state.hasError)
    // Paul didn't answer: the list isn't confirmed
    assertFalse(vm.state.isConfirmed)
  }

  @Test
  fun theListIsConfirmed_onlyOnceEveryPatientAnswered() = runTest {
    // Joined Paul's circle again: the device still remembers the removal until the server answers
    follow("p-paul", "Paul", CarePermission.PLANNING)
    circles.unconfirmedMembers = setOf("p-paul" to "caregiver")
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals(emptyList<FollowedPatient>(), vm.state.patients)
    assertFalse(vm.state.isConfirmed)

    // The server confirms the member
    circles.unconfirmedMembers = emptySet()
    advanceUntilIdle()
    assertEquals(listOf("p-paul"), vm.state.patients.map { it.uid })
    assertTrue(vm.state.isConfirmed)

    // Paul removes the caregiver: confirmed, and nobody left
    circles.setMembers("p-paul", emptyList())
    advanceUntilIdle()
    assertEquals(emptyList<FollowedPatient>(), vm.state.patients)
    assertTrue(vm.state.isConfirmed)
  }

  @Test
  fun anEmptyListFromTheCache_isNotConfirmed_untilTheServerAnswers() = runTest {
    // A circle joined on another phone: this phone's copy doesn't know about it yet
    invites.joinedCirclesFromCache = true
    val vm = viewModel()
    advanceUntilIdle()
    assertFalse(vm.state.isLoading)
    assertEquals(emptyList<FollowedPatient>(), vm.state.patients)
    assertFalse(vm.state.isConfirmed)

    // The server answers with the circle
    follow("p-anne", "Anne", CarePermission.PLANNING)
    invites.joinedCirclesFromCache = false
    advanceUntilIdle()
    assertEquals(listOf("p-anne"), vm.state.patients.map { it.uid })
    assertTrue(vm.state.isConfirmed)
  }

  @Test
  fun aListFromTheCache_withPatients_isConfirmedByTheirAnswers() = runTest {
    // Offline with Anne known: each patient answers for themselves, as before
    follow("p-anne", "Anne", CarePermission.PLANNING)
    invites.joinedCirclesFromCache = true
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals(listOf("p-anne"), vm.state.patients.map { it.uid })
    assertTrue(vm.state.isConfirmed)

    // The server confirms the same list: Anne's access isn't read again
    invites.joinedCirclesFromCache = false
    advanceUntilIdle()
    assertEquals(listOf("p-anne"), circles.observedOwners)
  }

  @Test
  fun followsChanges_inTheSharedSections_andTheCircles() = runTest {
    follow("p-anne", "Anne", CarePermission.PLANNING)
    val vm = viewModel()
    advanceUntilIdle()

    // Anne shares symptoms too
    circles.setMembers(
        "p-anne",
        listOf(
            CareCircleMember(
                uid = caregiver,
                firstName = "Sam",
                permissions = setOf(CarePermission.PLANNING, CarePermission.SYMPTOMS),
            )
        ),
    )
    advanceUntilIdle()
    assertEquals(
        setOf(CarePermission.PLANNING, CarePermission.SYMPTOMS),
        vm.state.patients.single().sharedSections,
    )

    // Then removes the caregiver
    invites.setJoinedCircles(caregiver, emptyList())
    advanceUntilIdle()
    assertTrue(vm.state.patients.isEmpty())
  }

  @Test
  fun theCirclesCantBeRead_showsAnError_andRetryLoadsThem() = runTest {
    follow("p-anne", "Anne", CarePermission.PLANNING)
    invites.observeError = IllegalStateException("offline")
    val vm = viewModel()
    advanceUntilIdle()
    assertTrue(vm.state.hasError)
    assertFalse(vm.state.isLoading)

    invites.observeError = null
    vm.load()
    advanceUntilIdle()
    assertFalse(vm.state.hasError)
    assertEquals(listOf("p-anne"), vm.state.patients.map { it.uid })
  }

  @Test
  fun nobodySignedIn_asksToSignIn_ratherThanRetry() = runTest {
    auth.signOut()
    val vm = viewModel()
    advanceUntilIdle()
    assertTrue(vm.state.isSignedOut)
    assertFalse(vm.state.hasError)
  }

  // ----- What each person logged last, and whether it is new -----

  @Test
  fun news_isTheNewestEntryTheCaregiverDidntWrite_byWhenItWasLogged_onlyIfTheJournalIsShared() =
      runTest {
        follow("p-anne", "Anne", CarePermission.SYMPTOMS)
        follow("p-paul", "Paul", CarePermission.PLANNING)
        logs("p-anne", "felt-late", loggedAt = at(8, 0), felt = at(8, 0))
        // Felt earlier but logged later: it is what Anne did last
        logs("p-anne", "backdated", loggedAt = at(8, 40), felt = at(6, 0))
        // Not on the server yet: logged when it was felt
        logs("p-anne", "pending", loggedAt = null, felt = at(8, 20))
        // The caregiver's own entry is no news to them
        logs("p-anne", "by-sam", loggedAt = at(8, 50), addedBy = caregiver)
        logs("p-paul", "paul", loggedAt = at(8, 55))

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(setOf("p-anne"), vm.state.news.keys)
        assertEquals("backdated", vm.state.news.getValue("p-anne").latest.entry.id)
        // Said at the time it was felt, in the caregiver's zone
        assertEquals(LocalTime.of(6, 0), vm.state.news.getValue("p-anne").latest.at.toLocalTime())
        assertEquals(LocalDate.of(2026, 11, 28), vm.state.today)
      }

  @Test
  fun aPendingEntry_isNewerThanOneTheServerHasFromBefore() = runTest {
    follow("p-anne", "Anne", CarePermission.SYMPTOMS)
    logs("p-anne", "on-server", loggedAt = at(8, 0), felt = at(7, 0))
    logs("p-anne", "pending", loggedAt = null, felt = at(8, 20))
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals("pending", vm.state.news.getValue("p-anne").latest.entry.id)
  }

  @Test
  fun someoneFollowedForTheFirstTime_startsWithoutADot_butWhatTheyLogNextIsNew() = runTest {
    follow("p-anne", "Anne", CarePermission.SYMPTOMS)
    logs("p-anne", "before", loggedAt = at(8, 0))
    val vm = viewModel()
    advanceUntilIdle()

    assertEquals(emptySet<String>(), vm.newIds)
    assertEquals(at(9, 0), opened("p-anne"))
    assertNull(vm.state.newsKey)

    logs("p-anne", "after", loggedAt = at(9, 10))
    advanceUntilIdle()

    assertEquals(setOf("p-anne"), vm.newIds)
    assertTrue(vm.state.hasNew)
    val afterKey = vm.state.newsKey

    // Another one from the same person: a key of its own, so the button glows again
    logs("p-anne", "after-2", loggedAt = at(9, 20))
    advanceUntilIdle()
    assertNotEquals(afterKey, vm.state.newsKey)
  }

  @Test
  fun theDayShownNow_seesWhatIsLogged_andLeavingIt_marksItSeen() = runTest {
    follow("p-anne", "Anne", CarePermission.SYMPTOMS)
    follow("p-paul", "Paul", CarePermission.SYMPTOMS)
    lastOpened.markOpened(caregiver, "p-anne", at(7, 0))
    lastOpened.markOpened(caregiver, "p-paul", at(7, 0))
    logs("p-anne", "a1", loggedAt = at(8, 0))
    logs("p-paul", "p1", loggedAt = at(8, 0))
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals(setOf("p-anne", "p-paul"), vm.newIds)
    val bothNew = vm.state.newsKey

    vm.show("p-anne")
    advanceUntilIdle()
    assertEquals(setOf("p-paul"), vm.newIds)
    assertNotEquals(bothNew, vm.state.newsKey)

    // Logged while Anne's day is open: seen at once
    logs("p-anne", "a2", loggedAt = at(9, 5))
    advanceUntilIdle()
    assertEquals(setOf("p-paul"), vm.newIds)
    assertEquals(at(9, 5), opened("p-anne"))

    // Paul's day instead, then none: Anne's was open until 9:20
    clock.now = at(9, 20)
    vm.show("p-paul")
    vm.show(null)
    advanceUntilIdle()
    assertEquals(at(9, 20), opened("p-anne"))
    assertNull(vm.state.newsKey)

    logs("p-anne", "a3", loggedAt = at(9, 25))
    advanceUntilIdle()
    assertEquals(setOf("p-anne"), vm.newIds)
  }

  @Test
  fun theDayShownNow_isNeverNew_evenBeforeTheStoreHasCaughtUp() = runTest {
    follow("p-anne", "Anne", CarePermission.SYMPTOMS)
    lastOpened.markOpened(caregiver, "p-anne", at(7, 0))
    // Remembers nothing more: as if writing to the phone were slow
    val slow =
        object : LastOpened by lastOpened {
          override fun markOpened(caregiverUid: String, patientUid: String, at: Instant) = Unit
        }
    val vm = viewModel(store = slow)
    vm.show("p-anne")
    logs("p-anne", "a1", loggedAt = at(8, 0))
    advanceUntilIdle()

    assertEquals(emptySet<String>(), vm.newIds)
  }

  @Test
  fun signingInAgain_afterBeingSignedOut_isNotSignedOutAnymore() = runTest {
    auth.signOut()
    val vm = viewModel()
    advanceUntilIdle()
    assertTrue(vm.state.isSignedOut)

    runBlocking { auth.signInWithGoogle("token") }
    vm.load()
    advanceUntilIdle()

    assertFalse(vm.state.isSignedOut)
  }

  @Test
  fun aPhoneClockBehindTheServers_neverMakesWhatWasSeenNewAgain() = runTest {
    follow("p-anne", "Anne", CarePermission.SYMPTOMS)
    lastOpened.markOpened(caregiver, "p-anne", at(7, 0))
    val vm = viewModel()
    advanceUntilIdle()
    vm.show("p-anne")
    // The server's time is ahead of the phone's 9:00
    logs("p-anne", "ahead", loggedAt = at(9, 30))
    advanceUntilIdle()

    vm.show(null)
    advanceUntilIdle()

    assertEquals(emptySet<String>(), vm.newIds)
    assertEquals(at(9, 30), opened("p-anne"))
  }

  @Test
  fun whenTheDaysWereOpenedCantBeRead_nothingIsNew_butTheLinesStay() = runTest {
    follow("p-anne", "Anne", CarePermission.SYMPTOMS)
    logs("p-anne", "a1", loggedAt = at(8, 0))
    // At once, or after times by which a1 was new
    for (before in listOf(null, mapOf("p-anne" to at(7, 0)))) {
      val unreadable =
          object : LastOpened by lastOpened {
            override fun observe(caregiverUid: String): Flow<Map<String, Instant>> = flow {
              before?.let { emit(it) }
              throw IllegalStateException("corrupt")
            }
          }

      val vm = viewModel(store = unreadable)
      advanceUntilIdle()

      assertEquals("$before", "a1", vm.state.news.getValue("p-anne").latest.entry.id)
      assertEquals("$before", emptySet<String>(), vm.newIds)
      // Unknown is not first sight: nothing is remembered on top
      assertNull("$before", opened("p-anne"))
      assertFalse("$before", vm.state.hasError)
    }
  }

  @Test
  fun aJournalThatCantBeRead_leavesOnlyThatPersonWithoutNews() = runTest {
    follow("p-anne", "Anne", CarePermission.SYMPTOMS)
    follow("p-paul", "Paul", CarePermission.SYMPTOMS)
    logs("p-anne", "a1", loggedAt = at(8, 0))
    logs("p-paul", "p1", loggedAt = at(8, 0))
    val denying =
        object : SymptomRepository by journals {
          override fun observeSymptoms(uid: String): Flow<List<SymptomEntry>> =
              if (uid == "p-paul") flow { throw IllegalStateException("denied") }
              else journals.observeSymptoms(uid)
        }

    val vm = viewModel(symptomRepository = denying)
    advanceUntilIdle()

    assertEquals(setOf("p-anne"), vm.state.news.keys)
    assertFalse(vm.state.hasError)
  }

  @Test
  fun someoneWhoStopsSharingTheirJournal_hasNoNewsAnymore() = runTest {
    follow("p-anne", "Anne", CarePermission.SYMPTOMS)
    logs("p-anne", "a1", loggedAt = at(8, 0))
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals(setOf("p-anne"), vm.state.news.keys)

    circles.setMembers(
        "p-anne",
        listOf(
            CareCircleMember(
                uid = caregiver,
                firstName = "Sam",
                permissions = setOf(CarePermission.PLANNING),
            )
        ),
    )
    advanceUntilIdle()

    assertEquals(emptyMap<String, PatientNews>(), vm.state.news)
  }

  @Test
  fun showingADay_whileNobodyIsSignedIn_remembersNothing() = runTest {
    auth.signOut()
    val vm = viewModel()
    vm.show("p-anne")
    advanceUntilIdle()
    assertNull(opened("p-anne"))
  }
}
