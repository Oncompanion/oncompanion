// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.settings

/** The device's appearance override; system appearance remains the default. */
enum class AppearancePreference {
  FOLLOW_DEVICE,
  LIGHT,
  DARK,
}

/** The date presentation chosen on this phone. */
enum class DateFormatPreference {
  FOLLOW_DEVICE,
  TEXT_DATE,
  DAY_MONTH_YEAR,
  YEAR_MONTH_DAY,
}

/** The clock format chosen on this phone. */
enum class TimeFormatPreference {
  FOLLOW_DEVICE,
  TWELVE_HOUR,
  TWENTY_FOUR_HOUR,
}

/** Preferences shared by accounts on this phone, independently of patient data. */
data class DeviceSettings(
    val appearance: AppearancePreference = AppearancePreference.FOLLOW_DEVICE,
    val dateFormat: DateFormatPreference = DateFormatPreference.FOLLOW_DEVICE,
    val timeFormat: TimeFormatPreference = TimeFormatPreference.FOLLOW_DEVICE,
)
