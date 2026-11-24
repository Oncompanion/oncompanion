// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.github.se.oncompanion.model.settings.DateFormatPreference
import com.github.se.oncompanion.model.settings.TimeFormatPreference
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formats the same sample appointment for each choice so users can compare them. */
internal class DateTimePreviews(
    private val locale: Locale,
    private val deviceDatePattern: String,
    private val deviceUses24Hours: Boolean,
) {
  fun date(choice: DateFormatPreference): String =
      sample.format(
          DateTimeFormatter.ofPattern(
              when (choice) {
                DateFormatPreference.FOLLOW_DEVICE -> deviceDatePattern
                DateFormatPreference.TEXT_DATE -> "d MMM uuuu"
                DateFormatPreference.DAY_MONTH_YEAR -> "dd/MM/uuuu"
                DateFormatPreference.YEAR_MONTH_DAY -> "uuuu-MM-dd"
              },
              locale,
          )
      )

  fun time(choice: TimeFormatPreference): String {
    val use24Hours =
        when (choice) {
          TimeFormatPreference.FOLLOW_DEVICE -> deviceUses24Hours
          TimeFormatPreference.TWELVE_HOUR -> false
          TimeFormatPreference.TWENTY_FOUR_HOUR -> true
        }
    return sample.format(DateTimeFormatter.ofPattern(if (use24Hours) "HH:mm" else "h:mm a", locale))
  }

  private companion object {
    val sample: LocalDateTime = LocalDateTime.of(2026, 11, 18, 14, 30)
  }
}

/** Reads the phone's defaults again when returning from Android Settings. */
@Composable
internal fun rememberDateTimePreviews(): DateTimePreviews {
  val context = LocalContext.current
  val locale = LocalConfiguration.current.locales[0]
  var use24Hours by remember(context, locale) { mutableStateOf(DateFormat.is24HourFormat(context)) }
  LifecycleResumeEffect(context) {
    use24Hours = DateFormat.is24HourFormat(context)
    onPauseOrDispose {}
  }
  val deviceLocale = Resources.getSystem().configuration.locales[0]
  val pattern = remember(deviceLocale) { DateFormat.getBestDateTimePattern(deviceLocale, "yMMMd") }
  return remember(locale, pattern, use24Hours) { DateTimePreviews(locale, pattern, use24Hours) }
}
