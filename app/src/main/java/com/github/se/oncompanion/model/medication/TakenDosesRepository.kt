// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * The doses of their medications a patient ticked each day on the Overview: for each day, how many
 * doses of each medication were taken. The patient records them; a caregiver who sees the patient's
 * prescriptions only reads them. The app records what the patient ticked, nothing more: how many
 * doses a day a medication has comes from its frequency (`DoseCount`).
 */
interface TakenDosesRepository {

  /**
   * How many doses of each medication, by [Medication.id], [uid] ticked on [date], the patient's
   * local day; a medication without a tick is absent, so a new day starts empty. It emits again
   * when a tick changes, from the device's cache when offline, and fails if the user may not read
   * them (e.g. a caregiver who no longer sees the patient's prescriptions).
   */
  fun observeDay(uid: String, date: LocalDate): Flow<Map<String, Int>>

  /**
   * As [observeDay], for someone who only reads the day (a caregiver): `null` while it is unknown,
   * i.e. this phone has no copy of the day and the server hasn't answered yet, as offline. A
   * missing copy on the patient's own phone means nothing was ticked, since they write it; on a
   * caregiver's it only means it was never downloaded, so it mustn't show as "0 of 3".
   */
  fun observeDayIfKnown(uid: String, date: LocalDate): Flow<Map<String, Int>?>

  /**
   * Records that [count] doses of [medicationId] were taken on [date] (0 unticks them all), without
   * changing the other medications of that day. It returns once saved on the device, also offline.
   *
   * @throws IllegalArgumentException if [medicationId] is blank or [count] isn't from 0 to
   *   [MAX_TAKEN_PER_DAY]
   */
  suspend fun setTaken(uid: String, date: LocalDate, medicationId: String, count: Int)

  companion object {
    /**
     * At most one dose an hour is recorded, and
     * [com.github.se.oncompanion.domain.medication.DoseCount] counts no more. Keep in sync with
     * `firestore.rules`.
     */
    const val MAX_TAKEN_PER_DAY = 24
  }
}
