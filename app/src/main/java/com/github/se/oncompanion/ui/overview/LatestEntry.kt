// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.ui.symptom.nameOf
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A journal entry as the Overview says it in one line: "Nausea 7/10 · Fatigue 6/10". The
 * caregiver's Overview shows the patient's newest one, the patient's own the one they just saved.
 *
 * @property entry the entry itself, for its ID and the names of its symptoms ("Other" may have one)
 * @property at when the patient felt it, in the Overview's time zone
 * @property symptoms each symptom with its strength out of 10, when the entry has one per symptom
 * @property overall the entry's single intensity, for entries saved before a strength per symptom;
 *   `null` when the symptoms have their own
 */
data class LatestEntry(
    val entry: SymptomEntry,
    val at: LocalDateTime,
    val symptoms: List<Pair<SymptomType, Int?>>,
    val overall: Int?,
)

/**
 * This entry as the Overview says it, in [zoneId]: a strength for each symptom when it has them,
 * otherwise its single intensity once, for all of its symptoms.
 */
fun SymptomEntry.toLatestEntry(zoneId: ZoneId): LatestEntry =
    LatestEntry(
        entry = this,
        at = LocalDateTime.ofInstant(occurredAt, zoneId),
        symptoms = types.map { it to strengths[it] },
        overall = intensity.takeIf { strengths.isEmpty() },
    )

/**
 * "Nausea 7/10 · Fatigue 6/10" with a strength per symptom, "Nausea, Fatigue · 6/10" with one
 * intensity for the entry, "Nausea, Fatigue" without any; "Other" goes by the name it was given.
 */
@Composable
internal fun entrySummary(latest: LatestEntry): String {
  val symptoms =
      latest.symptoms.map { (type, strength) ->
        val name = latest.entry.nameOf(type)
        strength?.let { stringResource(R.string.overview_entry_strength, name, it) } ?: name
      }
  val withStrengths = latest.symptoms.any { it.second != null }
  val list = symptoms.joinToString(if (withStrengths) STRENGTH_SEPARATOR else NAME_SEPARATOR)
  val overall = latest.overall ?: return list
  return stringResource(R.string.overview_entry_overall, list, overall)
}

/** Between symptoms that each have a strength. */
private const val STRENGTH_SEPARATOR = " · "

/** Between the names alone, as in the journal's titles. */
private const val NAME_SEPARATOR = ", "
