// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import com.github.se.oncompanion.model.symptom.SymptomType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * One day of the "This week" chart.
 *
 * @property intensity the strongest symptom logged that day (see [SymptomEntry.strongest]), `null`
 *   if none of its entries gave a strength
 * @property hasEntries whether something was logged that day: without an [intensity] the chart
 *   shows an open dot, so a day logged without a number isn't mistaken for a day without symptoms
 */
data class WeekDay(val date: LocalDate, val intensity: Int?, val hasEntries: Boolean)

/**
 * A symptom the "This week" card can name: [type], or for "Other" the name the user gave it, so
 * each named "Other" counts as its own symptom.
 *
 * @property otherName the name of an "Other" symptom, as first written this week; `null` otherwise
 */
data class WeekSymptom(val type: SymptomType, val otherName: String? = null)

/**
 * The calendar week of the journal's "This week" card (Figma: "Symptoms – History").
 *
 * @property days Monday to Sunday; the days after today are empty
 * @property entryCount how many symptoms were logged this week
 * @property mostOften the symptom logged more often than every other one this week, `null` on a
 *   tie: the card then only gives the count, so it never names a symptom the entries don't single
 *   out
 */
data class WeekSummary(
    val days: List<WeekDay>,
    val entryCount: Int,
    val mostOften: WeekSymptom?,
)

/**
 * Sums up the calendar week (Monday to Sunday) that contains [today], from the journal's [days].
 * Returns `null` when nothing was logged this week, so the card isn't shown empty. Only what the
 * user entered is counted: no score, no trend.
 */
fun weekSummaryOf(days: List<JournalDay>, today: LocalDate): WeekSummary? {
  val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
  val thisWeek = days.filter { it.date >= monday && it.date <= monday.plusDays(6) }
  val entries = thisWeek.flatMap { it.entries }
  if (entries.isEmpty()) return null

  val weekDays =
      (0L..6L).map { offset ->
        val date = monday.plusDays(offset)
        val dayEntries = thisWeek.filter { it.date == date }.flatMap { it.entries }
        WeekDay(
            date = date,
            intensity = dayEntries.mapNotNull { it.entry.strongest() }.maxOrNull(),
            hasEntries = dayEntries.isNotEmpty(),
        )
      }
  val logged = entries.flatMap { (entry) ->
    entry.types.map { type ->
      WeekSymptom(type, entry.otherName.takeIf { type == SymptomType.OTHER })
    }
  }
  // The same name however it was typed: "Hiccups" and " hiccups" are one symptom
  val counts = logged.groupingBy { it.copy(otherName = it.otherName?.let(::sameName)) }.eachCount()
  // Only a symptom logged more often than every other one is named
  val highest = counts.values.max()
  val mostOften =
      counts
          .filterValues { it == highest }
          .keys
          .singleOrNull()
          ?.let { key ->
            logged.first { it.copy(otherName = it.otherName?.let(::sameName)) == key }
          }
  return WeekSummary(weekDays, entries.size, mostOften)
}

/** [name] without case or extra spaces, to compare two names of a symptom. */
internal fun sameName(name: String): String =
    name.trim().lowercase().split(SPACES).joinToString(" ")

private val SPACES = Regex("""\s+""")
