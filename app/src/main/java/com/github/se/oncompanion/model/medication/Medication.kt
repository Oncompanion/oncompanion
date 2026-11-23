// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication

import java.time.LocalDate
import java.time.LocalTime

/**
 * One medication of a [Prescription], stored in the document of its prescription. Everything is
 * entered or confirmed by the user: the app only records it.
 *
 * @property id a unique ID, see [MedicationRepository.newId]
 * @property prescriptionId the [Prescription] this medication belongs to
 * @property name required, see [isValid]
 * @property dosage optional free text (e.g. "1 tablet")
 * @property frequency optional free text (e.g. "Twice a day")
 * @property startDate first day of intake; it can be later than [Prescription.prescribedOn]
 * @property durationDays number of days of intake counting [startDate]; `null` if there is no end
 * @property reminderTimes the times of day the patient chose to be reminded of it (US-32), in the
 *   phone's time zone; empty when they set no reminder. Only the patient sets them, and only on a
 *   prescription they confirmed, see [Prescription.isValid]
 */
data class Medication(
    val id: String,
    val prescriptionId: String,
    val name: String,
    val dosage: String? = null,
    val frequency: String? = null,
    val startDate: LocalDate,
    val durationDays: Int? = null,
    val reminderTimes: List<LocalTime> = emptyList(),
) {
  /** Whether the medication satisfies the same constraints as the Firestore security rules. */
  fun isValid(): Boolean =
      id.isNotBlank() &&
          prescriptionId.isNotBlank() &&
          name.isNotBlank() &&
          name.length <= MAX_TEXT_LENGTH &&
          (dosage == null || dosage.length <= MAX_TEXT_LENGTH) &&
          (frequency == null || frequency.length <= MAX_TEXT_LENGTH) &&
          isValidDuration(durationDays) &&
          isValidReminderTimes(reminderTimes)

  companion object {
    /** Keep in sync with `firestore.rules`. */
    const val MAX_TEXT_LENGTH = 100
    /** Keep in sync with `firestore.rules`. */
    const val MAX_DURATION_DAYS = 3650
    /** One reminder per dose, for up to three doses a day as the form offers. */
    const val MAX_REMINDER_TIMES = 3

    /** Whether a medication can last [days]: no end (`null`), or 1 to [MAX_DURATION_DAYS] days. */
    fun isValidDuration(days: Int?): Boolean = days == null || days in 1..MAX_DURATION_DAYS

    /**
     * Whether [times] can be a medication's reminders: at most [MAX_REMINDER_TIMES], each a whole
     * minute (they are stored as "HH:mm"), never the same time twice.
     */
    fun isValidReminderTimes(times: List<LocalTime>): Boolean =
        times.size <= MAX_REMINDER_TIMES &&
            times.all { it.second == 0 && it.nano == 0 } &&
            times.distinct().size == times.size
  }
}
