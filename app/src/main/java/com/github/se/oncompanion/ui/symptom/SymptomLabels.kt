// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.symptom.Severity
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The label of a symptom type, as shown in the app (Figma: "Add symptom"). */
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

/** The label of a severity level, as shown in the journal (Figma: "Symptoms – History"). */
@get:StringRes
val Severity.label: Int
  get() =
      when (this) {
        Severity.MILD -> R.string.symptom_severity_mild
        Severity.MODERATE -> R.string.symptom_severity_moderate
        Severity.SEVERE -> R.string.symptom_severity_severe
      }

/** "Mon 28 Sep", as in the mockups. The app's UI is in English. */
private val dayFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

/** "Mon 28 Sep 2025", for a day of another year. */
private val dayWithYearFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)

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
      date.year == today.year -> date.format(dayFormatter)
      else -> date.format(dayWithYearFormatter)
    }

/**
 * What [type] is called in this entry: for "Other", the name the user gave it ("Hiccups"), or
 * "Other" in entries saved before it could be named; otherwise its label.
 */
@Composable
fun SymptomEntry.nameOf(type: SymptomType): String =
    otherName?.takeIf { type == SymptomType.OTHER } ?: stringResource(type.label)

/** What the entry is about: the names of its symptoms, e.g. "Fatigue, Hiccups". */
@Composable fun SymptomEntry.title(): String = types.map { nameOf(it) }.joinToString(", ")

/**
 * The symptoms given a strength, said in words in the order they were picked: "Moderate fatigue ·
 * mild nausea" (Figma: journal). The name of "Other" is kept as the user typed it. `null` for an
 * entry without strengths, as one saved before them.
 */
@Composable
fun SymptomEntry.strengthsInWords(): String? {
  val phrases =
      strengthsInOrder().map { (type, strength) ->
        val name =
            otherName?.takeIf { type == SymptomType.OTHER }
                ?: stringResource(type.label).lowercase(Locale.ENGLISH)
        val severity = stringResource(Severity.of(strength).label).lowercase(Locale.ENGLISH)
        stringResource(R.string.symptom_strength_in_words, severity, name)
      }
  if (phrases.isEmpty()) return null
  val joined = phrases.reduce { all, phrase ->
    stringResource(R.string.symptom_journal_summary, all, phrase)
  }
  return joined.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
}
