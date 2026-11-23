// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.planning.PlanningTiming
import java.time.Duration
import java.time.Instant

/**
 * How long an entry without an end lasts in Planning: an appointment (it records no end) or an
 * event whose end is unknown. The calendar draws it this long and shows it as happening now for
 * that time. It is a display choice, not something the patient entered.
 */
val DEFAULT_PLANNING_DURATION: Duration = Duration.ofHours(1)

/**
 * When this entry ends: its [end], or [DEFAULT_PLANNING_DURATION] after it starts without one. The
 * one place that rule is written: Planning, its status and the Overview all read an entry's end
 * here, so an entry built without one (in a test or a preview) lasts as long everywhere.
 */
fun PlanningTiming.Timed.endOrDefault(): Instant = end ?: instant + DEFAULT_PLANNING_DURATION

/**
 * The timing of an entry that starts at [start] and ends at [end], or [DEFAULT_PLANNING_DURATION]
 * later when [end] is unknown or not after [start] (a typo in the console shouldn't hide an event).
 * Every Planning source builds its timed entries here, so they all get an end the same way.
 */
fun timedOccurrence(start: Instant, end: Instant? = null): PlanningTiming.Timed =
    PlanningTiming.Timed(
        start,
        PlanningTiming.Timed(start, end?.takeIf { it > start }).endOrDefault(),
    )
