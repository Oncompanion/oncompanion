// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.reminder

import android.content.Intent

/** Accepts only the internal appointment URI shape; the ViewModel separately checks the account. */
internal fun Intent.appointmentReminder(): Pair<String, String>? {
  val uri = data ?: return null
  if (
      action != Intent.ACTION_VIEW ||
          uri.scheme != "oncompanion-reminder" ||
          uri.authority != "local"
  )
      return null
  val parts = uri.pathSegments
  if (parts.size != 3 || parts[1] != "appointment" || parts.any { it.isBlank() }) return null
  return parts[0] to parts[2]
}
