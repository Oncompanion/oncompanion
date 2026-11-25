// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.planning

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the days the endless List loads: where it opens, how it grows, and the supported years.
 */
class PlanningListWindowTest {
  private fun day(text: String) = LocalDate.parse(text)

  private fun window(start: String, end: String) = PlanningListWindow(day(start), day(end))

  /** It opens 4 weeks before the day and 8 after, both included, cut at years 1 and 9999. */
  @Test
  fun opensAroundTheDay() {
    // selected day -> first day, day after the last
    val cases =
        listOf(
            "2026-10-02" to window("2026-09-04", "2026-11-28"),
            // Over a leap day and a year end
            "2028-01-15" to window("2027-12-18", "2028-03-12"),
            "0001-01-10" to window("0001-01-01", "0001-03-08"),
            "9999-12-20" to window("9999-11-22", "+10000-01-01"),
        )
    for ((date, expected) in cases) {
      val window = PlanningListWindow.around(day(date))
      assertEquals(date, expected, window)
      assertTrue(date, day(date) in window)
    }
  }

  /** Each step adds 4 weeks on its side only, and stops at years 1 and 9999 without shrinking. */
  @Test
  fun growsByFourWeeksOnOneSide() {
    val shown = window("2026-09-04", "2026-11-28")
    val first = window("0001-01-15", "0001-03-08")
    val last = window("9999-11-22", "9999-12-20")
    // window after a step -> expected
    val cases =
        listOf(
            shown.earlier() to window("2026-08-07", "2026-11-28"),
            shown.later() to window("2026-09-04", "2026-12-26"),
            first.earlier() to window("0001-01-01", "0001-03-08"),
            first.earlier().earlier() to window("0001-01-01", "0001-03-08"),
            last.later() to window("9999-11-22", "+10000-01-01"),
            last.later().later() to window("9999-11-22", "+10000-01-01"),
        )
    for ((grown, expected) in cases) assertEquals(expected, grown)
  }

  /** It holds its own days only, and loads them as whole days in the display zone. */
  @Test
  fun holdsAndLoadsItsDays() {
    val window = window("2026-10-01", "2026-10-29")
    assertTrue(day("2026-10-01") in window)
    assertTrue(day("2026-10-28") in window)
    assertFalse(day("2026-09-30") in window)
    assertFalse(day("2026-10-29") in window)
    // Summer time ends on 25 October in Zurich
    val range = window.range(ZoneId.of("Europe/Zurich"))
    assertEquals(Instant.parse("2026-09-30T22:00:00Z"), range.startInclusive)
    assertEquals(Instant.parse("2026-10-28T23:00:00Z"), range.endExclusive)
  }

  /** A window outside years 1 to 9999, or without a day, can't be built. */
  @Test
  fun rejectsUnsupportedDays() {
    val cases =
        listOf(
            { PlanningListWindow.around(day("0000-12-31")) },
            { PlanningListWindow.around(day("+10000-01-01")) },
            { window("0000-12-31", "0001-02-01") },
            { window("9999-12-01", "+10000-01-02") },
            { window("2026-10-02", "2026-10-02") },
        )
    for (build in cases) assertThrows(IllegalArgumentException::class.java) { build() }
  }
}
