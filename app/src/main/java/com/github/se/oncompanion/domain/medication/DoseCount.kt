// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication

import com.github.se.oncompanion.model.medication.TakenDosesRepository.Companion.MAX_TAKEN_PER_DAY
import com.github.se.oncompanion.model.medication.scan.comparable

/**
 * How many doses a day a medication's frequency says, so the patient can tick them on the Overview
 * (US-28). The frequency is free text, typed by the patient or kept as read from a scanned
 * prescription, so English, French and German wordings and the Swiss scheme are read.
 *
 * It only counts what the text says plainly: anything taken only if needed, up to a maximum or as a
 * range of doses, less often than daily (weekly, every few days, a cycle) or not understood has no
 * count, since a wrong number of doses would be worse than none. Two wordings that disagree ("2x/j
 * le matin") have none either. The scanner's word lists say whether a text is a frequency at all;
 * this answers another question, how many a day, and shares their text cleanup ([comparable]).
 */
object DoseCount {

  /**
   * The number of doses a day [frequency] says, or `null` if it doesn't say one plainly, or says
   * they're taken only on a condition: there is then nothing to tick each day.
   */
  fun of(frequency: String?): Int? = describe(frequency)?.takeIf { it.condition == null }?.perDay

  /**
   * [frequency] as the Overview can say it: its doses a day and the condition they're taken on, or
   * `null` to show it as written. That is when it says neither, says a condition the app doesn't
   * know ("en cas de toux"), a maximum, a range, or less often than daily: rewording those could
   * lose what the prescription says.
   */
  fun describe(frequency: String?): DailyDoses? {
    if (frequency.isNullOrBlank()) return null
    val text = comparable(frequency).replace(DASHES, "-")
    if (saysNoDailyCount(text)) return null
    val condition =
        CONDITIONS.entries.firstOrNull { (_, words) -> words.any { it.containsMatchIn(text) } }?.key
    if (condition == null && OTHER_CONDITION.containsMatchIn(text)) return null
    // Every wording that says a count must agree; "daily" alone only counts when none other does
    val counts = readers.mapNotNull { read -> read(text) }.distinct()
    val count =
        (if (counts.isEmpty()) onceADay(text) else counts.singleOrNull())
            // More than can be recorded, one an hour, is a misreading
            ?.takeIf { it in 1..MAX_TAKEN_PER_DAY }
    return if (count == null && condition == null) null else DailyDoses(count, condition)
  }

  /**
   * [text] says the doses are taken up to a maximum, as a range or less often than daily: whatever
   * number it also says isn't a count of doses to take each day.
   */
  private fun saysNoDailyCount(text: String): Boolean {
    // Without the Swiss scheme, so "1-0-1" isn't read as a range
    val withoutScheme = SCHEME.replace(text, " ")
    return listOf(UP_TO, LESS_THAN_DAILY).any { words -> words.any { it.containsMatchIn(text) } } ||
        RANGE.any { it.containsMatchIn(withoutScheme) }
  }

  /** "1-0-1", "1-1-1-1": one dose at each time of the day whose number isn't 0. */
  private fun schemeCount(text: String): Int? =
      SCHEME.find(text)
          ?.groupValues
          ?.drop(1)
          ?.count { it.isNotEmpty() && it != "0" }
          ?.takeIf { it > 0 }

  /** "3x/jour", "2 fois par jour", "3 times a day", "2x taglich", "2/j", "x 2/j". */
  private fun timesADay(text: String): Int? = TIMES_A_DAY.firstNotNullOfOrNull {
    it.find(text)?.groupValues?.get(1)?.toIntOrNull()
  }

  /** "twice a day", "deux fois par jour", "zweimal taglich", and "bid", "tid", "qid", "qd". */
  private fun wordCount(text: String): Int? =
      WORD_COUNTS.entries.firstOrNull { (words, _) -> words.containsMatchIn(text) }?.value

  /** "q8h", "toutes les 8 heures", "every 12 hours", "alle 8 Stunden": only if it divides a day. */
  private fun everyFewHours(text: String): Int? =
      EVERY_FEW_HOURS.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.toIntOrNull() }
          ?.takeIf { it in 1..HOURS_IN_A_DAY && HOURS_IN_A_DAY % it == 0 }
          ?.let { HOURS_IN_A_DAY / it }

  /**
   * "matin et soir", "morning, noon and evening", "abends": one dose at each time named. "Le soir
   * au coucher" names one time twice, so it counts once. A meal isn't a time of day everywhere
   * ("dîner" is lunch in Switzerland and dinner in France), so a text naming one isn't counted
   * here.
   */
  private fun namedTimes(text: String): Int? {
    if (MEALS.containsMatchIn(text)) return null
    val named = AT_BEDTIME_IN_THE_EVENING.replace(text, "soir")
    return TIMES_OF_DAY.count { it.containsMatchIn(named) }.takeIf { it > 0 }
  }

  /**
   * Once a day, said without a number of times ("daily", "1 par jour", "par jour" alone), when no
   * other wording says a count. Not after a number other than 1 ("2 daily"): it can mean two
   * tablets at once or twice a day.
   */
  private fun onceADay(text: String): Int? =
      1.takeIf {
        DAILY.any { it.containsMatchIn(text) } && !NUMBER_BEFORE_DAILY.containsMatchIn(text)
      }

  private val readers: List<(String) -> Int?> =
      listOf(::schemeCount, ::timesADay, ::wordCount, ::everyFewHours, ::namedTimes)

  private const val HOURS_IN_A_DAY = 24

  /** The conditions a medication is taken on, the symptoms before plain "if needed". */
  private val CONDITIONS =
      linkedMapOf(
          DoseCondition.IF_NAUSEOUS to
              listOf(
                  Regex("""\b(?:si|en cas de) nausees?\b|\b(?:bei|nach) ubelkeit\b"""),
                  Regex("""\b(?:for|if) (?:nausea|nauseous)\b"""),
              ),
          DoseCondition.IF_IN_PAIN to
              listOf(
                  Regex("""\b(?:si|en cas de) douleurs?\b|\b(?:bei|nach) schmerzen\b"""),
                  Regex("""\b(?:for|if) pain\b"""),
              ),
          DoseCondition.IF_FEVERISH to
              listOf(
                  Regex("""\b(?:si|en cas de) fievre\b|\b(?:for|if) fever\b"""),
                  Regex("""\b(?:bei|nach) fieber\b"""),
              ),
          DoseCondition.IF_ANXIOUS to
              listOf(
                  Regex("""\b(?:si|en cas de) anxiete\b|\b(?:for|if) anxiety\b"""),
                  Regex("""\b(?:bei|nach) angst\b"""),
              ),
          DoseCondition.IF_VOMITING to
              listOf(
                  Regex("""\b(?:si|en cas de) vomissements?\b|\b(?:bei|nach) erbrechen\b"""),
                  Regex("""\b(?:for|if) vomiting\b"""),
              ),
          DoseCondition.FOR_SLEEP to listOf(Regex("""\b(?:for|if) sleep\b""")),
          DoseCondition.IF_NEEDED to
              listOf(
                  // "Reserve" alone is the German for it, "en réserve" the French
                  Regex(
                      """\b(?:si besoin|au besoin|selon (?:le )?besoin|reserve|si necessaire)\b"""
                  ),
                  Regex("""\b(?:bei|nach) bedarf\b|\bfalls notig\b"""),
                  Regex("""\b(?:if|as|when) (?:needed|required|necessary)\b|\bp\.?r\.?n\b"""),
              ),
      )

  /** A condition none of [CONDITIONS] says: "en cas de toux". */
  private val OTHER_CONDITION = Regex("""\ben cas de\b""")

  /**
   * A maximum, not a schedule: "max 3x/j", "up to 4 times a day", "höchstens 2x täglich", "pas plus
   * de 3x/j", "no more than 4 times a day", "bis 3x täglich".
   */
  private val UP_TO =
      listOf(
          Regex("""\b(?:max|maximum|up to|jusqu.?a|au plus|pas plus de|hochstens|bis zu)\b"""),
          Regex("""\b(?:(?:no|not) more than|at most)\b|\bbis\s+(?=\d)"""),
      )

  /**
   * A range: "1 à 2 fois par jour", "3-4 times a day", "2x-3x/j", "1 or 2", "2 oder 3", "deux à
   * trois fois", "two to three times". Looked for once the Swiss scheme is taken out, so "1-0-1"
   * isn't read as one.
   */
  private val RANGE =
      listOf(
          Regex("""(?<!\d)\d{1,2}\s*x?\s*(?:-|a|to|or|ou|bis|oder|od\.?)\s*\d{1,2}(?!\d)"""),
          // In words, one pattern per language
          Regex("""\b(?:une?|deux|trois|quatre) (?:a|ou) (?:deux|trois|quatre|cinq|six)\b"""),
          Regex("""\b(?:one|two|three|four) (?:to|or) (?:two|three|four|five|six)\b"""),
          Regex("""\b(?:eine?|zwei|drei|vier) (?:bis|oder) (?:zwei|drei|vier|funf|sechs)\b"""),
      )

  /** Dashes other than "-": "1–2" is then a range, and "1–0–1" the Swiss scheme. */
  private val DASHES = Regex("""[‐-―−]""")

  private val LESS_THAN_DAILY =
      listOf(
          // Weekly or monthly: "1x/semaine", "once a week", "2 mal pro Woche", "hebdomadaire"
          Regex("""(?:\b(?:par|per|a|pro)\s+|/\s*)(?:semaine|sem|week|woche|mois|month|monat)\b"""),
          Regex("""\b(?:weekly|monthly|hebdomadaire|mensuel(?:le)?|wochentlich|monatlich)\b"""),
          // Every few days or weeks: "tous les 21 jours", "every 2 days", "alle 3 Wochen"
          Regex("""\b(?:tous les|toutes les|every|alle)\s+\d+\s*(?:jours?|days?|tage?)\b"""),
          Regex("""\b(?:toutes les|every|alle)\s+\d+\s*(?:semaines?|weeks?|wochen?)\b"""),
          Regex("""\b(?:every other day|un jour sur deux|1 jour sur 2|jeden zweiten tag)\b"""),
      )

  private val SCHEME = Regex("""(?<![\d-])(\d)\s*-\s*(\d)\s*-\s*(\d)(?:\s*-\s*(\d))?(?![\d-])""")

  private val TIMES_A_DAY =
      listOf(
          Regex(
              """\b(\d{1,2})\s*(?:x|fois|times|mal)\s*(?:/|par|per|a|pro)?\s*(?:jour|j|day|d|tag)\b"""
          ),
          // "x", "times" or "mal" is needed: "2 daily" can mean two tablets once a day
          Regex("""\b(\d{1,2})\s*(?:x|mal|times|fois)\s*(?:taglich|daily|quotidien(?:ne)?)\b"""),
          Regex("""(?:\bx\s*)?\b(\d{1,2})\s*/\s*(?:jour|j|day|d)\b"""),
      )

  private val WORD_COUNTS =
      linkedMapOf(
          Regex("""\b(?:once|one time)\s+(?:a|per)\s+day\b|\bonce daily\b""") to 1,
          Regex("""\btwice\s+(?:a|per)\s+day\b|\btwice daily\b""") to 2,
          Regex("""\bthree times\s+(?:a|per)\s+day\b|\bthree times daily\b""") to 3,
          Regex("""\bfour times\s+(?:a|per)\s+day\b|\bfour times daily\b""") to 4,
          // Not "od": also the right eye on eye drops, and "oder" in German
          Regex("""\bune fois par jour\b|\beinmal taglich\b|\bqd\b""") to 1,
          Regex("""\bdeux fois par jour\b|\bzweimal taglich\b|\bbid\b""") to 2,
          Regex("""\btrois fois par jour\b|\bdreimal taglich\b|\btid\b""") to 3,
          Regex("""\bquatre fois par jour\b|\bviermal taglich\b|\bqid\b""") to 4,
      )

  private val EVERY_FEW_HOURS =
      listOf(
          Regex("""\bq(\d{1,2})h\b"""),
          Regex(
              """\b(?:tous les|toutes les|every|alle)\s+(\d{1,2})\s*(?:h|heures?|hours?|stunden?)\b"""
          ),
      )

  /** The times of a day a dose can be named by, each counted once. */
  private val TIMES_OF_DAY =
      listOf(
          Regex("""\b(?:matin|morning|morgens)\b"""),
          Regex("""\b(?:midi|noon|mittags)\b"""),
          Regex("""\b(?:soir|evening|abends)\b"""),
          Regex("""\b(?:coucher|nuit|night|bedtime|zur nacht|nachts)\b"""),
      )

  /** Meals, which aren't at the same time of day everywhere, so never counted as one. */
  private val MEALS =
      Regex(
          """\b(?:breakfast|lunch|dinner|supper|meals?|repas|dejeuner|diner|souper|""" +
              """fruhstuck|mittagessen|abendessen|mahlzeit(?:en)?)\b"""
      )

  private val NUMBER_BEFORE_DAILY =
      Regex("""\b(?:[02-9]|\d{2,})\s*(?:daily|taglich|quotidien(?:ne)?)\b""")

  private val AT_BEDTIME_IN_THE_EVENING = Regex("""\bsoir\s+(?:au|avant le)\s+coucher\b""")

  /**
   * Once a day without a number of times. "2 par jour" isn't read: it can mean two tablets at once.
   */
  private val DAILY =
      listOf(
          Regex("""\b(?:daily|every day|tous les jours|taglich|quotidien(?:ne)?)\b"""),
          Regex("""\b1\s*(?:par|per|a|pro)\s*(?:jour|day|tag)\b"""),
          Regex("""^(?:par jour|per day|a day|pro tag)$"""),
      )
}
