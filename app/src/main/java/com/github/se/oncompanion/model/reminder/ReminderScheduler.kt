// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.reminder

import java.time.Instant

/** Android channel shared by alarm delivery and its permission/settings UI. */
const val REMINDER_CHANNEL_ID = "care_reminders"

/** Account and feature-scoped identity; the same key replaces an existing reminder. */
data class ReminderKey(val ownerUid: String, val feature: String, val itemId: String) {
  init {
    require(ownerUid.isNotBlank() && feature.isNotBlank() && itemId.isNotBlank())
  }
}

/** Display-ready content; callers format dates and times in the phone's time zone. */
data class Reminder(val key: ReminderKey, val at: Instant, val title: String, val text: String)

/** Why a reminder was accepted or cannot currently be scheduled. */
enum class ReminderScheduleResult {
  SCHEDULED,
  NOTIFICATIONS_BLOCKED,
  EXACT_ALARMS_BLOCKED,
}

/** Device service for one-shot local reminders; no permission prompt or network access. */
interface ReminderScheduler {
  /** Replaces the alarm for this key, or cancels its old alarm when access is blocked. */
  fun schedule(reminder: Reminder): ReminderScheduleResult

  /** Cancels the pending alarm and any displayed notification for this key; safe to repeat. */
  fun cancel(key: ReminderKey)
}
