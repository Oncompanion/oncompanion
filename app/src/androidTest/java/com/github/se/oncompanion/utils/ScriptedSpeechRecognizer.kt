// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.utils

import com.github.se.oncompanion.model.speech.OfflineSpeech
import com.github.se.oncompanion.model.speech.SpeechError
import com.github.se.oncompanion.model.speech.SpeechEvent
import com.github.se.oncompanion.model.speech.SpeechLanguage
import com.github.se.oncompanion.model.speech.SpeechRecognizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A [SpeechRecognizer] for end-to-end tests, which can't speak into the emulator's microphone: each
 * time the app listens, the patient "says" the next of [phrases], then stops, as the phone's
 * recognizer reports it. The unit tests' fake lives in `app/src/test`, out of reach of the
 * connected tests.
 */
internal class ScriptedSpeechRecognizer(vararg phrases: String) : SpeechRecognizer {

  private val remaining = ArrayDeque(phrases.toList())

  override fun isAvailable(): Boolean = true

  override fun listen(language: SpeechLanguage, onDevice: Boolean): Flow<SpeechEvent> = flow {
    emit(SpeechEvent.Listening)
    // Listening more often than planned fails this attempt on screen, rather than throwing in the
    // app's coroutine, which would crash the process and every test after it in the shard
    val phrase = remaining.removeFirstOrNull()
    if (phrase == null) {
      emit(SpeechEvent.Failed(SpeechError.FAILED))
      return@flow
    }
    emit(SpeechEvent.PartialText(phrase))
    emit(SpeechEvent.FinalText(phrase))
  }

  override fun stop() = Unit

  // Unknown, as a phone that doesn't say: the app listens without offering a download
  override suspend fun offlineSupport(language: SpeechLanguage): OfflineSpeech =
      OfflineSpeech.UNKNOWN

  override fun downloadForOffline(language: SpeechLanguage) = Unit
}
