// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.planning

import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests of [planningListLines]. */
class PlanningListLinesTest {
  private val today = LocalDate.of(2026, 11, 17)
  private val window = PlanningListWindow.around(today)

  private fun appointment(id: String, instant: String) =
      PlanningItem(PlanningSource.Appointment(id), PlanningTiming.Timed(Instant.parse(instant)), id)

  private fun event(id: String, instant: String) =
      PlanningItem(PlanningSource.Event(id), PlanningTiming.Timed(Instant.parse(instant)), id)

  private fun medication(
      id: String,
      date: String,
      frequency: String?,
      isConfirmed: Boolean = true,
  ) =
      PlanningItem(
          PlanningSource.Medication("p", id),
          PlanningTiming.DateOnly(LocalDate.parse(date)),
          id,
          frequency = frequency,
          isConfirmed = isConfirmed,
      )

  /** Each line as the test reads it: "Day 2026-11-30 · 5 doses", "Entry a", "Month 2026-12". */
  private fun describe(lines: List<PlanningListLine>) = lines.map { line ->
    when (line) {
      is PlanningListLine.Month -> "Month ${line.month}"
      is PlanningListLine.Entry -> "Entry ${line.item.title}"
      is PlanningListLine.Day ->
          listOfNotNull(
                  if (line.isToday) "Today ${line.date}" else "Day ${line.date}",
                  line.doses?.let { "$it doses" },
                  "past".takeIf { line.isPast },
              )
              .joinToString(" · ")
    }
  }

  private fun byDay(vararg items: PlanningItem) = items.groupBy {
    when (val timing = it.timing) {
      is PlanningTiming.DateOnly -> timing.date
      is PlanningTiming.Timed -> LocalDate.parse(timing.instant.toString().take(10))
    }
  }

  @Test
  fun showsTheDaysWithSomethingPlanned() {
    // name, entries by day, window -> lines
    val cases =
        listOf(
            Triple("nothing loaded: today alone", byDay() to window, listOf("Today 2026-11-17")),
            Triple(
                "today outside the window: no line for it",
                byDay() to PlanningListWindow.around(LocalDate.of(2027, 6, 1)),
                emptyList(),
            ),
            Triple(
                "empty days skipped, past days muted",
                byDay(
                    appointment("Blood test", "2026-11-16T08:00:00Z"),
                    event("Art workshop", "2026-11-18T13:00:00Z"),
                ) to window,
                listOf(
                    "Day 2026-11-16 · past",
                    "Entry Blood test",
                    "Today 2026-11-17",
                    "Day 2026-11-18",
                    "Entry Art workshop",
                ),
            ),
            Triple(
                "a heading between months and years, none at the top",
                byDay(
                    appointment("Scan", "2026-10-30T08:00:00Z"),
                    appointment("Chemotherapy", "2026-11-30T08:00:00Z"),
                    appointment("Nutritionist", "2026-12-01T10:00:00Z"),
                    appointment("Check-up", "2027-01-04T09:00:00Z"),
                ) to window,
                listOf(
                    "Day 2026-10-30 · past",
                    "Entry Scan",
                    "Month 2026-11",
                    "Today 2026-11-17",
                    "Day 2026-11-30",
                    "Entry Chemotherapy",
                    "Month 2026-12",
                    "Day 2026-12-01",
                    "Entry Nutritionist",
                    "Month 2027-01",
                    "Day 2027-01-04",
                    "Entry Check-up",
                ),
            ),
        )
    for ((name, input, expected) in cases) {
      val (itemsByDay, shownWindow) = input
      val lines = planningListLines(itemsByDay, today, shownWindow)
      assertEquals(name, expected, describe(lines))
      assertEquals(name, lines.size, lines.map { it.key }.distinct().size)
    }
  }

  @Test
  fun otherDaysCountTheirDosesAndTodayListsItsMedications() {
    val itemsByDay =
        byDay(
            // Today: its medications first, then its timed entries, as given
            medication("Vitamin D", "2026-11-17", "Once a day"),
            medication("Paracetamol", "2026-11-17", "If needed"),
            appointment("Physio", "2026-11-17T15:30:00Z"),
            // Two counts add up; the medications aren't listed
            medication("Ondansetron", "2026-11-18", "3 times a day"),
            medication("Vitamin D", "2026-11-18", "Once a day"),
            // A caregiver added it and the patient hasn't confirmed it: not counted
            medication("Folic acid", "2026-11-18", "Twice a day", isConfirmed = false),
            // Only a medication without a daily count: nothing to show
            medication("Paracetamol", "2026-11-19", "If needed"),
            // A medication without a count adds nothing to the others
            medication("Paracetamol", "2026-11-20", "If needed"),
            medication("Dexamethasone", "2026-11-20", "Twice a day"),
            appointment("Blood test", "2026-11-20T08:00:00Z"),
        )

    val lines = planningListLines(itemsByDay, today, window)

    assertEquals(
        listOf(
            "Today 2026-11-17",
            "Entry Vitamin D",
            "Entry Paracetamol",
            "Entry Physio",
            "Day 2026-11-18 · 4 doses",
            "Day 2026-11-20 · 2 doses",
            "Entry Blood test",
        ),
        describe(lines),
    )
  }
}
