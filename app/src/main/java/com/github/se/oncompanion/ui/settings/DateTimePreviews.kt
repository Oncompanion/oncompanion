// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.github.se.oncompanion.model.settings.DateFormatPreference
import com.github.se.oncompanion.model.settings.TimeFormatPreference
import com.github.se.oncompanion.ui.common.DateTimeFormats
import com.github.se.oncompanion.ui.common.rememberDateTimeFormats
import java.time.LocalDateTime
import java.util.Locale

/** Formats the same sample appointment for each choice so users can compare them. */
internal class DateTimePreviews(private val formats: DateTimeFormats) {
  constructor(
      locale: Locale,
      deviceDatePattern: String,
      deviceUses24Hours: Boolean,
  ) : this(DateTimeFormats(locale, deviceDatePattern, deviceUses24Hours))

  fun date(choice: DateFormatPreference): String = formats.date(sample.toLocalDate(), choice)

  fun time(choice: TimeFormatPreference): String = formats.time(sample.toLocalTime(), choice)

  private companion object {
    val sample: LocalDateTime = LocalDateTime.of(2026, 11, 18, 14, 30)
  }
}

/** Uses the same phone defaults and app language as the screens displaying real dates. */
@Composable
internal fun rememberDateTimePreviews(): DateTimePreviews {
  val formats = rememberDateTimeFormats()
  return remember(formats) { DateTimePreviews(formats) }
}
