// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.navigation

import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.medication.PrescriptionConfirmation
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BottomBarViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val medications = FakeMedicationRepository()
  // Signing in with a token signs in the user whose uid is that token
  private val auth = FakeAuthRepository(onSignIn = { AuthUser(it) })
  private val date = LocalDate.of(2026, 11, 22)

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun prescription(id: String, confirmation: PrescriptionConfirmation) =
      Prescription(
          id = id,
          prescribedOn = date,
          medications =
              listOf(Medication(id = "$id-1", prescriptionId = id, name = id, startDate = date)),
          addedBy = "sam",
          addedByName = "Sam",
          confirmation = confirmation,
      )

  /** The count the bar shows, followed as a tab on screen does. */
  private fun TestScope.badgeOf(viewModel: BottomBarViewModel): () -> Int {
    backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
    return {
      advanceUntilIdle()
      viewModel.uiState.value.prescriptionsToReview
    }
  }

  @Test
  fun countsOnlyThePrescriptionsWaitingForThePatient_andFollowsThem() =
      runTest(dispatcher) {
        auth.signInWithGoogle("patient")
        listOf(
                prescription("waiting", PrescriptionConfirmation.PENDING),
                prescription("alsoWaiting", PrescriptionConfirmation.PENDING),
                prescription("confirmed", PrescriptionConfirmation.CONFIRMED),
                prescription("rejected", PrescriptionConfirmation.REJECTED),
            )
            .forEach { medications.seed("patient", it) }
        val badge = badgeOf(BottomBarViewModel(auth, medications))
        assertEquals(2, badge())

        // The patient confirms one: the count follows
        medications.seed("patient", prescription("waiting", PrescriptionConfirmation.CONFIRMED))
        assertEquals(1, badge())
      }

  @Test
  fun followsWhoeverIsSignedIn() =
      runTest(dispatcher) {
        medications.seed("anne", prescription("anne's", PrescriptionConfirmation.PENDING))
        medications.seed("marc", prescription("marc's", PrescriptionConfirmation.PENDING))
        medications.seed("marc", prescription("marc's 2", PrescriptionConfirmation.PENDING))
        val badge = badgeOf(BottomBarViewModel(auth, medications))

        // It outlives sign-out: each account sees only its own count, nobody signed in none
        val steps =
            listOf<Pair<String?, Int>>("anne" to 1, null to 0, "marc" to 2, null to 0, "anne" to 1)
        for ((account, expected) in steps) {
          if (account == null) auth.signOut() else auth.signInWithGoogle(account)
          assertEquals("signed in: $account", expected, badge())
        }

        // The account signed in before changes: the next one's badge doesn't hear of it
        auth.signOut()
        auth.signInWithGoogle("marc")
        assertEquals(2, badge())
        medications.seed("anne", prescription("anne's 2", PrescriptionConfirmation.PENDING))
        medications.seed("anne", prescription("anne's 3", PrescriptionConfirmation.PENDING))
        assertEquals(2, badge())
      }

  @Test
  fun showsNoBadge_whenThePrescriptionsOrTheUserCantBeRead() =
      runTest(dispatcher) {
        auth.signInWithGoogle("patient")
        medications.seed("patient", prescription("waiting", PrescriptionConfirmation.PENDING))

        // The prescriptions can't be read
        medications.readError = IllegalStateException("unavailable")
        assertEquals(0, badgeOf(BottomBarViewModel(auth, medications))())

        // Reading them fails once they are shown: the badge goes, and the next account still counts
        medications.readError = null
        val running = BottomBarViewModel(auth, medications)
        val badge = badgeOf(running)
        assertEquals(1, badge())
        medications.failRunningReads(IllegalStateException("access lost"))
        assertEquals(0, badge())
        auth.signOut()
        assertEquals(0, badge())
        auth.signInWithGoogle("patient")
        assertEquals(1, badge())

        // Who is signed in can't be read
        auth.observeError = IllegalStateException("unavailable")
        assertEquals(0, badgeOf(BottomBarViewModel(auth, medications))())
      }
}
