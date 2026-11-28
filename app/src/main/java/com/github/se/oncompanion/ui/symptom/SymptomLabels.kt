// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.symptom

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.symptom.Severity
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.ui.common.formatDateHeading
import com.github.se.oncompanion.ui.common.rememberAppDateFormatter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The label of a symptom type, as shown in the app. */
@get:StringRes
val SymptomType.label: Int
  get() =
      when (this) {
        SymptomType.FATIGUE -> R.string.symptom_type_fatigue
        SymptomType.NAUSEA -> R.string.symptom_type_nausea
        SymptomType.PAIN -> R.string.symptom_type_pain
        SymptomType.FEVER -> R.string.symptom_type_fever
        SymptomType.HEADACHE -> R.string.symptom_type_headache
        SymptomType.APPETITE_LOSS -> R.string.symptom_type_appetite_loss
        SymptomType.SLEEP_PROBLEMS -> R.string.symptom_type_sleep_problems
        SymptomType.BREATHLESSNESS -> R.string.symptom_type_breathlessness
        SymptomType.OTHER -> R.string.symptom_type_other
      }

/** The icon of a symptom type, as on the check-in tiles; "Other" has the dots of "More…". */
@get:DrawableRes
val SymptomType.icon: Int
  get() =
      when (this) {
        SymptomType.FATIGUE -> R.drawable.ic_symptom_fatigue
        SymptomType.NAUSEA -> R.drawable.ic_symptom_nausea
        SymptomType.PAIN -> R.drawable.ic_symptom_pain
        SymptomType.FEVER -> R.drawable.ic_symptom_fever
        SymptomType.HEADACHE -> R.drawable.ic_symptom_headache
        SymptomType.APPETITE_LOSS -> R.drawable.ic_symptom_appetite
        SymptomType.SLEEP_PROBLEMS -> R.drawable.ic_symptom_sleep
        SymptomType.BREATHLESSNESS -> R.drawable.ic_symptom_breathlessness
        SymptomType.OTHER -> R.drawable.ic_symptom_more
      }

/** The label of a severity level, as shown in the journal (design: M3, "Journal · My symptoms"). */
@get:StringRes
val Severity.label: Int
  get() =
      when (this) {
        Severity.MILD -> R.string.symptom_severity_mild
        Severity.MODERATE -> R.string.symptom_severity_moderate
        Severity.SEVERE -> R.string.symptom_severity_severe
      }

/** "Mon 28 Sep". Day and month names follow the app language. */
private val dayFormatter
  @Composable get() = rememberAppDateFormatter("EEE d MMM")

/** "Mon 28 Sep 2025", for a day of another year. */
private val dayWithYearFormatter
  @Composable get() = rememberAppDateFormatter("EEE d MMM yyyy")

/** "14:30", the time of day shown by the symptom screens. */
internal val symptomTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/**
 * "Today", "Yesterday" or the day of [date], seen on [today]; with its year if it isn't this year.
 */
@Composable
fun dayLabel(date: LocalDate, today: LocalDate): String =
    when {
      date == today -> stringResource(R.string.symptom_journal_today)
      date == today.minusDays(1) -> stringResource(R.string.symptom_journal_yesterday)
      date.year == today.year -> dayFormatter.formatDateHeading(date)
      else -> dayWithYearFormatter.formatDateHeading(date)
    }

/**
 * What [type] is called in this entry: for "Other", the name the user gave it ("Hiccups"), or
 * "Other" in entries saved before it could be named; otherwise its label.
 */
@Composable fun SymptomEntry.nameOf(type: SymptomType): String = symptomName(type, otherName)

/** What [type] is called: for "Other", the name the user gave it, if any; otherwise its label. */
@Composable
fun symptomName(type: SymptomType, otherName: String?): String =
    otherName?.takeIf { type == SymptomType.OTHER } ?: stringResource(type.label)

/**
 * What [type] is called inside a sentence: the label in lower case ("fatigue"), but the name of
 * "Other" kept as the user typed it ("Hiccups").
 */
@Composable
fun nameInSentence(type: SymptomType, otherName: String?): String =
    otherName?.takeIf { type == SymptomType.OTHER }
        ?: stringResource(type.label).lowercase(Locale.ENGLISH)

/**
 * The question over the slider for [type]: "How strong is your fatigue? (optional)", "How strong
 * are your sleep problems? (optional)", or "How strong is it? (optional)" for "Other" not named yet
 * (design: M3, "Check-in · Other, not named yet"). A name the user gave "Other" takes "is".
 */
@Composable
fun strengthQuestion(type: SymptomType, otherName: String?): String =
    when {
      type == SymptomType.OTHER && otherName == null ->
          stringResource(R.string.symptom_add_strength_question_other)
      type in PLURAL_LABELS ->
          stringResource(
              R.string.symptom_add_strength_question_plural,
              nameInSentence(type, otherName),
          )
      else ->
          stringResource(R.string.symptom_add_strength_question, nameInSentence(type, otherName))
    }

/** The symptoms whose label is a plural ("Sleep problems"), so a sentence says "are" about them. */
private val PLURAL_LABELS = setOf(SymptomType.SLEEP_PROBLEMS)

/** What the entry is about: the names of its symptoms, e.g. "Fatigue, Hiccups". */
@Composable fun SymptomEntry.title(): String = types.map { nameOf(it) }.joinToString(", ")

/**
 * The symptoms given a strength, said in words in the order they were picked: "Moderate fatigue ·
 * mild nausea" (design: M3, "Journal · My symptoms"). The name of "Other" is kept as the user typed
 * it. `null` for an entry without strengths, as one saved before them.
 */
@Composable
fun SymptomEntry.strengthsInWords(): String? {
  // French begins with the symptom label, which may be a name entered by the user.
  val symptomFirst = LocalConfiguration.current.locales[0].language == Locale.FRENCH.language
  val phrases =
      strengthsInOrder().map { (type, strength) ->
        val name = if (symptomFirst) nameOf(type) else nameInSentence(type, otherName)
        val severity = stringResource(Severity.of(strength).label).lowercase(Locale.ENGLISH)
        stringResource(R.string.symptom_strength_in_words, severity, name)
      }
  if (phrases.isEmpty()) return null
  val joined = phrases.reduce { all, phrase ->
    stringResource(R.string.symptom_journal_summary, all, phrase)
  }
  if (symptomFirst) return joined
  return joined.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
}
