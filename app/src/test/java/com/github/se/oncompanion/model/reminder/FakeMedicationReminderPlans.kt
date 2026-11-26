// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.reminder

/** In-memory [MedicationReminderPlans], shared by the use cases' tests. */
class FakeMedicationReminderPlans : MedicationReminderPlans {
  private val owners = mutableMapOf<String, Map<String, MedicationReminderPlan>>()

  override fun read(ownerUid: String) = owners[ownerUid].orEmpty()

  override fun write(ownerUid: String, plans: Map<String, MedicationReminderPlan>) {
    if (plans.isEmpty()) owners.remove(ownerUid) else owners[ownerUid] = plans.toMap()
  }
}
