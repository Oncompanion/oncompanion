// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.medication.FakeTakenDosesRepository
import com.github.se.oncompanion.model.medication.FakeTakenDosesRepository.Write
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules every screen that ticks doses shares (the Overview, then Planning): ticks, the sheet
 * correcting them, and what happens when the counts are unknown, the day changes or nobody is
 * signed in. The Overview's ViewModel tests check the same rules through its screen.
 */
class DoseTicksTest {

  private val day = LocalDate.of(2026, 11, 24)
  private val doses = FakeTakenDosesRepository()
  private val auth =
      FakeAuthRepository(onSignIn = { AuthUser(uid = "alice") }).also {
        runBlocking { it.signInWithGoogle("token") }
      }

  /** Ticks following alice's [day], with what the screen saw at each change. */
  private class Screen(
      scope: CoroutineScope,
      doses: FakeTakenDosesRepository,
      auth: FakeAuthRepository,
  ) {
    val seen = mutableListOf<Pair<Map<String, Int>?, DosesSheet?>>()
    val ticks: DoseTicks = DoseTicks(scope, doses, auth) { seen += ticks.taken to ticks.sheet }

    /** [id] as the screen shows it: [perDay] doses a day and the count read. */
    fun medication(id: String, perDay: Int?) =
        TodayMedication(
            key = "key-$id",
            medicationId = id,
            name = id,
            dosesPerDay = perDay.takeIf { ticks.taken != null },
            taken = ticks.taken?.get(id) ?: 0,
        )
  }

  private fun TestScope.screen(): Screen =
      Screen(backgroundScope, doses, auth).also {
        it.ticks.follow("alice", day)
        runCurrent()
      }

  @Test
  fun aTick_showsAtOnce_thenIsSavedOnTheDayFollowed() = runTest {
    val screen = screen()

    screen.ticks.tick(screen.medication("m", perDay = 3))
    assertEquals(1, screen.ticks.taken?.get("m"))
    runCurrent()

    assertEquals(listOf(Write("alice", day, "m", 1)), doses.writes)
  }

  @Test
  fun theSheet_changesOnlyItsDraft_andDoneSavesItOnce_ifItChanged() = runTest {
    doses.setTaken("alice", day, "m", 1)
    doses.writes.clear()
    val screen = screen()

    // Closed without Done, or Done with the count saved: nothing is written
    screen.ticks.openSheet(screen.medication("m", perDay = 3))
    screen.ticks.moreDoses()
    screen.ticks.closeSheet()
    screen.ticks.openSheet(screen.medication("m", perDay = 3))
    assertEquals(1, screen.ticks.sheet?.draft)
    screen.ticks.saveSheet()
    runCurrent()
    assertTrue(doses.writes.isEmpty())

    // − and + stay within 0 and the doses a day; a second Done saves nothing more
    screen.ticks.openSheet(screen.medication("m", perDay = 3))
    repeat(5) { screen.ticks.moreDoses() }
    assertEquals(3, screen.ticks.sheet?.draft)
    repeat(5) { screen.ticks.lessDoses() }
    assertEquals(0, screen.ticks.sheet?.draft)
    screen.ticks.saveSheet()
    screen.ticks.saveSheet()
    runCurrent()

    assertNull(screen.ticks.sheet)
    assertEquals(listOf(Write("alice", day, "m", 0)), doses.writes)
  }

  @Test
  fun theSheet_opensOnlyForSeveralDosesADay_andFollowsItsMedication() = runTest {
    val screen = screen()
    for (perDay in listOf(null, 1)) {
      screen.ticks.openSheet(screen.medication("m", perDay))
      assertNull("$perDay a day", screen.ticks.sheet)
    }

    screen.ticks.openSheet(screen.medication("m", perDay = 4))
    repeat(4) { screen.ticks.moreDoses() }
    // Its frequency changes: the draft stays within the new count, then the sheet closes when it
    // can't be corrected there any more, or the medication goes
    val cases =
        listOf(
            listOf(screen.medication("m", perDay = 2)) to 2,
            listOf(screen.medication("m", perDay = 1)) to null,
            emptyList<TodayMedication>() to null,
        )
    for ((medications, draft) in cases) {
      screen.ticks.keepSheetFor(medications)
      assertEquals("$medications", draft, screen.ticks.sheet?.draft)
    }
  }

  @Test
  fun followingAnotherDay_dropsTheTicksAndClosesTheSheet_andSignedOutReadsNothing() = runTest {
    doses.setTaken("alice", day, "m", 1)
    val screen = screen()
    screen.ticks.openSheet(screen.medication("m", perDay = 2))
    screen.seen.clear()

    screen.ticks.follow(null, day.plusDays(1))
    runCurrent()
    doses.setTaken("alice", day.plusDays(1), "m", 2)
    runCurrent()
    doses.writes.clear()
    screen.ticks.tick(TodayMedication("key-m", "m", "m", dosesPerDay = 2))
    runCurrent()

    // Unknown and closed at once; nothing read or saved while nobody's doses are followed
    assertEquals(listOf(null to null), screen.seen)
    assertTrue(doses.writes.isEmpty())
  }

  /** Midnight with the screen open: the same patient's next day starts from its own count. */
  @Test
  fun theNextDay_aTickBeforeItIsReadSavesNothing_thenOneSavesFromItsCount() = runTest {
    doses.setTaken("alice", day, "m", 2)
    val screen = screen()
    // What the screen still shows from the day before: 2 of 3 doses
    val yesterdays = screen.medication("m", perDay = 3)
    doses.writes.clear()

    screen.ticks.follow("alice", day.plusDays(1))
    screen.ticks.tick(yesterdays)
    runCurrent()
    screen.ticks.tick(screen.medication("m", perDay = 3))
    runCurrent()

    // Not 3 (yesterday's 2 + 1) on the new day: only the tick after it was read, from its 0
    assertEquals(listOf(Write("alice", day.plusDays(1), "m", 1)), doses.writes)
  }

  @Test
  fun unknownCounts_signedOut_orARefusedSave_writeNothing() = runTest {
    // Unknown: nothing to tick, no sheet, and a count can't be built on a wrong 0
    val unknown = FakeTakenDosesRepository(readFailure = IllegalStateException("refused"))
    val ticks = DoseTicks(backgroundScope, unknown, auth) {}
    ticks.follow("alice", day)
    runCurrent()
    val m = TodayMedication("key-m", "m", "m", dosesPerDay = 3)
    ticks.tick(m)
    ticks.openSheet(m)
    runCurrent()
    assertTrue(unknown.writes.isEmpty())
    assertNull(ticks.sheet)

    // The server refuses more than 24 doses a day: logged, not thrown
    val screen = screen()
    screen.ticks.tick(TodayMedication("key-m", "m", "m", dosesPerDay = 30, taken = 24))
    runCurrent()

    // Signed out since the day was read: nothing is saved
    auth.signOut()
    screen.ticks.tick(screen.medication("n", perDay = 1))
    runCurrent()
    assertTrue(doses.writes.isEmpty())
  }
}
