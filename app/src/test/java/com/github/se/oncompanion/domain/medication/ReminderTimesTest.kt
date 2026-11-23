// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderTimesTest {

  private fun times(vararg hours: Int) = hours.map { LocalTime.of(it, 0) }

  @Test
  fun suggested_oneReminderPerDose_andOneADayWithoutACount() {
    val cases =
        listOf(
            "Once a day" to times(9),
            "Twice a day" to times(9, 18),
            "3x/jour" to times(9, 13, 18),
            "4 times a day" to times(8, 12, 16, 20),
            // No count it can remind of: one a day, the patient adds more
            "" to times(9),
            "if needed" to times(9),
            "5 times a day" to times(9),
        )
    for ((frequency, expected) in cases) {
      assertEquals(frequency, expected, ReminderTimes.suggested(frequency))
    }
  }

  @Test
  fun fit_keepsThePatientsTimes_upToTheNewCount_orAsTheyAreWithoutOne() {
    val cases =
        listOf(
            // The patient's 08:30 stays, the suggested evening is added
            Triple(
                listOf(LocalTime.of(8, 30)),
                "Twice a day",
                listOf(LocalTime.of(8, 30), LocalTime.of(18, 0)),
            ),
            Triple(times(9, 13, 18), "Once a day", times(9)),
            Triple(times(9, 18), "Three times a day", times(9, 18, 13)),
            Triple(times(9), "4 times a day", times(9, 12, 16, 20)),
            Triple(times(7, 20), "", times(7, 20)),
            // Off stays off
            Triple(emptyList(), "Twice a day", emptyList()),
        )
    for ((times, frequency, expected) in cases) {
      assertEquals("$times $frequency", expected, ReminderTimes.fit(times, frequency))
    }
  }

  @Test
  fun addingAndRemoving_onlyWithoutACount_betweenOneAndFour() {
    val cases =
        listOf(
            // times, frequency, can add, can remove
            listOf(times(9), "", true, false),
            listOf(times(9, 13), "", true, true),
            listOf(times(9, 13, 18), "", true, true),
            listOf(times(9, 13, 18, 21), "", false, true),
            listOf(emptyList<LocalTime>(), "", false, false),
            listOf(times(9, 18), "Twice a day", false, false),
        )
    for ((t, frequency, canAdd, canRemove) in cases) {
      @Suppress("UNCHECKED_CAST") val list = t as List<LocalTime>
      assertEquals("add $list", canAdd, ReminderTimes.canAdd(list, frequency as String))
      assertEquals("remove $list", canRemove, ReminderTimes.canRemove(list, frequency))
    }
    // An added time is the first usual one not taken yet
    assertEquals(LocalTime.of(13, 0), ReminderTimes.added(times(9)))
    assertEquals(LocalTime.of(21, 0), ReminderTimes.added(times(9, 13, 18)))
  }
}
