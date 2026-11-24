// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication

/** What a medication is taken on, when only then, as [DoseCount.describe] reads it. */
enum class DoseCondition {
  IF_NEEDED,
  IF_NAUSEOUS,
  IF_IN_PAIN,
  IF_FEVERISH,
  IF_ANXIOUS,
  IF_VOMITING,
  FOR_SLEEP,
}

/**
 * A frequency the Overview can say in its own words ("3 times a day if nauseous") instead of as
 * written ("1-1-1 si nausées"). At least one of the two is set.
 *
 * @property perDay how many doses a day it says, or `null` if it only says a condition ("si
 *   besoin")
 * @property condition what they're taken on, when only then, or `null` for a plain schedule
 */
data class DailyDoses(val perDay: Int?, val condition: DoseCondition?)
