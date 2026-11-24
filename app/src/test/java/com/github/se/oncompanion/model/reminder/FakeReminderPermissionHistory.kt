// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.reminder

/** Shared request history for testing consumers without touching device storage. */
class FakeReminderPermissionHistory : ReminderPermissionHistory {
  override var requested = false
    private set

  override fun markRequested() {
    requested = true
  }
}
