// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.reminder

import java.time.Instant

/** Scheduling receipt; hashes identify timing/content without retaining appointment text. */
data class AppointmentReminderPlan(val due: Instant, val timing: String, val content: String)

/** Account-scoped accepted plans, retained across coordinator/process recreation. */
interface AppointmentReminderPlans {
  /** Reads only this account's receipts; corrupt storage must fail rather than silently replay. */
  fun read(ownerUid: String): Map<String, AppointmentReminderPlan>

  /** Atomically replaces this account's receipts, removing the key when empty. */
  fun write(ownerUid: String, plans: Map<String, AppointmentReminderPlan>)
}
