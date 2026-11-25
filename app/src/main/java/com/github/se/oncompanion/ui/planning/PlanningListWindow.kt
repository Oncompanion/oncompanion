// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.planning

import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.ui.planning.PlanningDates.END_OF_MAX_DAY
import com.github.se.oncompanion.ui.planning.PlanningDates.MIN_DAY
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/**
 * The days the List loads, from [start] (included) to [end] (excluded): the List is one endless
 * scroll, so it loads weeks around the day shown and more as the user nears either edge, instead of
 * one period like the grids.
 *
 * It only knows calendar days, never the entries, and stays within years 1 to 9999.
 */
data class PlanningListWindow(val start: LocalDate, val end: LocalDate) {
  init {
    require(start >= MIN_DAY && end <= END_OF_MAX_DAY && start < end) {
      "The window must hold days within years 1 to 9999"
    }
  }

  /** Whether the List has loaded [date]. */
  operator fun contains(date: LocalDate): Boolean = date >= start && date < end

  /** [GROWTH] more before [start], or this window at year 1. */
  fun earlier(): PlanningListWindow = copy(start = maxOf(start.minus(GROWTH), MIN_DAY))

  /** [GROWTH] more after [end], or this window at year 9999. */
  fun later(): PlanningListWindow = copy(end = minOf(end.plus(GROWTH), END_OF_MAX_DAY))

  /** The days to load in [zoneId]. */
  fun range(zoneId: ZoneId): PlanningRange = PlanningRange(start, end, zoneId)

  companion object {
    /** How far back the window first goes, and how much each step back or ahead adds. */
    val GROWTH: Period = Period.ofWeeks(4)

    /** How far ahead the window first goes. */
    val AHEAD: Period = Period.ofWeeks(8)

    /** From [GROWTH] before [date] to [AHEAD] after it, both included, within years 1 to 9999. */
    fun around(date: LocalDate): PlanningListWindow {
      require(PlanningDates.isSupported(date)) { "Date must have a four-digit positive year" }
      return PlanningListWindow(
          maxOf(date.minus(GROWTH), MIN_DAY),
          minOf(date.plus(AHEAD).plusDays(1), END_OF_MAX_DAY),
      )
    }
  }
}
