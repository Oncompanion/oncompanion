// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.reminder

/** Shared scheduler for reminder tests, without Android services. */
class FakeReminderScheduler : ReminderScheduler {
  /** Every reminder asked to be scheduled, in order, accepted or not. */
  val scheduled = mutableListOf<Reminder>()
  /** Every reminder cancelled, in order. */
  val cancelled = mutableListOf<ReminderKey>()
  /** The reminders scheduled now, by key. */
  val reminders = mutableMapOf<ReminderKey, Reminder>()
  /** The reminders that already rang; scheduling or cancelling one forgets it. */
  val delivered = mutableSetOf<ReminderKey>()

  override fun wasDelivered(key: ReminderKey) = key in delivered

  /** What scheduling answers; only [ReminderScheduleResult.SCHEDULED] keeps the reminder. */
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
