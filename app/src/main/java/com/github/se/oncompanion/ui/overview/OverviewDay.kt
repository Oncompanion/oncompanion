// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import com.github.se.oncompanion.domain.medication.DoseCount
import com.github.se.oncompanion.domain.planning.endOrDefault
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// What the patient's and the caregiver's Overviews both work out from the planning: Next up,
// today's
// medications, and when the day shown has to be refreshed

/** How far ahead the next appointment is looked for. */
private val LOOKAHEAD: Period = Period.ofYears(1)

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * The planning the Overview reads in [zoneId]: from [today] to [LOOKAHEAD] later, so the next
 * appointment is found even months ahead, as between two oncology check-ups.
 */
internal fun overviewRange(today: LocalDate, zoneId: ZoneId) =
    PlanningRange(today, today.plus(LOOKAHEAD), zoneId)

/**
 * Keeps the day right while the Overview is open: calls [refreshNow] now, then each time what it
 * shows changes (see [nextChangeAfter]), until the returned job is cancelled. When [items] change,
 * the next refresh is planned again from them.
 */
internal fun CoroutineScope.followDay(
    items: StateFlow<List<PlanningItem>>,
    clock: Clock,
    zoneId: ZoneId,
    refreshNow: () -> Unit,
): Job = launch {
  items.collectLatest { planned ->
    while (true) {
      refreshNow()
      val now = clock.instant()
      val wait = Duration.between(now, nextChangeAfter(planned, now, zoneId))
      // Rounded up, so it never wakes just before the change; at least 1 ms, so it always
      // suspends even if the clock moved
      delay(((wait.toNanos() + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI).coerceAtLeast(1))
    }
  }
}

/**
 * The next moment the Overview's content changes after [now]: an entry starts (Next up moves on, an
 * entry is happening), an entry ends (it is done), or midnight (the new day loads). Midnight is
 * always one of them, so it is never more than a day away. In instants, so the hour repeated when
 * the clocks go back can't give a moment already past.
 */
private fun nextChangeAfter(planned: List<PlanningItem>, now: Instant, zoneId: ZoneId): Instant =
    (planned
            .mapNotNull { it.timing as? PlanningTiming.Timed }
            .flatMap { listOf(it.instant, it.endOrDefault()) } +
            now.atZone(zoneId).toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant())
        .filter { it.isAfter(now) }
        .min()

/**
 * The next event or appointment that hasn't started yet, today or on a later day, so a check-up
 * weeks away still shows. An event is in the planning only once the patient registered for it, so a
 * later one shows too.
 */
internal fun nextUpOf(planned: List<PlanningItem>, now: LocalDateTime, zoneId: ZoneId): NextUp? {
  val next =
      planned
          .mapNotNull { item ->
            item.startIn(zoneId)?.takeIf { it.isAfter(now) }?.let { start -> item to start }
          }
          .minByOrNull { it.second }
  return next?.let { (item, start) ->
    when (val source = item.source) {
      is PlanningSource.Appointment ->
          NextUp(
              TodayItemKind.APPOINTMENT,
              source.appointmentId,
              item.title,
              start,
              item.subtitle,
          )
      is PlanningSource.Event ->
          NextUp(TodayItemKind.EVENT, source.eventId, item.title, start, item.subtitle)
      // A medication has no time, so it's never upcoming
      is PlanningSource.Medication -> null
    }
  }
}

/**
 * Whether an event or appointment of today has already started at [now], so a day without anything
 * upcoming isn't one where nothing was planned.
 */
internal fun startedToday(planned: List<PlanningItem>, now: LocalDateTime, zoneId: ZoneId) =
    planned.any { item ->
      item.startIn(zoneId)?.let { it.toLocalDate() == now.toLocalDate() && !it.isAfter(now) } ==
          true
    }

/**
 * The medications of [planned] to take on [now]'s day, with their dosage after their name and the
 * doses ticked, the same on the patient's and the caregiver's Overviews. One a caregiver added
 * stays off until the patient confirms its prescription: it isn't part of their treatment before
 * (Planning still lists it).
 *
 * @param taken the doses ticked that day, by medication ID; `null` if they couldn't be read, so
 *   nothing shows as ticked or to tick
 */
internal fun todayMedicationsOf(
    planned: List<PlanningItem>,
    now: LocalDateTime,
    zoneId: ZoneId,
    taken: Map<String, Int>?,
): List<TodayMedication> =
    planned
        .filter { it.isConfirmed && it.timing.dateIn(zoneId) == now.toLocalDate() }
        .mapNotNull { item ->
          val source = item.source as? PlanningSource.Medication ?: return@mapNotNull null
          val frequency = item.frequency?.takeIf { it.isNotBlank() }
          val doses = DoseCount.of(frequency).takeIf { taken != null }
          TodayMedication(
              key = item.key,
              medicationId = source.medicationId,
              name =
                  listOfNotNull(item.title, item.dosage?.takeIf { it.isNotBlank() })
                      .joinToString(" "),
              frequency = frequency,
              dailyDoses = DoseCount.describe(frequency),
              dosesPerDay = doses,
              taken = doses?.let { taken?.get(source.medicationId)?.coerceIn(0, it) } ?: 0,
          )
        }

/** When a timed entry ends, in [zoneId]. */
internal fun PlanningItem.endIn(zoneId: ZoneId): LocalDateTime =
    LocalDateTime.ofInstant((timing as PlanningTiming.Timed).endOrDefault(), zoneId)

/** When a timed entry starts, in [zoneId]; `null` for an entry without a time. */
internal fun PlanningItem.startIn(zoneId: ZoneId): LocalDateTime? =
    (timing as? PlanningTiming.Timed)?.let { LocalDateTime.ofInstant(it.instant, zoneId) }
