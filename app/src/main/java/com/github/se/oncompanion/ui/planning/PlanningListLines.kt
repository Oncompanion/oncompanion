// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.planning

import com.github.se.oncompanion.domain.medication.DoseCount
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningSource
import java.time.LocalDate
import java.time.YearMonth

/**
 * One line of the endless List. Its [key] stays the same while the loaded days change, so the List
 * keeps its place when days are added above it.
 */
sealed interface PlanningListLine {
  /** The line's key in the List, unique among its lines. */
  val key: String

  /** The day the line belongs to; a month heading belongs to the month's first day. */
  val date: LocalDate

  /** The heading of a month, between the last day of the previous month and its first day. */
  data class Month(val month: YearMonth) : PlanningListLine {
    override val key: String
      get() = "month:$month"

    override val date: LocalDate
      get() = month.atDay(1)
  }

  /**
   * A day's line, above its entries.
   *
   * @property doses on another day than today, how many doses its medications say a day, or null
   *   when none says a count (its medications aren't listed, only counted)
   * @property isPast the day is before today, so it is muted
   */
  data class Day(
      override val date: LocalDate,
      val doses: Int?,
      val isToday: Boolean,
      val isPast: Boolean,
  ) : PlanningListLine {
    override val key: String
      get() = dayKey(date)
  }

  /** An entry, under the day it starts. */
  data class Entry(val item: PlanningItem, override val date: LocalDate) : PlanningListLine {
    override val key: String
      get() = "entry:${item.key}"
  }

  companion object {
    /** The key of [date]'s line, so the List can find a day. */
    fun dayKey(date: LocalDate): String = "day:$date"
  }
}

/**
 * The List's lines for the entries loaded in [window], in order: each day that has something
 * planned, with a month heading between two months.
 * - Today always has its line while [window] holds it, and lists its medications first, then its
 *   timed entries, as [itemsByDay] orders them.
 * - Other days list their appointments and events. Their medications only add up to the doses on
 *   the day's line; a medication without a daily count (if needed, weekly) adds nothing, nor does
 *   one the patient hasn't confirmed yet (a caregiver added it), as on the Overview.
 * - A day with neither an appointment, an event nor a dose to count is skipped.
 */
fun planningListLines(
    itemsByDay: Map<LocalDate, List<PlanningItem>>,
    today: LocalDate,
    window: PlanningListWindow,
): List<PlanningListLine> {
  val days = (itemsByDay.keys + listOfNotNull(today.takeIf { it in window })).toSortedSet()
  val lines = mutableListOf<PlanningListLine>()
  for (date in days) {
    val items = itemsByDay[date].orEmpty()
    val isToday = date == today
    val shown = if (isToday) items else items.filter { it.source !is PlanningSource.Medication }
    val doses = if (isToday) null else doseCount(items)
    if (!isToday && shown.isEmpty() && doses == null) continue
    // A new month gets its heading, except at the top of the List
    val previous = lines.lastOrNull()?.date
    if (previous != null && YearMonth.from(previous) != YearMonth.from(date)) {
      lines += PlanningListLine.Month(YearMonth.from(date))
    }
    lines += PlanningListLine.Day(date, doses, isToday, isPast = date < today)
    shown.mapTo(lines) { PlanningListLine.Entry(it, date) }
  }
  return lines
}

/** The doses a day [items]' confirmed medications say, or null when none of them says a count. */
private fun doseCount(items: List<PlanningItem>): Int? =
    items
        .filter { it.source is PlanningSource.Medication && it.isConfirmed }
        .mapNotNull { DoseCount.of(it.frequency) }
        .takeIf { it.isNotEmpty() }
        ?.sum()
