// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.symptom

import java.time.Duration
import java.time.Instant

/**
 * The symptoms the user can pick from (Figma: "Log symptoms"). With [OTHER], the user names the
 * symptom ([SymptomEntry.otherName]). Keep in sync with the two lists of symptoms in
 * `firestore.rules` (`isValidSymptom`): one missing from the strengths' list would refuse every
 * edit.
 */
enum class SymptomType {
  FATIGUE,
  NAUSEA,
  PAIN,
  FEVER,
  HEADACHE,
  APPETITE_LOSS,
  SLEEP_PROBLEMS,
  BREATHLESSNESS,
  OTHER,
}

/**
 * What the user logged at one moment, stored at `/users/{uid}/symptoms/{id}`: one or more symptoms
 * felt together, how strong each one was and notes. The app only records what the user entered: it
 * never interprets a strength, and stores nothing combined from them (a screen may show the
 * strongest symptom of a day, as the week chart does).
 *
 * @property id the document ID; empty for an entry that isn't saved yet
 * @property types the symptoms felt, at least one and each at most once, in the order the user
 *   picked them
 * @property strengths how strong each symptom was, from [MIN_INTENSITY] to [MAX_INTENSITY], for
 *   those the user gave a strength (Overview UX Refactor, #543); a symptom without one is absent
 * @property intensity how bad the user felt overall, from [MIN_INTENSITY] to [MAX_INTENSITY], or
 *   `null`. Only for entries saved before [strengths] existed and those older app versions write:
 *   once the form asks a strength per symptom (#543) it stops writing it, and an edit that gives
 *   strengths clears it
 * @property occurredAt when the user felt it (defaults to now when logging, can be earlier but not
 *   later, see [isValid])
 * @property otherName which symptom [SymptomType.OTHER] is ("Hiccups"), at most
 *   [MAX_OTHER_NAME_LENGTH] characters; required with it. Entries saved before it existed have none
 *   and show "Other"; it is ignored without [SymptomType.OTHER]
 * @property notes free text
 * @property createdAt set by the server when the entry is saved; until the server has it, the time
 *   the device saved it. `null` only for an entry not read from the repository
 * @property addedBy the uid of the caregiver who logged it for the patient (US-22), or `null` when
 *   the patient did
 * @property addedByName that caregiver's first name, shown as "Added by Sam"
 * @property editedBy the uid of the caregiver who last edited it for the patient (US-46), or `null`
 *   when nobody did or the patient edited it last
 * @property editedByName that caregiver's first name, shown as "Edited by Sam"
 */
data class SymptomEntry(
    val id: String = "",
    val types: List<SymptomType>,
    val occurredAt: Instant,
    val intensity: Int? = null,
    val strengths: Map<SymptomType, Int> = emptyMap(),
    val notes: String? = null,
    val otherName: String? = null,
    val createdAt: Instant? = null,
    val addedBy: String? = null,
    val addedByName: String? = null,
    val editedBy: String? = null,
    val editedByName: String? = null,
) {
  /**
   * Whether the entry satisfies the same constraints as the Firestore security rules at [now]. A
   * symptom can't be in the future (it would stay at the top of the journal), give or take
   * [MAX_CLOCK_DRIFT].
   */
  fun isValid(now: Instant = Instant.now()): Boolean =
      types.isNotEmpty() &&
          types.distinct().size == types.size &&
          (intensity == null || intensity in MIN_INTENSITY..MAX_INTENSITY) &&
          // A strength belongs to a symptom of the entry
          types.containsAll(strengths.keys) &&
          strengths.values.all { it in MIN_INTENSITY..MAX_INTENSITY } &&
          !occurredAt.isAfter(now.plus(MAX_CLOCK_DRIFT)) &&
          (notes == null || notes.length <= MAX_NOTES_LENGTH) &&
          // "Other" only says something was felt: its name says what
          (SymptomType.OTHER !in types || !otherName.isNullOrBlank()) &&
          (otherName == null || otherName.length <= MAX_OTHER_NAME_LENGTH) &&
          // A caregiver's entry says who added it
          (addedBy == null ||
              (!addedByName.isNullOrBlank() && addedByName.length <= MAX_ADDED_BY_NAME_LENGTH)) &&
          // So does one a caregiver edited
          (editedBy == null ||
              (!editedByName.isNullOrBlank() && editedByName.length <= MAX_ADDED_BY_NAME_LENGTH))

  /**
   * The symptoms given a strength, each with it, in the order of [types]. Empty for an entry saved
   * before [strengths] existed: it has at most the [overallIntensity].
   */
  fun strengthsInOrder(): List<Pair<SymptomType, Int>> = types.mapNotNull { type ->
    strengths[type]?.let { type to it }
  }

  /**
   * The single [intensity] of an entry saved before [strengths] existed, or `null`. Once a symptom
   * has its own strength, the strengths say how strong it was and an old intensity isn't shown.
   */
  fun overallIntensity(): Int? = intensity.takeIf { strengths.isEmpty() }

  /**
   * How strong the entry felt at most: its strongest symptom, else its [overallIntensity]; `null`
   * when no strength was given.
   */
  fun strongest(): Int? = strengths.values.maxOrNull() ?: overallIntensity()

  companion object {
    /** Keep in sync with `firestore.rules`. */
    const val MIN_INTENSITY = 1
    /** Keep in sync with `firestore.rules`. */
    const val MAX_INTENSITY = 10
    /** Keep in sync with `firestore.rules`. */
    const val MAX_NOTES_LENGTH = 1000
    /** Keep in sync with `firestore.rules`. */
    const val MAX_OTHER_NAME_LENGTH = 40
    /** Keep in sync with `firestore.rules`. */
    const val MAX_ADDED_BY_NAME_LENGTH = 50
    /**
     * How far [occurredAt] may be after the current time, for a phone clock slightly ahead of the
     * server's. Keep in sync with `firestore.rules`.
     */
    val MAX_CLOCK_DRIFT: Duration = Duration.ofMinutes(5)
  }
}
