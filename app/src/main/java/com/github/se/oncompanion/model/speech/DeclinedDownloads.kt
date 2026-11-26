// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.speech

import java.util.concurrent.ConcurrentHashMap

/**
 * The languages whose offline download the patient declined with Not now, so a tired patient isn't
 * asked again at every dictation. Kept in memory only: the offer comes back after the app restarts,
 * in case they changed their mind.
 */
class DeclinedDownloads {
  private val languages = ConcurrentHashMap.newKeySet<SpeechLanguage>()

  /** The patient said Not now to downloading [language]. */
  fun decline(language: SpeechLanguage) {
    languages += language
  }

  /** Whether the patient said Not now to downloading [language] since the app started. */
  operator fun contains(language: SpeechLanguage): Boolean = language in languages
}

/** Holds the [DeclinedDownloads] shared by every symptom form; tests pass their own instead. */
object DeclinedDownloadsProvider {
  /** The instance every symptom form uses. */
  @Volatile var declined: DeclinedDownloads = DeclinedDownloads()
}
