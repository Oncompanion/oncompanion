// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication

import android.util.Log
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepositoryProvider
import com.github.se.oncompanion.model.medication.PrescriptionConfirmation
import com.github.se.oncompanion.model.notification.CircleNotification
import com.github.se.oncompanion.model.notification.NotificationKind
import com.github.se.oncompanion.model.notification.NotificationRepository
import com.github.se.oncompanion.model.notification.NotificationRepositoryProvider
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Saves a prescription the user has confirmed, with its medications, and changes or deletes a saved
 * one. Every medication write goes through this use case, which also tells the patient when a
 * caregiver adds a prescription for them. It doesn't schedule reminders:
 * [com.github.se.oncompanion.domain.reminder.ReconcileMedicationReminders] does that from the saved
 * medications.
 *
 * Unlike the other use cases, it has no single `invoke`: [add], [update] and [delete] are named
 * operations of one class rather than use cases of their own, so that the three writes can't be
 * synced differently.
 */
class ManageMedicationSchedule(
    private val medicationRepository: MedicationRepository =
        MedicationRepositoryProvider.repository,
    private val notificationRepository: NotificationRepository =
        NotificationRepositoryProvider.repository,
) {

  /**
   * Saves [draft] as a new prescription of [uid]. Returns once it is saved on the device, also
   * offline.
   *
   * A caregiver adding it for the patient signs it with [addedBy] and [addedByName]: it is then
   * [PrescriptionConfirmation.PENDING] until the patient checks it, and none of its medications are
   * reminded before. The patient also gets a notification asking them to review it, once: a
   * correction or the review itself sends none.
   *
   * @throws IllegalArgumentException if the draft is not [PrescriptionDraft.isValid], if one of its
   *   medications already has an ID (it comes from a saved prescription, which must be changed, not
   *   added a second time), if only one of [addedBy] and [addedByName] is given, or if a caregiver
   *   gives reminder times: only the patient chooses them, once they confirmed it
   */
  suspend fun add(
      uid: String,
      draft: PrescriptionDraft,
      addedBy: String? = null,
      addedByName: String? = null,
  ) {
    val signature = addedBy?.let {
      Signature(it, requireNotNull(addedByName) { "A caregiver signs with both" })
    }
    require(signature != null || addedByName == null) { "A caregiver signs with both" }
    require(signature == null || draft.medications.all { it.reminderTimes.isEmpty() }) {
      "Only the patient chooses reminders"
    }
    // Adding a draft made from a saved prescription would leave two prescriptions sharing the
    // same medication IDs, the old one next to the new one
    require(draft.medications.all { it.id == null }) {
      "A new prescription can't contain medications that are already saved"
    }
    require(draft.isValid()) { "Invalid prescription draft" }
    // The repository creates the IDs on the device, so this also works offline
    val prescription =
        draft.toPrescription(medicationRepository.newId(), medicationRepository::newId).let {
          if (signature == null) it
          else
              it.copy(
                  addedBy = signature.uid,
                  addedByName = signature.firstName,
                  confirmation = PrescriptionConfirmation.PENDING,
              )
        }
    medicationRepository.addPrescription(uid, prescription)
    if (signature != null) notifyThePatient(uid, prescription.id, signature)
  }

  /**
   * Tells patient [uid] that the caregiver who signed [signature] added prescription
   * [prescriptionId] for them to review. Sent after the prescription: the server applies the two
   * writes in that order, and its rules only accept a notification about a prescription it already
   * has.
   *
   * The prescription is saved whatever happens here: a notification that can't be written is only
   * logged, as the repository does when the server refuses one.
   */
  private suspend fun notifyThePatient(
      uid: String,
      prescriptionId: String,
      signature: Signature,
  ) {
    try {
      notificationRepository.notify(
          uid,
          CircleNotification(
              kind = NotificationKind.PRESCRIPTION_ADDED,
              itemId = prescriptionId,
              actorUid = signature.uid,
              actorFirstName = signature.firstName,
              // Ignored: the server sets it
              createdAt = Instant.now(),
          ),
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Not the message: it can hold the document path
      Log.w(
          TAG,
          "Couldn't notify the patient of prescription $prescriptionId: ${e.javaClass.simpleName}",
      )
    }
  }

  /**
   * Replaces the content of the saved prescription [prescriptionId] of [uid] with [draft], so a
   * wrong prescription can be corrected. A medication of the draft that has an ID stays the same
   * saved medication, one without an ID is added, and a saved one that the draft leaves out is
   * removed. Returns once it is saved on the device, also offline. Who added it stays the same, and
   * a patient saving a prescription a caregiver added confirms it. A caregiver editing with
   * [editedBy] and [editedByName] keeps the saved confirmation state, signing the last edit. The
   * patient's edit clears an earlier caregiver editor.
   *
   * The prescription is read again first, because it can have been deleted since the form was
   * opened (e.g. from another device) and the repository would then silently save nothing. The read
   * answers from this device's cache, so it only catches a deletion this device already knows
   * about, online or offline. A deletion it doesn't know about yet is refused by the server later,
   * and the update is undone.
   *
   * @return `true` if the prescription was replaced; `false` if it no longer exists, in which case
   *   nothing is written and the caller should tell the user
   * @throws IllegalArgumentException if the draft or the editor signature is invalid
   */
  suspend fun update(
      uid: String,
      prescriptionId: String,
      draft: PrescriptionDraft,
      editedBy: String? = null,
      editedByName: String? = null,
  ): Boolean {
    require(draft.isValid()) { "Invalid prescription draft" }
    // Without this read, the caller couldn't tell a saved correction from one that was dropped
    val saved =
        medicationRepository.observePrescription(uid, prescriptionId).first() ?: return false
    // Only the medications added in the form need an ID, the saved ones keep theirs. The form
    // doesn't hold who added it or whether it is confirmed: they come from the saved one
    val prescription =
        draft
            .toPrescription(prescriptionId, medicationRepository::newId)
            .copy(
                editedBy = editedBy,
                editedByName = editedByName,
                addedBy = saved.addedBy,
                addedByName = saved.addedByName,
                confirmation =
                    if (editedBy == null && saved.confirmation == PrescriptionConfirmation.PENDING)
                        PrescriptionConfirmation.CONFIRMED
                    else saved.confirmation,
            )
    require(prescription.isValid()) { "Invalid prescription" }
    medicationRepository.updatePrescription(uid, prescription)
    return true
  }

  /**
   * Rejects the prescription [prescriptionId] a caregiver added to [uid]'s: it leaves their
   * prescriptions and is kept apart as [PrescriptionConfirmation.REJECTED], where only the
   * caregiver who added it sees it was rejected. None of its medications are ever reminded. Returns
   * once it is saved on the device, also offline. The caller asks the patient to confirm first.
   *
   * @return `false` if there is no such prescription waiting for the patient (e.g. it was deleted,
   *   or decided on another device): nothing is written
   */
  suspend fun reject(uid: String, prescriptionId: String): Boolean {
    val saved = medicationRepository.observePrescription(uid, prescriptionId).first()
    if (saved?.confirmation != PrescriptionConfirmation.PENDING) return false
    medicationRepository.rejectPrescription(uid, saved)
    return true
  }

  /**
   * Deletes the rejected prescription [prescriptionId] of [patientUid], once the caregiver who
   * added it saw it was rejected. Returns once it is deleted on the device, also offline.
   */
  suspend fun dismissRejected(patientUid: String, prescriptionId: String) {
    medicationRepository.deleteRejectedPrescription(patientUid, prescriptionId)
  }

  /**
   * Deletes the prescription [prescriptionId] of [uid] with its medications. Returns once it is
   * deleted on the device, also offline. Does nothing if the prescription doesn't exist. The caller
   * asks the user to confirm first: a deletion can't be undone.
   */
  suspend fun delete(uid: String, prescriptionId: String) {
    medicationRepository.deletePrescription(uid, prescriptionId)
  }

  private companion object {
    const val TAG = "ManageMedicationSchedule"
  }
}

/** Who signs a caregiver's prescription: both, or neither for the patient's own. */
private data class Signature(val uid: String, val firstName: String)
