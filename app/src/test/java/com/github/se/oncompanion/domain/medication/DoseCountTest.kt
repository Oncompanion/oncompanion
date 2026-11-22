// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication

import org.junit.Assert.assertEquals
import org.junit.Test

class DoseCountTest {

  /** Checks each frequency gives its count, with the frequency in the failure message. */
  private fun assertCounts(cases: List<Pair<String?, Int?>>) =
      cases.forEach { (frequency, expected) ->
        assertEquals("\"$frequency\"", expected, DoseCount.of(frequency))
      }

  @Test
  fun englishWordings_giveTheirCount() =
      assertCounts(
          listOf(
              "Once a day" to 1,
              "once daily" to 1,
              "Twice a day" to 2,
              "twice daily" to 2,
              "Three times a day" to 3,
              "four times per day" to 4,
              "3 times a day" to 3,
              "3 times daily" to 3,
              "2 times daily" to 2,
              "5 times per day" to 5,
              "Daily" to 1,
              "every day" to 1,
              "1 a day" to 1,
              "bid" to 2,
              "TID" to 3,
              "qid" to 4,
              "qd" to 1,
              "Every 8 hours" to 3,
              "q12h" to 2,
              "BID" to 2,
              "24 times a day" to 24,
              "Morning and evening" to 2,
              "morning, noon and evening" to 3,
              "At bedtime" to 1,
          )
      )

  @Test
  fun scannedFrenchAndGermanWordings_andTheSwissScheme_giveTheirCount() =
      assertCounts(
          listOf(
              "1-0-1" to 2,
              // The scheme with another dash is still the scheme, not a range
              "1–0–1" to 2,
              "1 - 1 - 1" to 3,
              "1-1-1-1" to 4,
              "0-0-1" to 1,
              "2-0-0" to 1,
              "1-0-1 (matin et soir)" to 2,
              "3x/jour" to 3,
              "2 x par jour" to 2,
              "2 fois par jour" to 2,
              "1 fois/j" to 1,
              "2/j" to 2,
              "x 2/j" to 2,
              "Une fois par jour" to 1,
              "deux fois par jour" to 2,
              "trois fois par jour" to 3,
              "1 par jour" to 1,
              "par jour" to 1,
              "tous les jours" to 1,
              "Quotidien" to 1,
              "toutes les 6 heures" to 4,
              "toutes les 12h" to 2,
              "matin et soir" to 2,
              "le soir au coucher" to 1,
              "matin, midi et soir" to 3,
              "3x/jour pendant 5 jours" to 3,
              "2x täglich" to 2,
              "3 mal täglich" to 3,
              "zweimal täglich" to 2,
              "2X TÄGLICH" to 2,
              "Täglich" to 1,
              "morgens und abends" to 2,
              "morgens und nachts" to 2,
              "zur Nacht" to 1,
              "alle 8 Stunden" to 3,
              // Two wordings that agree, and "OD", which isn't read as once a day
              "1x/jour le soir" to 1,
              "1 goutte OD matin et soir" to 2,
              // A number of times still counts next to meals
              "3x/jour aux repas" to 3,
          )
      )

  @Test
  fun ifNeeded_lessThanDaily_ambiguous_orUnreadable_haveNoCount() =
      assertCounts(
          listOf(
              null to null,
              "" to null,
              "   " to null,
              // Only if needed: there is nothing to tick each day
              "If needed" to null,
              "as needed" to null,
              "si besoin" to null,
              "3x/jour si douleurs" to null,
              "1 cp si nausées" to null,
              "bei Bedarf" to null,
              "2x täglich bei Bedarf" to null,
              "1-0-1 si besoin" to null,
              "prn" to null,
              "3x/j prn" to null,
              "1-1-1 au besoin" to null,
              "1 cp si nécessaire, 3x/jour" to null,
              "1 cp en cas de nausées, 3x/j" to null,
              "3 times a day if required" to null,
              "3 times a day when required" to null,
              "3 times a day for pain" to null,
              "3x täglich bei Schmerzen" to null,
              "3x täglich bei Übelkeit" to null,
              "falls nötig 2x täglich" to null,
              "3x täglich nach Bedarf" to null,
              "2x täglich nach Fieber" to null,
              "2x täglich nach Angst" to null,
              "Reserve 3x/Tag" to null,
              "in Reserve 3x täglich" to null,
              "3x/j p.r.n." to null,
              "3x/day if pain" to null,
              "3 times a day if nauseous" to null,
              "3x/j selon besoin" to null,
              // A maximum or a range isn't a schedule to follow
              "max 3x/j" to null,
              "up to 4 times a day" to null,
              "jusqu'à 3 fois par jour" to null,
              "höchstens 2x täglich" to null,
              "1 Tbl. bis 3x täglich" to null,
              "pas plus de 3x/j" to null,
              "no more than 4 times a day" to null,
              "not more than 3 times a day" to null,
              "at most 3 times a day" to null,
              "1 à 2 fois par jour" to null,
              "1-2 fois par jour" to null,
              "3-4 times a day" to null,
              "2-3x/j" to null,
              "1 or 2 twice a day" to null,
              "1 od. 2 Tabl. täglich" to null,
              "1–2x/j" to null,
              "3–4 times a day" to null,
              "1—2 fois par jour" to null,
              "deux à trois fois par jour" to null,
              "two to three times a day" to null,
              "2 oder 3 mal täglich" to null,
              "zwei bis drei mal täglich" to null,
              "2x–3x/j" to null,
              "3x à 4x par jour" to null,
              // Two wordings that disagree
              "2x/j le matin" to null,
              // Meals aren't the same time of day everywhere
              "before lunch and dinner" to null,
              "at breakfast and lunch" to null,
              "matin et souper" to null,
              // Less often than daily
              "Once a week" to null,
              "1x/semaine" to null,
              "2 fois par semaine" to null,
              "2 mal pro Woche" to null,
              "hebdomadaire" to null,
              "monthly" to null,
              "tous les 21 jours" to null,
              "toutes les 3 semaines" to null,
              "every 2 weeks" to null,
              "every 2 days" to null,
              "every other day" to null,
              "un jour sur deux" to null,
              "alle 3 Tage" to null,
              // Ambiguous: "2 par jour" or "2 daily" can mean two tablets at once
              "2 par jour" to null,
              "2 daily" to null,
              // Every few hours that don't divide a day, or a count no one takes
              "every 5 hours" to null,
              "every 48 hours" to null,
              "25 times a day" to null,
              "30x/jour" to null,
              "0-0-0" to null,
              // Not a frequency
              "1 tablet" to null,
              "with food" to null,
          )
      )

  @Test
  fun describe_saysTheDosesADay_andTheConditionItKnows() =
      listOf(
              // A plain schedule, however it's written
              "1-0-0" to DailyDoses(1, null),
              "0-0-1" to DailyDoses(1, null),
              "1-0-1" to DailyDoses(2, null),
              "matin et soir" to DailyDoses(2, null),
              "toutes les 8 heures" to DailyDoses(3, null),
              "3x täglich" to DailyDoses(3, null),
              // With a condition: the doses a day it says, and what for
              "1-1-1 si nausées" to DailyDoses(3, DoseCondition.IF_NAUSEOUS),
              "1 cp en cas de nausées, 3x/j" to DailyDoses(3, DoseCondition.IF_NAUSEOUS),
              "3 times a day for pain" to DailyDoses(3, DoseCondition.IF_IN_PAIN),
              "2x täglich bei Fieber" to DailyDoses(2, DoseCondition.IF_FEVERISH),
              "si anxiété" to DailyDoses(null, DoseCondition.IF_ANXIOUS),
              "1-0-1 si vomissements" to DailyDoses(2, DoseCondition.IF_VOMITING),
              "once a day for sleep" to DailyDoses(1, DoseCondition.FOR_SLEEP),
              "si besoin" to DailyDoses(null, DoseCondition.IF_NEEDED),
              "1-0-1 si besoin" to DailyDoses(2, DoseCondition.IF_NEEDED),
              "bei Bedarf" to DailyDoses(null, DoseCondition.IF_NEEDED),
              "3x/j prn" to DailyDoses(3, DoseCondition.IF_NEEDED),
              "3x/j p.r.n." to DailyDoses(3, DoseCondition.IF_NEEDED),
              "Reserve 3x/Tag" to DailyDoses(3, DoseCondition.IF_NEEDED),
              "3x täglich nach Bedarf" to DailyDoses(3, DoseCondition.IF_NEEDED),
              "3x/day if pain" to DailyDoses(3, DoseCondition.IF_IN_PAIN),
              "3 times a day if nauseous" to DailyDoses(3, DoseCondition.IF_NAUSEOUS),
              "3x täglich nach Erbrechen" to DailyDoses(3, DoseCondition.IF_VOMITING),
              "2x täglich nach Fieber" to DailyDoses(2, DoseCondition.IF_FEVERISH),
              "2x täglich nach Angst" to DailyDoses(2, DoseCondition.IF_ANXIOUS),
              // As written: a condition it doesn't know, a maximum, a range, less than daily, or
              // nothing it understands
              "en cas de toux, 3x/j" to null,
              // Two counts that disagree, with a condition: as written, not "If nauseous"
              "1-1-1 si nausées, 2x/j" to null,
              "max 3x/j si nausées" to null,
              "1 à 2 fois par jour" to null,
              "1x/semaine" to null,
              "with food" to null,
              null to null,
          )
          .forEach { (frequency, expected) ->
            assertEquals("\"$frequency\"", expected, DoseCount.describe(frequency))
          }
}
