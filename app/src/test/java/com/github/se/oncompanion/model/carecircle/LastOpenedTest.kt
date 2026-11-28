// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The contract of [LastOpened], for the phone's [LastOpenedPreferences] and [LastOpenedInMemory].
 */
@RunWith(AndroidJUnit4::class)
class LastOpenedTest {

  private val preferences =
      ApplicationProvider.getApplicationContext<Context>()
          .getSharedPreferences("last_opened_test", Context.MODE_PRIVATE)
          .also { it.edit().clear().commit() }

  /** Both stores, each starting empty. */
  private fun stores(): List<LastOpened> =
      listOf(LastOpenedPreferences(preferences), LastOpenedInMemory())

  private fun at(seconds: Long) = Instant.ofEpochSecond(seconds)

  private fun LastOpened.of(caregiverUid: String) = runBlocking { observe(caregiverUid).first() }

  @Test
  fun eachCaregiver_seesOnlyTheirOwnDays() {
    for (store in stores()) {
      store.markOpened("sam", "anne", at(10))
      store.markOpened("sam", "paul", at(20))
      // A uid that starts like Sam's must not read Sam's days
      store.markOpened("samuel", "anne", at(30))

      assertEquals(store.toString(), mapOf("anne" to at(10), "paul" to at(20)), store.of("sam"))
      assertEquals(store.toString(), mapOf("anne" to at(30)), store.of("samuel"))
      assertEquals(store.toString(), emptyMap<String, Instant>(), store.of("lea"))
    }
  }

  @Test
  fun anEarlierTime_neverReplacesALaterOne() {
    for (store in stores()) {
      store.markOpened("sam", "anne", at(20))
      store.markOpened("sam", "anne", at(10))
      assertEquals(store.toString(), at(20), store.of("sam")["anne"])

      store.markOpened("sam", "anne", at(30))
      assertEquals(store.toString(), at(30), store.of("sam")["anne"])
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun observe_saysEachChange_asItHappens() = runTest {
    for (store in stores()) {
      val seen = mutableListOf<Map<String, Instant>>()
      val watching =
          launch(UnconfinedTestDispatcher(testScheduler)) { store.observe("sam").toList(seen) }

      store.markOpened("sam", "anne", at(10))
      store.markOpened("sam", "anne", at(20))
      watching.cancel()

      assertEquals(
          store.toString(),
          listOf(emptyMap(), mapOf("anne" to at(10)), mapOf("anne" to at(20))),
          seen,
      )
    }
  }

  @Test
  fun thePhonesDays_stayAfterTheAppRestarts() {
    LastOpenedPreferences(preferences).markOpened("sam", "anne", at(10))
    assertEquals(mapOf("anne" to at(10)), LastOpenedPreferences(preferences).of("sam"))
  }
}
