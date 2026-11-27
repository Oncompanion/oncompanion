// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.planning

import com.github.se.oncompanion.domain.planning.endOrDefault
import com.github.se.oncompanion.model.planning.PlanningTiming
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** What an appointment or event's row says about where it stands now (Figma R3 · Parts). */
sealed interface PlanningRowStatus {
  /** On a later day: the row shows its detail only. */
  data object Later : PlanningRowStatus

  /** Later today, in less than an hour: "In 25 min". */
  data class InMinutes(val minutes: Long) : PlanningRowStatus

  /** Later today, in an hour or more, whole hours rounded down: "In 2 h". */
  data class InHours(val hours: Long) : PlanningRowStatus

  /** Under way: "Happening now · until 15:00". */
  data class HappeningNow(val until: Instant) : PlanningRowStatus

  /** Over: the row is muted. */
  data object Past : PlanningRowStatus
}

/**
 * The row's status at [now], from [statusAt] (so "today", the end and the 1 h default are the same
 * as everywhere else). A start less than a minute away still says "In 1 min".
 */
fun PlanningTiming.Timed.rowStatus(now: Instant, zoneId: ZoneId): PlanningRowStatus =
    when (statusAt(now, zoneId)) {
      PlanningStatus.UPCOMING -> PlanningRowStatus.Later
      PlanningStatus.TODAY -> {
        val untilStart = Duration.between(now, instant)
        if (untilStart < Duration.ofHours(1))
            PlanningRowStatus.InMinutes(untilStart.toMinutes().coerceAtLeast(1))
        else PlanningRowStatus.InHours(untilStart.toHours())
      }
      PlanningStatus.HAPPENING_NOW -> PlanningRowStatus.HappeningNow(endOrDefault())
      PlanningStatus.PAST -> PlanningRowStatus.Past
    }
