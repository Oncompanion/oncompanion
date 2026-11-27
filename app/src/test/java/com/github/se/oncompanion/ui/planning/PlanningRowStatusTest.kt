// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.planning

import com.github.se.oncompanion.model.planning.PlanningTiming
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** What an appointment's row says at each moment of its day (#349, R3 · Parts). */
class PlanningRowStatusTest {

  private val zone = ZoneId.of("Europe/Zurich")
  private val day = LocalDate.of(2026, 11, 17)

  private fun at(time: String, date: LocalDate = day) =
      date.atTime(LocalTime.parse(time)).atZone(zone).toInstant()

  @Test
  fun theRowSaysWhenItStarts_thenUntilWhen_thenNothing() {
    // Physiotherapy from 14:00 to 15:00, seen at different times
    val physio = PlanningTiming.Timed(at("14:00"), at("15:00"))
    val cases =
        listOf(
            at("14:00", day.minusDays(1)) to PlanningRowStatus.Later,
            at("08:00") to PlanningRowStatus.InHours(6),
            at("11:01") to PlanningRowStatus.InHours(2),
            at("13:00") to PlanningRowStatus.InHours(1),
            at("13:01") to PlanningRowStatus.InMinutes(59),
            at("13:35") to PlanningRowStatus.InMinutes(25),
            // Less than a minute away still says a minute, never "In 0 min"
            at("13:59:30") to PlanningRowStatus.InMinutes(1),
            at("14:00") to PlanningRowStatus.HappeningNow(at("15:00")),
            at("14:59") to PlanningRowStatus.HappeningNow(at("15:00")),
            at("15:00") to PlanningRowStatus.Past,
        )
    for ((now, expected) in cases) {
      assertEquals("at $now", expected, physio.rowStatus(now, zone))
    }
  }

  @Test
  fun withoutAnEnd_itLastsAnHour_asEverywhereElse() {
    val noEnd = PlanningTiming.Timed(at("14:00"))
    val cases =
        listOf(
            at("14:30") to PlanningRowStatus.HappeningNow(at("15:00")),
            at("15:00") to PlanningRowStatus.Past,
        )
    for ((now, expected) in cases) {
      assertEquals("at $now", expected, noEnd.rowStatus(now, zone))
    }
  }
}
