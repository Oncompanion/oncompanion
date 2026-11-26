// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.common

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests [keepUntilExpired] with items that may never expire. How an item leaves the moment it
 * expires is tested through its first use, `keepRecent` (RecentNotificationsTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeepUntilExpiredTest {

  private val start = Instant.parse("2026-11-24T09:00:00Z")

  @Test
  fun anItemWithNoExpiry_isAlwaysKept_whileTheOthersLeaveWhenTheyExpire() = runTest {
    // An item and when it expires: "unsent" has none yet, like an invitation the server
    // hasn't received
    val items = listOf("unsent" to null, "soon" to start.plusSeconds(60))
    val emitted = mutableListOf<List<String>>()
    backgroundScope.launch {
      flowOf(items)
          .keepUntilExpired({ start.plusMillis(testScheduler.currentTime) }) { it.second }
          .collect { kept -> emitted += kept.map { it.first } }
    }
    runCurrent()
    assertEquals(listOf("unsent", "soon"), emitted.last())

    advanceTimeBy(Duration.ofMinutes(2).toMillis())
    runCurrent()
    assertEquals(listOf("unsent"), emitted.last())

    // Nothing left that expires: it stops waiting
    advanceTimeBy(Duration.ofDays(30).toMillis())
    runCurrent()
    assertEquals(2, emitted.size)
  }
}
