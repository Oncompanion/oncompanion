// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as AndroidRecognizer
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Listens with the phone's own speech recognizer (usually Google's), so the app needs no speech
 * model or server of its own. Recognition on the phone is preferred, so it works offline when the
 * language is installed on the phone; otherwise the phone's recognizer may go online. On Android
 * 13+, the app can also ask which languages work offline and have one downloaded (US-45), then
 * listen with that same on-device recognizer.
 *
 * Nothing is recorded by the app: Android only hands back the text.
 *
 * @param context any context of the app; only its application context is kept, so a screen given
 *   here is never kept alive after it closes
 * @param mainDispatcher where Android's recognizer is created and driven; it only works on the main
 *   thread
 */
class AndroidSpeechRecognizer(
    context: Context,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : SpeechRecognizer {

  private val appContext: Context = context.applicationContext

  /** The recognizer currently listening, so [stop] can reach it; `null` when not listening. */
  @Volatile private var listening: AndroidRecognizer? = null

  /** Whether the user asked the current session to [stop]. */
  @Volatile private var stopRequested = false

  override fun isAvailable(): Boolean = AndroidRecognizer.isRecognitionAvailable(appContext)

  override fun listen(language: SpeechLanguage, onDevice: Boolean): Flow<SpeechEvent> = flow {
    // Checked when listening starts, not when the flow is built: the user may have
    // granted the microphone in between
    when {
      !hasMicrophone() -> emit(SpeechEvent.Failed(SpeechError.NO_PERMISSION))
      !isAvailable() -> emit(SpeechEvent.Failed(SpeechError.UNAVAILABLE))
      else -> emitAll(recognition(language, onDevice))
    }
  }
      .flowOn(mainDispatcher)

  override fun stop() {
    val recognizer = listening ?: return
    stopRequested = true
    recognizer.stopListening()
  }

  // Android 13+ only: older phones can't say which languages they understand offline
  override suspend fun offlineSupport(language: SpeechLanguage): OfflineSpeech =
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) OfflineSpeech.UNKNOWN
      else withContext(mainDispatcher) { onDeviceSupport(language).also(::afterDownloadAnswer) }

  override fun downloadForOffline(language: SpeechLanguage) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (!AndroidRecognizer.isOnDeviceRecognitionAvailable(appContext)) return
    val recognizer = AndroidRecognizer.createOnDeviceSpeechRecognizer(appContext)
    recognizer.triggerModelDownload(recognizerIntent(language, appContext.packageName))
    // Kept until the phone has the request (see afterDownloadAnswer): destroying it now could
    // drop the request before it reaches the phone's speech service
    releaseDownloader()
    downloader = recognizer
    answersSinceDownload = 0
  }

  /** The recognizer that asked for the last download; see [downloadForOffline]. */
  private var downloader: AndroidRecognizer? = null

  /** How many times the phone answered [offlineSupport] since the last download was asked for. */
  private var answersSinceDownload = 0

  /**
   * Releases the recognizer that asked for the last download once the phone surely has the request.
   * The first answer may come before the phone took it (when the phone shows no dialog, the app
   * asks again as soon as it stops waiting for one), so still downloadable then doesn't mean the
   * request was lost. The next answer comes at the next dictation: by then, a language still
   * downloadable means the patient cancelled the phone's dialog, and the recognizer goes too.
   */
  private fun afterDownloadAnswer(support: OfflineSpeech) {
    answersSinceDownload++
    val phoneHasIt = support == OfflineSpeech.DOWNLOADING || support == OfflineSpeech.READY
    if (phoneHasIt || answersSinceDownload > 1) releaseDownloader()
  }

  private fun releaseDownloader() {
    downloader?.destroy()
    downloader = null
  }

  /**
   * The recognizer asked by the last [offlineSupport], answered or not, kept until the next session
   * is ready to listen, or until the next [offlineSupport] if the patient didn't listen. Destroying
   * it as soon as it answered, or when the app stopped waiting for a slow phone, shut the phone's
   * speech service down under the session starting right after, which then failed, or found the
   * service busy with what was left of it (#675).
   */
  private var checker: AndroidRecognizer? = null

  private fun releaseChecker() {
    checker?.destroy()
    checker = null
  }

  /** Asks the phone's on-device recognizer about [language], then keeps it in [checker]. */
  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private suspend fun onDeviceSupport(language: SpeechLanguage): OfflineSpeech {
    if (!AndroidRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
      return OfflineSpeech.ONLINE_ONLY
    }
    releaseChecker()
    val recognizer = AndroidRecognizer.createOnDeviceSpeechRecognizer(appContext)
    // Kept even if the app stops waiting for the answer: it listens right after
    checker = recognizer
    return suspendCancellableCoroutine { answer ->
      fun reply(support: OfflineSpeech) {
        if (answer.isActive) answer.resume(support)
      }
      recognizer.checkRecognitionSupport(
          recognizerIntent(language, appContext.packageName),
          Runnable::run,
          object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) =
                reply(
                    offlineSpeechOf(
                        language,
                        installed = support.installedOnDeviceLanguages,
                        pending = support.pendingOnDeviceLanguages,
                        downloadable = support.supportedOnDeviceLanguages,
                    )
                )

            // The phone couldn't answer: listening still works as before
            override fun onError(error: Int) = reply(OfflineSpeech.UNKNOWN)
          },
      )
    }
  }

  /** One listening session with Android's recognizer, destroyed when the flow ends. */
  private fun recognition(language: SpeechLanguage, onDevice: Boolean) =
      callbackFlow {
            stopRequested = false
            val session = Session(language, onDevice && canListenOnDevice(), this)
            session.start()
            awaitClose {
              releaseChecker()
              session.release()
            }
          }
          // A session is short: keep every event, so a slow screen never loses the final one
          .buffer(Channel.UNLIMITED)

  /**
   * What one listening session heard so far, and how it answers Android's recognizer.
   *
   * A recognizer that fails before the patient starts speaking gets a second start when it may then
   * work. The on-device one, after an error from the phone's speech service, which can drop a
   * session while it restarts though the language is on the phone (#675, see [checker]). The
   * phone's default one, asked to prefer offline, fails for a language it hasn't downloaded even
   * when the phone is online: it starts again without that preference. Both can say they are ready
   * before failing, so only speech counts, not being ready.
   *
   * @param useOnDevice whether it listens with the on-device recognizer
   * @param events where the session sends what it hears, closed when it ends
   */
  private inner class Session(
      private val language: SpeechLanguage,
      private val useOnDevice: Boolean,
      private val events: ProducerScope<SpeechEvent>,
  ) : RecognitionListener {
    // What was heard in the segments already finished, when the recognizer cuts at each pause
    private val heard = mutableListOf<String>()
    // Whether the patient started speaking, and whether the recognizer was started a second time
    private var spoke = false
    private var restarted = false
    // Said once, even if the recognizer was started a second time
    private var saidListening = false
    // Offline if the phone can, until the default recognizer starts again online
    private var preferOffline = true
    private lateinit var recognizer: AndroidRecognizer

    /** Starts a new recognizer, which [stop] can reach. */
    fun start() {
      recognizer = newRecognizer(useOnDevice)
      listening = recognizer
      recognizer.setRecognitionListener(this)
      recognizer.startListening(recognizerIntent(language, appContext.packageName, preferOffline))
    }

    /** Releases the recognizer; also cancels listening if the user left before the end. */
    fun release() {
      if (listening === recognizer) listening = null
      recognizer.destroy()
    }

    private fun soFar(current: String? = null) = (heard + listOfNotNull(current)).joinToString(" ")

    private fun finish(text: String) {
      events.trySend(
          if (text.isBlank()) SpeechEvent.Failed(SpeechError.NO_SPEECH)
          else SpeechEvent.FinalText(text)
      )
      events.close()
    }

    // A failure before listening that a second start can get past: a service error on the
    // on-device recognizer, or the default one missing the language offline (it may have it online)
    private fun canRestart(error: Int): Boolean {
      if (spoke || restarted || stopRequested) return false
      return if (useOnDevice) error in SERVICE_ERRORS else error in LANGUAGE_ERRORS
    }

    override fun onReadyForSpeech(params: Bundle?) {
      // The session holds the phone's speech service now: the check's recognizer can go
      releaseChecker()
      if (!saidListening) events.trySend(SpeechEvent.Listening)
      saidListening = true
    }

    override fun onBeginningOfSpeech() {
      spoke = true
    }

    override fun onPartialResults(partialResults: Bundle?) {
      bestText(partialResults)?.let {
        spoke = true
        events.trySend(SpeechEvent.PartialText(soFar(it)))
      }
    }

    // Android 13+: one sentence is over, the session goes on after the pause
    override fun onSegmentResults(segmentResults: Bundle) {
      bestText(segmentResults)?.let {
        heard += it
        events.trySend(SpeechEvent.PartialText(soFar()))
      }
    }

    override fun onEndOfSegmentedSession() = finish(soFar())

    override fun onResults(results: Bundle?) = finish(soFar(bestText(results)))

    override fun onError(error: Int) {
      // Only Android's code, never anything the patient said: tells a phone's failures apart
      Log.w(TAG, "Listening failed with error $error (on device: $useOnDevice)")
      // The patient hasn't spoken yet: start again, online for the default recognizer
      if (canRestart(error)) {
        restarted = true
        preferOffline = useOnDevice
        recognizer.destroy()
        start()
        return
      }
      // Some phones answer a stop before any speech with a client error: the user stopped
      // without saying anything, which is not a failure
      val reason =
          if (error == AndroidRecognizer.ERROR_CLIENT && stopRequested) SpeechError.NO_SPEECH
          else speechErrorOf(error, useOnDevice)
      // Silence after some sentences only ends the session: keep what was heard
      if (reason == SpeechError.NO_SPEECH && heard.isNotEmpty()) finish(soFar())
      else {
        events.trySend(SpeechEvent.Failed(reason))
        events.close()
      }
    }

    // Only how loud it is, to show the app hears the user; never the sound itself
    override fun onRmsChanged(rmsdB: Float) {
      events.trySend(SpeechEvent.Level(levelOf(rmsdB)))
    }

    // The raw audio isn't used: the app keeps nothing of the recording
    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = Unit

    override fun onEvent(eventType: Int, params: Bundle?) = Unit
  }

  /** Whether the phone has an on-device recognizer the app can listen with (Android 13+). */
  @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
  private fun canListenOnDevice(): Boolean =
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
          AndroidRecognizer.isOnDeviceRecognitionAvailable(appContext)

  /** The on-device recognizer if [onDevice] and the phone has it, else the phone's default one. */
  private fun newRecognizer(onDevice: Boolean): AndroidRecognizer =
      if (onDevice && canListenOnDevice()) {
        AndroidRecognizer.createOnDeviceSpeechRecognizer(appContext)
      } else {
        AndroidRecognizer.createSpeechRecognizer(appContext)
      }

  private fun hasMicrophone(): Boolean =
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED
}

/**
 * What Android's recognizer is asked: free speech in [language], with text as it comes, without a
 * connection if it can when [preferOffline].
 */
internal fun recognizerIntent(
    language: SpeechLanguage,
    packageName: String,
    preferOffline: Boolean = true,
): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
      putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
      // Android 13+: let the patient pause to think, and end only after a few seconds of
      // silence; each sentence comes as its own segment. Older phones end at the first pause.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        putExtra(
            RecognizerIntent.EXTRA_SEGMENTED_SESSION,
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
        )
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
            PAUSE_MILLIS,
        )
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, PAUSE_MILLIS)
      }
    }

/**
 * What the phone's lists of on-device languages say about [language]: [installed] ones work
 * offline, [pending] ones are downloading, [downloadable] ones can be downloaded.
 */
internal fun offlineSpeechOf(
    language: SpeechLanguage,
    installed: List<String>,
    pending: List<String>,
    downloadable: List<String>,
): OfflineSpeech {
  fun List<String>.has() = any { it.equals(language.tag, ignoreCase = true) }
  return when {
    installed.has() -> OfflineSpeech.READY
    pending.has() -> OfflineSpeech.DOWNLOADING
    downloadable.has() -> OfflineSpeech.DOWNLOADABLE
    else -> OfflineSpeech.ONLINE_ONLY
  }
}

/**
 * How loud [rmsdB] is, from 0 to 1. Android's recognizers report roughly -2 dB (silence) to 10 dB
 * (speaking up close); anything outside is clamped.
 */
internal fun levelOf(rmsdB: Float): Float =
    ((rmsdB - QUIET_DB) / (LOUD_DB - QUIET_DB)).coerceIn(0f, 1f)

private const val QUIET_DB = -2f
private const val LOUD_DB = 10f

/**
 * How long the patient can stay silent before listening ends. An `Int`, although Android documents
 * a `Long`: Google's recognizer reads an `Int` and ignores a `Long`.
 */
private const val PAUSE_MILLIS = 5_000

/** The most likely text in Android's [results], without surrounding spaces; `null` if blank. */
internal fun bestText(results: Bundle?): String? =
    results
        ?.getStringArrayList(AndroidRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * The [SpeechError] for one of Android's `ERROR_*` codes, from the [onDevice] recognizer or not.
 * Codes added after Android 9 are plain numbers at compile time, so older phones just never send
 * them.
 */
@SuppressLint("InlinedApi")
internal fun speechErrorOf(code: Int, onDevice: Boolean = false): SpeechError =
    when (code) {
      AndroidRecognizer.ERROR_NO_MATCH,
      AndroidRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.NO_SPEECH
      AndroidRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.NO_PERMISSION
      AndroidRecognizer.ERROR_RECOGNIZER_BUSY,
      AndroidRecognizer.ERROR_TOO_MANY_REQUESTS -> SpeechError.BUSY
      AndroidRecognizer.ERROR_NETWORK,
      AndroidRecognizer.ERROR_NETWORK_TIMEOUT,
      AndroidRecognizer.ERROR_SERVER,
      AndroidRecognizer.ERROR_SERVER_DISCONNECTED ->
          // The on-device recognizer needs no connection: its service failed (#675)
          if (onDevice) SpeechError.FAILED else SpeechError.NEEDS_CONNECTION
      AndroidRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
      AndroidRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SpeechError.LANGUAGE_UNAVAILABLE
      else -> SpeechError.FAILED
    }

private const val TAG = "AndroidSpeechRecognizer"

/**
 * Android's errors that come from the phone's speech service rather than from the patient, the
 * microphone or the language: the on-device recognizer is started again once after one (#675).
 */
@SuppressLint("InlinedApi")
private val SERVICE_ERRORS =
    setOf(
        AndroidRecognizer.ERROR_NETWORK,
        AndroidRecognizer.ERROR_NETWORK_TIMEOUT,
        AndroidRecognizer.ERROR_SERVER,
        AndroidRecognizer.ERROR_SERVER_DISCONNECTED,
        AndroidRecognizer.ERROR_CLIENT,
    )

/**
 * Android's errors for a language the recognizer can't use: the phone's default recognizer, asked
 * to prefer offline, sends them for a language it hasn't downloaded, though it has it online.
 */
@SuppressLint("InlinedApi")
private val LANGUAGE_ERRORS =
    setOf(
        AndroidRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        AndroidRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
    )
