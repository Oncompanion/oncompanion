// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.reminder

import java.time.Instant

/** Scheduling receipt; hashes identify timing/content without retaining appointment text. */
data class AppointmentReminderPlan(val due: Instant, val timing: String, val content: String)

/** Account-scoped accepted plans, retained across coordinator/process recreation. */
interface AppointmentReminderPlans {
  /**
   * Reads this account's receipts; malformed receipts are cleared so current appointments can
   * rebuild them. A failed reset or closed session still fails instead of scheduling from untrusted
   * data.
   */
  fun read(ownerUid: String): Map<String, AppointmentReminderPlan>

  /** Atomically replaces this account's receipts, removing the key when empty. */
  fun write(ownerUid: String, plans: Map<String, AppointmentReminderPlan>)
}
