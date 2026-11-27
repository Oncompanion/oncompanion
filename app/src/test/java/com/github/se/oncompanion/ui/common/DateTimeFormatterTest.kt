// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.common

import com.github.se.oncompanion.model.settings.DateFormatPreference
import com.github.se.oncompanion.model.settings.TimeFormatPreference
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DateTimeFormatterTest {
  @Test
  fun dateChoicesKeepTheirOrderAndLeadingZeros() {
    val formats = DateTimeFormats(Locale.US, "MMM d, y", true)
    val day = LocalDate.of(2026, 3, 5)

    assertEquals("Mar 5, 2026", formats.date(day))
    assertEquals("5 Mar 2026", formats.date(day, DateFormatPreference.TEXT_DATE))
    assertEquals("05/03/2026", formats.date(day, DateFormatPreference.DAY_MONTH_YEAR))
    assertEquals("2026-03-05", formats.date(day, DateFormatPreference.YEAR_MONTH_DAY))
  }

  @Test
  fun textUsesTheAppLanguageWithThePhoneDateOrder() {
    val formats = DateTimeFormats(Locale.FRANCE, "MMM d, y", true)
    assertEquals("mars 5, 2026", formats.date(LocalDate.of(2026, 3, 5)))
  }

  @Test
  fun clockModeAndTimeLabelsAgreeForEveryChoice() {
    for (phoneUses24Hours in listOf(false, true)) {
      val expected =
          mapOf(
              TimeFormatPreference.FOLLOW_DEVICE to phoneUses24Hours,
              TimeFormatPreference.TWELVE_HOUR to false,
              TimeFormatPreference.TWENTY_FOUR_HOUR to true,
          )
      for ((choice, uses24Hours) in expected) {
        val formats = DateTimeFormats(Locale.US, "MMM d, y", phoneUses24Hours, timeChoice = choice)
        assertEquals(uses24Hours, formats.is24Hour)
        assertEquals(if (uses24Hours) "14:30" else "2:30 PM", formats.time(LocalTime.of(14, 30)))
      }
    }
  }
}
