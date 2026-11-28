// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import com.github.se.oncompanion.domain.medication.DailyDoses
import com.github.se.oncompanion.domain.medication.DoseCount
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningSource
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One of the medications to take today, on the patient's "Today's medications" card, with the doses
 * they ticked.
 *
 * @property key identifies it, the same as in Planning
 * @property medicationId the [com.github.se.oncompanion.model.medication.Medication] it is, under
 *   which its doses are saved
 * @property name its name and dosage (e.g. "Ondansetron 8 mg")
 * @property frequency the frequency recorded on the prescription, if any
 * @property dailyDoses [frequency] as the card says it in its own words ("Once a day" for "1-0-0"),
 *   or `null` to show it as written
 * @property dosesPerDay how many doses can be ticked today, read from [frequency]; `null` when
 *   there is nothing to tick (taken only if needed, a frequency that doesn't say a number) or
 *   today's ticks couldn't be read
 * @property taken how many of them the patient ticked today, from 0 to [dosesPerDay]
 */
data class TodayMedication(
    val key: String,
    val medicationId: String,
    val name: String,
    val frequency: String? = null,
    val dailyDoses: DailyDoses? = null,
    val dosesPerDay: Int? = null,
    val taken: Int = 0,
)

/**
 * The sheet where the patient corrects how many doses of a medication taken several times a day
 * they took today. Nothing is saved until Done: a sheet closed any other way, or an app stopped
 * with it open, leaves the saved count as it was.
 *
 * @property medication the medication corrected, as the card shows it now
 * @property doses its doses a day, at least 2
 * @property draft the count the patient set with − and +, from 0 to [doses]
 */
data class DosesSheet(val medication: TodayMedication, val doses: Int, val draft: Int)

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

/**
 * The count a tick saves: the next dose, up to the doses a day, or, for a medication taken once a
 * day that is already ticked, 0 (its button and row toggle). `null` when there is nothing to tick,
 * or the tick changes nothing.
 */
internal fun TodayMedication.nextTakenCount(): Int? {
  val doses = dosesPerDay ?: return null
  val count = if (doses == 1) 1 - taken.coerceAtMost(1) else (taken + 1).coerceAtMost(doses)
  return count.takeIf { it != taken }
}

/**
 * The sheet for this medication, with [draft] (its count saved for `null`), or `null` if it can't
 * be corrected there: unknown, nothing to tick, or taken once a day.
 */
internal fun TodayMedication?.dosesSheet(draft: Int? = null): DosesSheet? {
  if (this == null) return null
  val doses = dosesPerDay ?: return null
  if (doses < 2) return null
  return DosesSheet(this, doses, draft?.coerceAtMost(doses) ?: taken)
}

/** The sheet with [by] doses more (or fewer) in its draft, from 0 to its doses a day. */
internal fun DosesSheet.withDraftChangedBy(by: Int): DosesSheet =
    copy(draft = (draft + by).coerceIn(0, doses))

/** The count Done saves, or `null` when the draft is the count saved now. */
internal val DosesSheet.changedCount: Int?
  get() = draft.takeIf { it != medication.taken }
