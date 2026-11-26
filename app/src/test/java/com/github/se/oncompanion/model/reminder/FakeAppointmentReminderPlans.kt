// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.reminder

/**
 * Shared account-scoped receipts; preserves accepted plans across coordinator instances in tests.
 */
class FakeAppointmentReminderPlans : AppointmentReminderPlans {
  private val owners = mutableMapOf<String, Map<String, AppointmentReminderPlan>>()

  override fun read(ownerUid: String) = owners[ownerUid].orEmpty()

  override fun write(ownerUid: String, plans: Map<String, AppointmentReminderPlan>) {
    owners[ownerUid] = plans.toMap()
  }
}
