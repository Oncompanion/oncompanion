// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.reminder

/** Shared scheduler for future appointment/domain tests without Android services. */
class FakeReminderScheduler : ReminderScheduler {
  val scheduled = mutableListOf<Reminder>()
  val cancelled = mutableListOf<ReminderKey>()
  val reminders = mutableMapOf<ReminderKey, Reminder>()
  val delivered = mutableSetOf<ReminderKey>()

  override fun wasDelivered(key: ReminderKey) = key in delivered

  var result = ReminderScheduleResult.SCHEDULED

  override fun schedule(reminder: Reminder): ReminderScheduleResult {
    delivered.remove(reminder.key)
    scheduled += reminder
    reminders.remove(reminder.key)
    if (result == ReminderScheduleResult.SCHEDULED) reminders[reminder.key] = reminder
    return result
  }

  override fun cancel(key: ReminderKey) {
    delivered.remove(key)
    cancelled += key
    reminders.remove(key)
  }
}
