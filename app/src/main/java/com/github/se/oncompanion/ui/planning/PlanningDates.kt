// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.planning

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** Shared numeric date contract; uuuu enables strict leap-year validation. */
object PlanningDates {
  /** The first supported day, 1 January of year 1 (a Monday). */
  val MIN_DAY: LocalDate = LocalDate.of(1, 1, 1)

  /** The last supported day, 31 December 9999. */
  val MAX_DAY: LocalDate = LocalDate.of(9999, 12, 31)

  /** The day after [MAX_DAY], the end of the last range Planning loads. */
  val END_OF_MAX_DAY: LocalDate = MAX_DAY.plusDays(1)

  /** Whether [date] is between [MIN_DAY] and [MAX_DAY], with a four-digit year. */
  fun isSupported(date: LocalDate): Boolean = date.year in 1..9999

  private val formatter =
      DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)

  /**
   * [date] as "dd/MM/yyyy".
   *
   * @throws IllegalArgumentException if [date] isn't supported (see [isSupported])
   */
  fun format(date: LocalDate): String {
    require(isSupported(date)) { "Date must have a four-digit positive year" }
    return date.format(formatter)
  }

  /**
   * The day written as "dd/MM/yyyy" in [text], strictly: 31/02 is no day.
   *
   * @throws IllegalArgumentException if [text] isn't such a day with a four-digit year
   */
  fun parse(text: String): LocalDate {
    require(text.matches(Regex("[0-9]{2}/[0-9]{2}/[0-9]{4}"))) { "Date must use dd/MM/yyyy" }
    return LocalDate.parse(text, formatter).also {
      require(it.year in 1..9999) { "Date must have a four-digit positive year" }
    }
  }
}
