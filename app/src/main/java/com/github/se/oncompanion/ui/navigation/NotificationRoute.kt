// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.navigation

import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.medication.PrescriptionConfirmation
import com.github.se.oncompanion.model.notification.CircleNotification
import com.github.se.oncompanion.model.notification.NotificationKind

/**
 * Where tapping this notification leads, given its item [prescription] as it is now, or `null` if
 * it no longer exists (US-50). It is decided at the tap, not when the notification was sent: the
 * notification stays in the "Your circle" sheet for days, and the patient may have reviewed the
 * prescription elsewhere since.
 *
 * A prescription still waiting opens its review form. Otherwise its page shows it as it is now, or
 * that it doesn't exist anymore when it was rejected or deleted: never a review that is over. The
 * tap only opens a screen; confirming or rejecting stays the patient's choice in the form.
 */
fun CircleNotification.route(prescription: Prescription?): String =
    when (kind) {
      NotificationKind.PRESCRIPTION_ADDED ->
          if (prescription?.confirmation == PrescriptionConfirmation.PENDING) {
            Screen.prescriptionEdit(itemId)
          } else Screen.prescriptionDetail(itemId)
    }
