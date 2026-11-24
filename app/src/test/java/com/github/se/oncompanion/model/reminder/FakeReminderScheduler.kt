// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.reminder

/** Shared scheduler for future appointment/domain tests without Android services. */
class FakeReminderScheduler : ReminderScheduler {
  val reminders = mutableMapOf<ReminderKey, Reminder>()
  var result = ReminderScheduleResult.SCHEDULED

  override fun schedule(reminder: Reminder): ReminderScheduleResult {
    reminders.remove(reminder.key)
    if (result == ReminderScheduleResult.SCHEDULED) reminders[reminder.key] = reminder
    return result
  }

  override fun cancel(key: ReminderKey) {
    reminders.remove(key)
  }
}
