// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.navigation

import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.medication.PrescriptionConfirmation
import com.github.se.oncompanion.model.notification.CircleNotification
import com.github.se.oncompanion.model.notification.NotificationKind
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests of where tapping a notification leads: the review of a prescription while it waits, its
 * page otherwise.
 */
class NotificationRouteTest {

  private val added =
      CircleNotification(
          kind = NotificationKind.PRESCRIPTION_ADDED,
          itemId = "presc-1",
          actorUid = "sam",
          actorFirstName = "Sam",
          createdAt = Instant.EPOCH,
      )

  private fun prescription(confirmation: PrescriptionConfirmation) =
      Prescription(
          id = "presc-1",
          prescribedOn = LocalDate.of(2026, 11, 23),
          medications = emptyList(),
          addedBy = "sam",
          addedByName = "Sam",
          confirmation = confirmation,
      )

  @Test
  fun anAddedPrescription_opensTheReviewOnlyWhileItWaits_elseItsPage() {
    // The prescription as it is when the patient taps, and where the tap leads
    val cases =
        listOf(
            prescription(PrescriptionConfirmation.PENDING) to Screen.prescriptionEdit("presc-1"),
            // Reviewed meanwhile, e.g. on another phone: its page shows it as it is now
            prescription(PrescriptionConfirmation.CONFIRMED) to
                Screen.prescriptionDetail("presc-1"),
            // Rejected or deleted: its page says it doesn't exist anymore
            prescription(PrescriptionConfirmation.REJECTED) to Screen.prescriptionDetail("presc-1"),
            null to Screen.prescriptionDetail("presc-1"),
        )
    for ((current, expected) in cases) {
      assertEquals("${current?.confirmation}", expected, added.route(current))
    }
  }
}
