// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.symptom

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SymptomEntryTest {

  private val valid =
      SymptomEntry(
          types = listOf(SymptomType.FATIGUE),
          occurredAt = Instant.parse("2026-10-05T08:00:00Z"),
      )

  private val other = valid.copy(types = listOf(SymptomType.OTHER), otherName = "Hiccups")

  @Test
  fun constantsHaveSpecifiedValues() {
    assertEquals(1, SymptomEntry.MIN_INTENSITY)
    assertEquals(10, SymptomEntry.MAX_INTENSITY)
    assertEquals(1000, SymptomEntry.MAX_NOTES_LENGTH)
    assertEquals(40, SymptomEntry.MAX_OTHER_NAME_LENGTH)
    assertEquals(Duration.ofMinutes(5), SymptomEntry.MAX_CLOCK_DRIFT)
  }

  @Test
  fun optionalFieldsDefaultToEmpty() {
    assertEquals("", valid.id)
    assertNull(valid.intensity)
    assertNull(valid.notes)
    assertNull(valid.createdAt)
  }

  @Test
  fun typesMatchTheFigmaList() {
    assertEquals(
        listOf(
            SymptomType.FATIGUE,
            SymptomType.NAUSEA,
            SymptomType.PAIN,
            SymptomType.FEVER,
            SymptomType.HEADACHE,
            SymptomType.APPETITE_LOSS,
            SymptomType.SLEEP_PROBLEMS,
            SymptomType.BREATHLESSNESS,
            SymptomType.OTHER,
        ),
        SymptomType.entries,
    )
  }

  @Test
  fun everyListedTypeAloneIsValid_withoutIntensityOrNotes() {
    SymptomType.entries
        .filter { it != SymptomType.OTHER }
        .forEach { assertTrue("$it", valid.copy(types = listOf(it)).isValid()) }
  }

  @Test
  fun severalSymptomsAreValid() {
    assertTrue(valid.copy(types = listOf(SymptomType.FATIGUE, SymptomType.HEADACHE)).isValid())
    assertTrue(valid.copy(types = SymptomType.entries.toList(), otherName = "Hiccups").isValid())
  }

  @Test
  fun atLeastOneSymptomIsNeeded() {
    assertFalse(valid.copy(types = emptyList()).isValid())
  }

  @Test
  fun aSymptomCantBeListedTwice() {
    assertFalse(valid.copy(types = listOf(SymptomType.PAIN, SymptomType.PAIN)).isValid())
  }

  @Test
  fun fullEntryIsValid() {
    assertTrue(
        valid
            .copy(id = "s1", intensity = 5, notes = "After lunch", createdAt = Instant.now())
            .isValid()
    )
  }

  @Test
  fun intensityIsOptional_butWithinBoundsWhenGiven() {
    assertTrue(valid.copy(intensity = null).isValid())
    assertTrue(valid.copy(intensity = 1).isValid())
    assertTrue(valid.copy(intensity = 10).isValid())
    assertFalse(valid.copy(intensity = 0).isValid())
    assertFalse(valid.copy(intensity = 11).isValid())
    assertFalse(valid.copy(intensity = -1).isValid())
  }

  @Test
  fun eachStrength_belongsToASymptomOfTheEntry_andIsWithinBounds() {
    val two = valid.copy(types = listOf(SymptomType.FATIGUE, SymptomType.NAUSEA))
    listOf(
            emptyMap<SymptomType, Int>() to true,
            mapOf(SymptomType.FATIGUE to 5) to true,
            mapOf(SymptomType.FATIGUE to 1, SymptomType.NAUSEA to 10) to true,
            // Only a symptom of the entry has a strength
            mapOf(SymptomType.PAIN to 5) to false,
            mapOf(SymptomType.FATIGUE to 0) to false,
            mapOf(SymptomType.NAUSEA to 11) to false,
        )
        .forEach { (strengths, isValid) ->
          assertEquals(
              "$strengths",
              isValid,
              two.copy(strengths = strengths).isValid(valid.occurredAt),
          )
        }
  }

  @Test
  fun notesAreOptional_withoutOther() {
    assertTrue(valid.copy(notes = null).isValid())
    assertTrue(valid.copy(notes = "").isValid())
    assertTrue(valid.copy(notes = "   ").isValid())
  }

  @Test
  fun otherNeedsItsName_upTo40Characters_notesAloneDontSayIt() {
    assertTrue(other.isValid())
    assertTrue(other.copy(otherName = "h".repeat(40)).isValid())
    listOf(null, "", "   ", "h".repeat(41)).forEach {
      assertFalse("name $it", other.copy(otherName = it, notes = "Hiccups").isValid())
    }
  }

  @Test
  fun otherAmongSeveralSymptomsStillNeedsItsName() {
    val fatigueAndOther = valid.copy(types = listOf(SymptomType.FATIGUE, SymptomType.OTHER))
    assertFalse(fatigueAndOther.isValid())
    assertTrue(fatigueAndOther.copy(otherName = "Hiccups").isValid())
  }

  @Test
  fun occurredAt_canBeInThePast_orUpTo5MinutesAhead() {
    val now = valid.occurredAt

    assertTrue(valid.isValid(now))
    assertTrue(valid.isValid(now.plus(Duration.ofDays(400))))
    assertTrue(valid.isValid(now.minus(Duration.ofMinutes(5))))
    assertFalse(valid.isValid(now.minus(Duration.ofMinutes(5)).minusSeconds(1)))
    assertFalse(valid.isValid(now.minus(Duration.ofDays(1))))
  }

  @Test
  fun isValid_usesTheCurrentTimeByDefault() {
    assertTrue(valid.copy(occurredAt = Instant.now()).isValid())
    assertFalse(valid.copy(occurredAt = Instant.now().plus(Duration.ofHours(1))).isValid())
  }

  @Test
  fun notesLengthBoundary() {
    assertTrue(valid.copy(notes = "n".repeat(1000)).isValid())
    assertFalse(valid.copy(notes = "n".repeat(1001)).isValid())
    assertTrue(other.copy(notes = "n".repeat(1000)).isValid())
    assertFalse(other.copy(notes = "n".repeat(1001)).isValid())
  }

  /** A caregiver who added (US-22) or edited (US-46) an entry signs it with a name. */
  @Test
  fun caregiversEntry_needsTheirName_ofAtMostFiftyCharacters() {
    val now = Instant.parse("2026-10-05T09:00:00Z")
    val added = valid.copy(addedBy = "caregiver", addedByName = "Sam")
    val edited = valid.copy(editedBy = "caregiver", editedByName = "Sam")

    assertTrue(added.isValid(now))
    assertTrue(edited.isValid(now))
    for (name in listOf(null, "  ", "a".repeat(SymptomEntry.MAX_ADDED_BY_NAME_LENGTH + 1))) {
      assertFalse(added.copy(addedByName = name).isValid(now))
      assertFalse(edited.copy(editedByName = name).isValid(now))
    }
    assertNull(valid.addedBy)
    assertNull(valid.editedBy)
  }
}
