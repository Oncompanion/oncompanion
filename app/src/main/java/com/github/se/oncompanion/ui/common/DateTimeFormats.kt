// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.common

import android.content.res.Configuration
import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.github.se.oncompanion.model.settings.DateFormatPreference
import com.github.se.oncompanion.model.settings.TimeFormatPreference
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formats display values using the saved choices and the current phone defaults. */
internal class DateTimeFormats(
    private val locale: Locale,
    private val deviceDatePattern: String,
    private val deviceUses24Hours: Boolean,
    private val dateChoice: DateFormatPreference = DateFormatPreference.FOLLOW_DEVICE,
    private val timeChoice: TimeFormatPreference = TimeFormatPreference.FOLLOW_DEVICE,
) {
  private val dates =
      DateFormatPreference.entries.associateWith { choice ->
        val pattern =
            when (choice) {
              DateFormatPreference.FOLLOW_DEVICE -> deviceDatePattern
              DateFormatPreference.TEXT_DATE -> "d MMM uuuu"
              DateFormatPreference.DAY_MONTH_YEAR -> "dd/MM/uuuu"
              DateFormatPreference.YEAR_MONTH_DAY -> "uuuu-MM-dd"
            }
        DateTimeFormatter.ofPattern(pattern, locale)
      }
  private val clock12 = DateTimeFormatter.ofPattern("h:mm a", locale)
  private val clock24 = DateTimeFormatter.ofPattern("HH:mm", locale)

  /** The effective clock mode, shared by time labels and pickers. */
  val is24Hour: Boolean
    get() = uses24Hours(timeChoice)

  /** Displays a calendar day using the saved format or an explicit Settings preview choice. */
  fun date(value: LocalDate, choice: DateFormatPreference = dateChoice): String =
      value.format(dates.getValue(choice))

  /** Displays a time of day using the saved format or an explicit Settings preview choice. */
  fun time(value: LocalTime, choice: TimeFormatPreference = timeChoice): String =
      value.format(if (uses24Hours(choice)) clock24 else clock12)

  private fun uses24Hours(choice: TimeFormatPreference): Boolean =
      when (choice) {
        TimeFormatPreference.FOLLOW_DEVICE -> deviceUses24Hours
        TimeFormatPreference.TWELVE_HOUR -> false
        TimeFormatPreference.TWENTY_FOUR_HOUR -> true
      }
}

/** Resolves phone defaults, refreshing the phone clock mode when the activity resumes. */
@Composable
internal fun rememberDateTimeFormats(
    dateChoice: DateFormatPreference = DateFormatPreference.FOLLOW_DEVICE,
    timeChoice: TimeFormatPreference = TimeFormatPreference.FOLLOW_DEVICE,
): DateTimeFormats {
  val context = LocalContext.current
  val locale = LocalConfiguration.current.locales[0]
  // Use the system locale even when Oncompanion has its own language override.
  val deviceLocale = Resources.getSystem().configuration.locales[0]
  val pattern = remember(deviceLocale) { DateFormat.getBestDateTimePattern(deviceLocale, "yMMMd") }
  val deviceContext =
      remember(context, deviceLocale) {
        context.createConfigurationContext(Configuration().apply { setLocale(deviceLocale) })
      }
  var use24Hours by
      remember(deviceContext) { mutableStateOf(DateFormat.is24HourFormat(deviceContext)) }
  LifecycleResumeEffect(deviceContext) {
    use24Hours = DateFormat.is24HourFormat(deviceContext)
    onPauseOrDispose {}
  }
  return remember(locale, pattern, use24Hours, dateChoice, timeChoice) {
    DateTimeFormats(locale, pattern, use24Hours, dateChoice, timeChoice)
  }
}

/** The resolved date and time preferences supplied by the app root. */
internal val LocalDateTimeFormats = compositionLocalOf<DateTimeFormats?> { null }
