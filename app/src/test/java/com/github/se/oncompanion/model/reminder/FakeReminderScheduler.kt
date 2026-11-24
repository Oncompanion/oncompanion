// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.reminder

/** Shared scheduler for future appointment/domain tests without Android services. */
class FakeReminderScheduler : ReminderScheduler {
  val scheduled = mutableListOf<Reminder>()
  val cancelled = mutableListOf<ReminderKey>()
  val reminders = mutableMapOf<ReminderKey, Reminder>()
  var result = ReminderScheduleResult.SCHEDULED

  override fun schedule(reminder: Reminder): ReminderScheduleResult {
    scheduled += reminder
    reminders.remove(reminder.key)
    if (result == ReminderScheduleResult.SCHEDULED) reminders[reminder.key] = reminder
    return result
  }

  override fun cancel(key: ReminderKey) {
    cancelled += key
    reminders.remove(key)
  }
}
