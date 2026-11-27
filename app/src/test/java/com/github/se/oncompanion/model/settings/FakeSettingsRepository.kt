// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Shared controllable device preferences for screen, ViewModel and root-theme tests. */
class FakeSettingsRepository(initial: DeviceSettings = DeviceSettings()) : SettingsRepository {
  /** The saved preferences; setting it updates every observation. */
  val settings = MutableStateFlow(initial)
  /** Failure injected into an already-running observation. */
  val observationFailure = MutableStateFlow<Exception?>(null)
  /** Number of live collectors, used to detect duplicate observations after Retry. */
  var activeObservations = 0
    private set

  /** Lets a test keep a local write pending. */
  var writeGate: CompletableDeferred<Unit>? = null
  /** Suppresses the observation echo so tests exercise the ViewModel's successful local update. */
  var emitWrites = true
  /** Every date format saved, in order. */
  val dateWrites = mutableListOf<DateFormatPreference>()
  /** Every time format saved, in order. */
  val timeWrites = mutableListOf<TimeFormatPreference>()
  /** Every appearance saved, in order. */
  val writes = mutableListOf<AppearancePreference>()
  /** Suspends each new observation until a test releases startup. */
  var readGate: CompletableDeferred<Unit>? = null
  /** Thrown by each new observation, if set. */
  var readFailure: Exception? = null
  /** Thrown by each write, if set. */
  var writeFailure: Exception? = null

  override fun observeSettings(): Flow<DeviceSettings> = flow {
    activeObservations++
    try {
      readGate?.await()
      readFailure?.let { throw it }
      emitAll(
          settings.combine(observationFailure) { current, failure ->
            failure?.let { throw it }
            current
          }
      )
    } finally {
      activeObservations--
    }
  }

  override suspend fun setAppearance(appearance: AppearancePreference) {
    writes += appearance
    writeGate?.await()
    writeFailure?.let { throw it }
    if (emitWrites) settings.value = settings.value.copy(appearance = appearance)
  }

  override suspend fun setDateFormat(dateFormat: DateFormatPreference) {
    dateWrites += dateFormat
    writeGate?.await()
    writeFailure?.let { throw it }
    if (emitWrites) settings.value = settings.value.copy(dateFormat = dateFormat)
  }

  override suspend fun setTimeFormat(timeFormat: TimeFormatPreference) {
    timeWrites += timeFormat
    writeGate?.await()
    writeFailure?.let { throw it }
    if (emitWrites) settings.value = settings.value.copy(timeFormat = timeFormat)
  }
}
