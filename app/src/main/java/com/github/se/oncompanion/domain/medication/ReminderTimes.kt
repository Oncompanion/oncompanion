// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication

import com.github.se.oncompanion.model.medication.Medication
import java.time.LocalTime

/**
 * The reminder times the prescription form suggests for a medication (US-32): one per dose its
 * frequency says, at times the patient can change. They only help to remember: they never say when
 * to take the medication, and the prescription stays as recorded.
 *
 * When the frequency says no count ([DoseCount] answers none: missing, only if needed, unclear),
 * one reminder a day is suggested, and the patient adds or removes times, up to
 * [Medication.MAX_REMINDER_TIMES].
 */
object ReminderTimes {

  /** Morning, midday and evening: the times suggested for one, two or three doses. */
  private val MORNING = LocalTime.of(9, 0)
  private val MIDDAY = LocalTime.of(13, 0)
  private val EVENING = LocalTime.of(18, 0)

  /** Four doses a day, every four hours while awake. */
  private val FOUR_A_DAY = listOf(8, 12, 16, 20).map { LocalTime.of(it, 0) }

  /** Where a time the patient adds goes, in this order, skipping those already taken. */
  private val ADDED = listOf(MORNING, MIDDAY, EVENING, LocalTime.of(21, 0))

  /** The times suggested when the patient turns reminders on for a medication taken [frequency]. */
  fun suggested(frequency: String): List<LocalTime> =
      when (countOf(frequency)) {
        2 -> listOf(MORNING, EVENING)
        3 -> listOf(MORNING, MIDDAY, EVENING)
        4 -> FOUR_A_DAY
        else -> listOf(MORNING)
      }

  /**
   * [times] after the frequency changed to [frequency]: when it says a count, the patient's times
   * are kept up to that count, and suggested ones fill the rest. Otherwise, or with reminders off
   * (no time), they stay as they are.
   */
  fun fit(times: List<LocalTime>, frequency: String): List<LocalTime> {
    if (times.isEmpty()) return times
    val count = countOf(frequency) ?: return times
    val kept = times.take(count).toMutableList()
    // A new dose gets the time suggested for its place (the evening for a second one), or the
    // first usual one free if the patient already took it
    val suggested = suggested(frequency)
    while (kept.size < count) kept += suggested[kept.size].takeIf { it !in kept } ?: added(kept)
    return kept
  }

  /** Whether the patient can add a time: only when the frequency says no count, up to the limit. */
  fun canAdd(times: List<LocalTime>, frequency: String): Boolean =
      countOf(frequency) == null && times.size in 1 until Medication.MAX_REMINDER_TIMES

  /** Whether the patient can remove a time: as for [canAdd], keeping one at least. */
  fun canRemove(times: List<LocalTime>, frequency: String): Boolean =
      countOf(frequency) == null && times.size > 1

  /** The time a newly added reminder starts at: the first of the usual ones not taken yet. */
  fun added(times: List<LocalTime>): LocalTime = ADDED.first { it !in times }

  /** A count of doses the form can remind of, one reminder each; `null` otherwise. */
  private fun countOf(frequency: String): Int? =
      DoseCount.of(frequency)?.takeIf { it <= Medication.MAX_REMINDER_TIMES }
}
