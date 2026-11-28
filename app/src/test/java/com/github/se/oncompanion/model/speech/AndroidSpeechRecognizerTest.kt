// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.speech

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as AndroidRecognizer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.util.ReflectionHelpers

/**
 * Listening with the phone's recognizer, simulated by Robolectric: what the user hears back in each
 * case, and that Android's recognizer is always released at the end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidSpeechRecognizerTest {
  private val context: Application = ApplicationProvider.getApplicationContext()

  @Before
  fun setUp() {
    ShadowSpeechRecognizer.reset()
    shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
    installRecognitionService()
  }

  @Test
  fun howLoudTheUserIs_comesBackAsALevelFromZeroToOne() = runTest {
    val session = startListening(SpeechLanguage.ENGLISH)

    // Android's dB, from below silence to above speaking up close
    val cases = listOf(-5f to 0f, -2f to 0f, 4f to 0.5f, 10f to 1f, 14f to 1f)
    for ((rmsdB, _) in cases) android.triggerOnRmsChanged(rmsdB)
    runCurrent()

    assertEquals(cases.map { SpeechEvent.Level(it.second) }, session.events)
    session.job.cancel()
  }

  @Test
  fun whatTheUserSaysComesBackAsTheySpeakThenInFull() = runTest {
    val session = startListening(SpeechLanguage.FRENCH)

    android.triggerOnReadyForSpeech(Bundle())
    android.triggerOnPartialResults(results("J'ai"))
    android.triggerOnPartialResults(results("J'ai mal à la tête"))
    android.triggerOnResults(results(" J'ai mal à la tête depuis ce matin "))
    runCurrent()

    assertEquals(
        listOf(
            SpeechEvent.Listening,
            SpeechEvent.PartialText("J'ai"),
            SpeechEvent.PartialText("J'ai mal à la tête"),
            SpeechEvent.FinalText("J'ai mal à la tête depuis ce matin"),
        ),
        session.events,
    )
    assertTrue(session.job.isCompleted)
    assertTrue(android.isDestroyed)
  }

  @Test
  fun theRecognizerIsAskedForFreeSpeechInTheLanguageOnThePhoneIfPossible() = runTest {
    startListening(SpeechLanguage.FRENCH)

    val intent = android.lastRecognizerIntent
    assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
    assertEquals("fr-FR", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
    assertEquals(
        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL),
    )
    assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
    assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
    assertEquals(1, intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0))
    assertEquals(context.packageName, intent.getStringExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE))
    // Segments, so the patient can pause; the silence must be an Int for Google's recognizer
    assertEquals(
        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
        intent.getStringExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION),
    )
    for (silence in
        listOf(
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
        )) {
      assertEquals(silence, 5_000, intent.getIntExtra(silence, 0))
    }
  }

  @Test
  fun phonesBeforeAndroid13AreNotAskedForSegments() {
    val sdk = Build.VERSION.SDK_INT
    ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", Build.VERSION_CODES.S_V2)
    try {
      val intent = recognizerIntent(SpeechLanguage.ENGLISH, context.packageName)

      assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION))
      assertFalse(
          intent.hasExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
      )
    } finally {
      ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
    }
  }

  @Test
  fun sentencesSaidBetweenPausesAreJoinedInOrder() = runTest {
    val session = startListening()

    listener.onPartialResults(results("I'm tired"))
    listener.onSegmentResults(results("I'm tired"))
    listener.onPartialResults(results("and nauseous"))
    listener.onSegmentResults(results("and nauseous"))
    listener.onEndOfSegmentedSession()
    runCurrent()

    assertEquals(
        listOf(
            SpeechEvent.PartialText("I'm tired"),
            SpeechEvent.PartialText("I'm tired"),
            SpeechEvent.PartialText("I'm tired and nauseous"),
            SpeechEvent.PartialText("I'm tired and nauseous"),
            SpeechEvent.FinalText("I'm tired and nauseous"),
        ),
        session.events,
    )
    assertTrue(session.job.isCompleted)
    assertTrue(android.isDestroyed)
  }

  @Test
  fun silenceAfterASentenceKeepsWhatWasHeard() = runTest {
    // Ended by silence after a sentence: what was heard is kept
    val afterASentence = startListening()
    listener.onSegmentResults(results("I'm tired"))
    android.triggerOnError(AndroidRecognizer.ERROR_SPEECH_TIMEOUT)
    runCurrent()
    assertEquals(SpeechEvent.FinalText("I'm tired"), afterASentence.events.last())

    // A session that heard nothing still ends with nothing heard
    val silent = startListening()
    listener.onSegmentResults(results("  "))
    listener.onEndOfSegmentedSession()
    runCurrent()
    assertEquals(listOf(SpeechEvent.Failed(SpeechError.NO_SPEECH)), silent.events)

    // A real failure after a sentence is still a failure
    val failed = startListening()
    listener.onSegmentResults(results("I'm tired"))
    android.triggerOnError(AndroidRecognizer.ERROR_NETWORK)
    runCurrent()
    assertEquals(SpeechEvent.Failed(SpeechError.NEEDS_CONNECTION), failed.events.last())
  }

  @Test
  fun blankTextIsNeverSentAndSilenceEndsWithNoSpeech() = runTest {
    val session = startListening()

    android.triggerOnPartialResults(results("   "))
    android.triggerOnPartialResults(Bundle())
    android.triggerOnResults(results(""))
    runCurrent()

    assertEquals(listOf(SpeechEvent.Failed(SpeechError.NO_SPEECH)), session.events)
    assertTrue(android.isDestroyed)
  }

  @Test
  fun anErrorEndsListeningAndReleasesTheRecognizer() = runTest {
    val session = startListening()

    android.triggerOnReadyForSpeech(Bundle())
    android.triggerOnError(AndroidRecognizer.ERROR_NETWORK)
    runCurrent()

    assertEquals(
        listOf(SpeechEvent.Listening, SpeechEvent.Failed(SpeechError.NEEDS_CONNECTION)),
        session.events,
    )
    assertTrue(session.job.isCompleted)
    assertTrue(android.isDestroyed)
  }

  @Test
  fun whatAndroidSendsAfterStopStillEndsTheSession() = runTest {
    // Robolectric doesn't record stopListening(), so this can't check that stop() reaches
    // Android: only that the text Android then sends still comes back and ends the session
    val recognizer = AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler))
    val session = startListening(recognizer = recognizer)
    android.triggerOnPartialResults(results("tired"))

    recognizer.stop()
    android.triggerOnResults(results("tired since this morning"))
    runCurrent()

    assertEquals(
        listOf(
            SpeechEvent.PartialText("tired"),
            SpeechEvent.FinalText("tired since this morning"),
        ),
        session.events,
    )
    assertTrue(session.job.isCompleted)
  }

  @Test
  fun doneDuringPauses_returnsEverySentenceOnce_andReleasesTheRecognizer() = runTest {
    val recognizer = AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler))
    val session = startListening(recognizer = recognizer)
    listener.onSegmentResults(results("I'm tired"))
    listener.onPartialResults(results("and nauseous"))

    // Done: Android finishes the sentence being said, then ends the session
    recognizer.stop()
    listener.onSegmentResults(results("and nauseous"))
    listener.onEndOfSegmentedSession()
    // A late answer after the end adds nothing
    android.triggerOnResults(results("and nauseous"))
    runCurrent()

    assertEquals(
        listOf(
            SpeechEvent.PartialText("I'm tired"),
            SpeechEvent.PartialText("I'm tired and nauseous"),
            SpeechEvent.PartialText("I'm tired and nauseous"),
            SpeechEvent.FinalText("I'm tired and nauseous"),
        ),
        session.events,
    )
    assertTrue(session.job.isCompleted)
    assertTrue(android.isDestroyed)
  }

  @Test
  fun stoppingBeforeSpeakingIsNothingHeardNotAFailure() = runTest {
    // Some phones end a stop before any speech with ERROR_CLIENT: only after a stop is it silence
    val cases = listOf(true to SpeechError.NO_SPEECH, false to SpeechError.FAILED)
    for ((stopped, expected) in cases) {
      val recognizer = AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler))
      val session = startListening(recognizer = recognizer)
      if (stopped) recognizer.stop()

      android.triggerOnError(AndroidRecognizer.ERROR_CLIENT)
      runCurrent()

      assertEquals("stopped: $stopped", listOf(SpeechEvent.Failed(expected)), session.events)
    }
  }

  @Test
  fun aStopIsForgottenWhenTheNextSessionStarts() = runTest {
    val recognizer = AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler))
    startListening(recognizer = recognizer)
    recognizer.stop()
    android.triggerOnResults(results("tired"))
    runCurrent()

    val next = startListening(recognizer = recognizer)
    android.triggerOnError(AndroidRecognizer.ERROR_CLIENT)
    runCurrent()

    assertEquals(listOf(SpeechEvent.Failed(SpeechError.FAILED)), next.events)
  }

  @Test
  fun stoppingWhenNotListeningDoesNotCrash() = runTest {
    // The same recognizer, as the app has one: e.g. the user taps Stop twice, or after leaving.
    // Only checks that nothing crashes: Robolectric doesn't record stopListening()
    val recognizer = AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler))
    recognizer.stop()

    // After a session that ended
    val ended = startListening(recognizer = recognizer)
    android.triggerOnResults(results("tired"))
    runCurrent()
    recognizer.stop()

    // After a session that was cancelled
    val cancelled = startListening(recognizer = recognizer)
    cancelled.job.cancel()
    runCurrent()
    recognizer.stop()

    assertEquals(listOf(SpeechEvent.FinalText("tired")), ended.events)
    assertTrue(cancelled.events.isEmpty())
  }

  @Test
  fun cancellingStopsListeningAndDropsWhatWasSaid() = runTest {
    val session = startListening()
    android.triggerOnPartialResults(results("nausea"))

    session.job.cancel()
    runCurrent()
    android.triggerOnResults(results("nausea all day"))
    runCurrent()

    assertEquals(listOf(SpeechEvent.PartialText("nausea")), session.events)
    assertTrue(android.isDestroyed)
  }

  @Test
  fun withoutTheMicrophoneNothingIsStarted() = runTest {
    shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)

    val session = startListening()

    assertEquals(listOf(SpeechEvent.Failed(SpeechError.NO_PERMISSION)), session.events)
    assertTrue(session.job.isCompleted)
    assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
  }

  @Test
  fun onAPhoneWithoutARecognizerNothingIsStarted() = runTest {
    shadowOf(context.packageManager).removeService(RECOGNITION_SERVICE)
    val recognizer = AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler))

    assertFalse(recognizer.isAvailable())
    val session = startListening(recognizer = recognizer)
    assertEquals(listOf(SpeechEvent.Failed(SpeechError.UNAVAILABLE)), session.events)
    assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
  }

  @Test
  fun aPhoneWithARecognizerCanListen() {
    assertTrue(AndroidSpeechRecognizer(context).isAvailable())
  }

  @Test
  fun onlyTheAppsContextIsKeptNeverTheScreens() = runTest {
    // A screen's context that fails the test if anything but its app's context is used: keeping
    // the screen would leak it when the phone is rotated while listening
    val screen =
        object : ContextWrapper(context) {
          override fun getApplicationContext(): Context = context

          override fun getPackageManager(): PackageManager = screenUsed()

          override fun getPackageName(): String = screenUsed()

          override fun checkPermission(permission: String, pid: Int, uid: Int): Int = screenUsed()

          override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean =
              screenUsed()

          override fun getMainLooper(): Looper = screenUsed()
        }

    val recognizer = AndroidSpeechRecognizer(screen, UnconfinedTestDispatcher(testScheduler))
    assertTrue(recognizer.isAvailable())
    val session = startListening(recognizer = recognizer)
    android.triggerOnResults(results("tired"))
    runCurrent()

    assertEquals(listOf(SpeechEvent.FinalText("tired")), session.events)
  }

  @Test
  fun whetherEnglishWorksOfflineComesFromThePhonesOnDeviceLanguages() = runTest {
    ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
    // What the phone's on-device recognizer lists, and what it means for English (en-US)
    val cases =
        listOf(
            support(installed = listOf("fr-FR", "en-US")) to OfflineSpeech.READY,
            support(installed = listOf("EN-us")) to OfflineSpeech.READY,
            support(pending = listOf("en-US"), downloadable = listOf("en-US")) to
                OfflineSpeech.DOWNLOADING,
            support(downloadable = listOf("fr-FR", "en-US")) to OfflineSpeech.DOWNLOADABLE,
            support(installed = listOf("en-GB"), downloadable = listOf("fr-FR")) to
                OfflineSpeech.ONLINE_ONLY,
        )
    for ((support, expected) in cases) {
      val answer = askOffline()
      android.triggerSupportResult(support)
      runCurrent()

      assertEquals("$support", expected, answer.await())
    }
  }

  @Test
  fun whenThePhoneCantAnswer_itIsUnknown() = runTest {
    ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
    val answer = askOffline()

    android.triggerSupportError(AndroidRecognizer.ERROR_SERVER)
    runCurrent()

    assertEquals(OfflineSpeech.UNKNOWN, answer.await())
  }

  @Test
  fun theRecognizerThatAnswered_isKeptUntilTheNextSessionIsReady() = runTest {
    // Destroying it at once shut the phone's speech service down under the next session
    ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
    val recognizer = AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler))
    val installed: ShadowSpeechRecognizer.() -> Unit = {
      triggerSupportResult(support(installed = listOf("en-US")))
    }
    // How the next session goes → whether the check's recognizer is released by then
    val sessions: List<Triple<String, ShadowSpeechRecognizer.() -> Unit, Boolean>> =
        listOf(
            Triple("not ready yet", {}, false),
            Triple("ready", { triggerOnReadyForSpeech(Bundle()) }, true),
            Triple("failed", { triggerOnError(AndroidRecognizer.ERROR_AUDIO) }, true),
            // As on the phone: dropped before the patient spoke, so started again, not ready yet
            Triple(
                "started again",
                { triggerOnError(AndroidRecognizer.ERROR_SERVER_DISCONNECTED) },
                false,
            ),
        )
    for ((name, session, released) in sessions) {
      answer(recognizer, installed)
      val checker = android
      assertFalse("$name: kept after answering", checker.isDestroyed)

      val listening = startListening(recognizer = recognizer, onDevice = true)
      android.session()
      shadowOf(Looper.getMainLooper()).idle()
      runCurrent()

      assertEquals("$name: released", released, checker.isDestroyed)
      // Still listening: whichever recognizer listens now releases it once ready
      if (!released) {
        android.triggerOnReadyForSpeech(Bundle())
        assertTrue("$name: released once ready", checker.isDestroyed)
      }
      listening.job.cancel()
      runCurrent()
    }

    // Not listened with, even when the phone couldn't answer: released by the next check, which
    // keeps its own
    answer(recognizer) { triggerSupportError(AndroidRecognizer.ERROR_SERVER) }
    val unused = android
    assertFalse(unused.isDestroyed)
    answer(recognizer, installed)
    assertTrue(unused.isDestroyed)
    assertFalse(android.isDestroyed)
  }

  @Test
  fun aSecondAnswerFromThePhone_isIgnored() = runTest {
    ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
    val answer = askOffline()

    android.triggerSupportResult(support(installed = listOf("en-US")))
    android.triggerSupportError(AndroidRecognizer.ERROR_SERVER)
    runCurrent()

    assertEquals(OfflineSpeech.READY, answer.await())
  }

  @Test
  fun leavingBeforeThePhoneAnswers_theRecognizerIsReleasedByTheNextCheck() = runTest {
    // Like an answer the patient doesn't listen after
    ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
    val recognizer = AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler))
    val answer = askOffline(recognizer = recognizer)
    val unanswered = android

    answer.cancel()
    runCurrent()
    assertFalse("kept after leaving", unanswered.isDestroyed)
    askOffline(recognizer = recognizer)

    assertTrue("released by the next check", unanswered.isDestroyed)
    assertFalse("the next check keeps its own", android.isDestroyed)
  }

  @Test
  fun aCheckTheAppStoppedWaitingFor_isKeptUntilTheSessionAfterItIsReady() = runTest {
    // The form stops waiting after 2 s and listens at once: a busy speech service, on a slow phone
    ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
    val recognizer = AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler))
    val answer = askOffline(recognizer = recognizer)
    val checker = android

    answer.cancel()
    runCurrent()
    val listening = startListening(recognizer = recognizer)

    assertFalse("kept while the session starts", checker.isDestroyed)
    android.triggerOnReadyForSpeech(Bundle())
    assertTrue("released once ready", checker.isDestroyed)
    listening.job.cancel()
  }

  @Test
  fun onDevice_listensWithTheRecognizerThatHasTheDownload_whenThePhoneHasOne() = runTest {
    // Whether the phone has an on-device recognizer, and whether the app asks for it → whether it
    // listens with it
    val cases =
        listOf(Triple(true, true, true), Triple(true, false, false), Triple(false, true, false))
    for ((available, asked, expected) in cases) {
      ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(available)
      val session = startListening(onDevice = asked)

      assertEquals("available $available, asked $asked", expected, latestIsOnDevice())
      session.job.cancel()
    }
  }

  @Test
  fun theDownloadRecognizer_isReleasedOnceThePhoneSurelyHasTheRequest() = runTest {
    ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
    val recognizer = AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler))
    val stillDownloadable: ShadowSpeechRecognizer.() -> Unit = {
      triggerSupportResult(support(downloadable = listOf("en-US")))
    }
    // The phone's first answer after a download → whether it shows the phone took the request.
    // Still downloadable may come before the phone took it (no dialog yet), or after the patient
    // cancelled the phone's dialog: the recognizer then waits for the next answer.
    val answers: List<Triple<String, ShadowSpeechRecognizer.() -> Unit, Boolean>> =
        listOf(
            Triple(
                "downloading",
                { triggerSupportResult(support(pending = listOf("en-US"))) },
                true,
            ),
            Triple("ready", { triggerSupportResult(support(installed = listOf("en-US"))) }, true),
            Triple("still downloadable", stillDownloadable, false),
            Triple(
                "online only",
                { triggerSupportResult(support(installed = listOf("en-GB"))) },
                false,
            ),
            Triple("error", { triggerSupportError(AndroidRecognizer.ERROR_SERVER) }, false),
        )
    for ((name, first, released) in answers) {
      recognizer.downloadForOffline(SpeechLanguage.ENGLISH)
      val downloader = android
      answer(recognizer, first)
      assertEquals("$name: released after the first answer", released, downloader.isDestroyed)

      // The next dictation: the phone had all the time it needed
      answer(recognizer, stillDownloadable)
      assertTrue("$name: still bound to the phone's speech service", downloader.isDestroyed)
    }
  }

  /** Asks [recognizer] whether English works offline, and the phone gives [answer]. */
  private suspend fun TestScope.answer(
      recognizer: AndroidSpeechRecognizer,
      answer: ShadowSpeechRecognizer.() -> Unit,
  ) {
    val support = askOffline(recognizer = recognizer)
    android.answer()
    runCurrent()
    support.await()
  }

  @Test
  fun aPhoneWithoutOnDeviceRecognition_onlyListensOnline_andDownloadsNothing() = runTest {
    ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
    val recognizer = AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler))

    assertEquals(OfflineSpeech.ONLINE_ONLY, recognizer.offlineSupport(SpeechLanguage.ENGLISH))
    recognizer.downloadForOffline(SpeechLanguage.ENGLISH)

    assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
  }

  @Test
  fun phonesBeforeAndroid13CantTell_andDownloadNothing() = runTest {
    ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
    val recognizer = AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler))
    val sdk = Build.VERSION.SDK_INT
    ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", Build.VERSION_CODES.S_V2)
    try {
      assertEquals(OfflineSpeech.UNKNOWN, recognizer.offlineSupport(SpeechLanguage.ENGLISH))
      recognizer.downloadForOffline(SpeechLanguage.ENGLISH)

      assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
      // Asked to listen on the phone anyway, it listens with the default recognizer
      startListening(recognizer = recognizer, onDevice = true).job.cancel()
      assertFalse(latestIsOnDevice())
    } finally {
      ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
    }
  }

  @Test
  fun aDownloadAsksThePhoneForTheLanguage_andReleasesTheOneBefore() {
    ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
    val recognizer = AndroidSpeechRecognizer(context)

    recognizer.downloadForOffline(SpeechLanguage.FRENCH)
    shadowOf(Looper.getMainLooper()).idle()
    val first = android
    recognizer.downloadForOffline(SpeechLanguage.ENGLISH)
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(
        "fr-FR",
        first.latestModelDownloadIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE),
    )
    assertEquals(
        "en-US",
        android.latestModelDownloadIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE),
    )
    assertTrue(first.isDestroyed)
    assertFalse(android.isDestroyed)
  }

  @Test
  fun onDevice_aServiceErrorBeforeThePatientSpeaks_startsItOnceMore() = runTest {
    // The phone's speech service drops the on-device session before the patient spoke
    ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
    val serviceErrors =
        listOf(
            AndroidRecognizer.ERROR_NETWORK,
            AndroidRecognizer.ERROR_NETWORK_TIMEOUT,
            AndroidRecognizer.ERROR_SERVER,
            AndroidRecognizer.ERROR_SERVER_DISCONNECTED,
            AndroidRecognizer.ERROR_CLIENT,
        )
    for (error in serviceErrors) {
      val recognizer = AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler))
      val retried = startListening(recognizer = recognizer, onDevice = true)
      val first = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
      // Ready, then failing before the patient spoke
      android.triggerOnReadyForSpeech(Bundle())
      android.triggerOnError(error)
      shadowOf(Looper.getMainLooper()).idle()

      // A new on-device recognizer listens, and the patient sees neither the failure nor a second
      // start
      val second = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
      assertNotSame("error $error", first, second)
      assertTrue("error $error", shadowOf(first).isDestroyed)
      assertTrue("error $error", latestIsOnDevice())
      // Still offline: the on-device recognizer needs no connection
      assertTrue(
          "error $error",
          android.lastRecognizerIntent.getBooleanExtra(
              RecognizerIntent.EXTRA_PREFER_OFFLINE,
              false,
          ),
      )
      // Done reaches the recognizer listening now, not the one destroyed (Robolectric doesn't
      // record
      // stopListening(), so this checks which one stop() would reach)
      assertSame("error $error", second, ReflectionHelpers.getField(recognizer, "listening"))
      android.triggerOnReadyForSpeech(Bundle())
      android.triggerOnResults(results("tired"))
      runCurrent()
      assertEquals(
          "error $error",
          listOf(SpeechEvent.Listening, SpeechEvent.FinalText("tired")),
          retried.events,
      )
      assertTrue("error $error", android.isDestroyed)
    }

    // Started only once more: a second failure is reported, as a failure, not as a missing
    // connection, which the on-device recognizer doesn't need
    val failing = startListening(onDevice = true)
    android.triggerOnError(AndroidRecognizer.ERROR_SERVER)
    shadowOf(Looper.getMainLooper()).idle()
    val second = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
    android.triggerOnError(AndroidRecognizer.ERROR_SERVER)
    shadowOf(Looper.getMainLooper()).idle()
    runCurrent()
    assertSame(second, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    assertEquals(listOf(SpeechEvent.Failed(SpeechError.FAILED)), failing.events)
    assertTrue(android.isDestroyed)
  }

  @Test
  fun aLanguageNotDownloaded_isListenedToOnline() = runTest {
    // Google's recognizer, asked to prefer offline, says it is ready, then fails for a language it
    // hasn't downloaded even online: it is started again without that preference
    val online = startListening(language = SpeechLanguage.FRENCH)
    val first = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
    android.triggerOnReadyForSpeech(Bundle())
    // Blank words aren't the patient speaking
    android.triggerOnPartialResults(results("  "))
    android.triggerOnError(AndroidRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
    shadowOf(Looper.getMainLooper()).idle()

    assertNotSame(first, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    assertTrue(shadowOf(first).isDestroyed)
    val intent = android.lastRecognizerIntent
    assertFalse(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
    assertEquals("fr-FR", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
    android.triggerOnReadyForSpeech(Bundle())
    android.triggerOnResults(results("fatigue"))
    runCurrent()
    assertEquals(listOf(SpeechEvent.Listening, SpeechEvent.FinalText("fatigue")), online.events)

    // Started only once more: what the second one says is what the patient is told
    val cases =
        listOf(
            AndroidRecognizer.ERROR_NETWORK to SpeechError.NEEDS_CONNECTION,
            AndroidRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED to SpeechError.LANGUAGE_UNAVAILABLE,
        )
    for ((error, expected) in cases) {
      val session = startListening()
      android.triggerOnError(AndroidRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
      shadowOf(Looper.getMainLooper()).idle()
      val second = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
      android.triggerOnError(error)
      shadowOf(Looper.getMainLooper()).idle()
      runCurrent()

      assertSame("error $error", second, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
      assertEquals("error $error", listOf(SpeechEvent.Failed(expected)), session.events)
    }
  }

  @Test
  fun otherFailures_endListeningWithoutStartingAgain() = runTest {
    ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
    // What happened before the error, the error → what the patient is told
    data class Case(
        val name: String,
        val onDevice: Boolean,
        val error: Int,
        val expected: SpeechError,
        val spoke: Boolean = false,
        val said: String? = null,
        val stopped: Boolean = false,
    )
    // Errors that don't come from the phone's speech service: starting again wouldn't help
    val notTheService =
        listOf(
            AndroidRecognizer.ERROR_NO_MATCH,
            AndroidRecognizer.ERROR_SPEECH_TIMEOUT,
            AndroidRecognizer.ERROR_RECOGNIZER_BUSY,
            AndroidRecognizer.ERROR_AUDIO,
            AndroidRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            AndroidRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            AndroidRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        )
    val cases =
        notTheService.map {
          Case(
              name = "on device, error $it",
              onDevice = true,
              error = it,
              expected = speechErrorOf(it, onDevice = true),
          )
        } +
            listOf(
                Case(
                    name = "online",
                    onDevice = false,
                    error = AndroidRecognizer.ERROR_SERVER_DISCONNECTED,
                    expected = SpeechError.NEEDS_CONNECTION,
                ),
                Case(
                    name = "on device, once speaking",
                    onDevice = true,
                    error = AndroidRecognizer.ERROR_SERVER_DISCONNECTED,
                    expected = SpeechError.FAILED,
                    spoke = true,
                ),
                Case(
                    name = "on device, once words came",
                    onDevice = true,
                    error = AndroidRecognizer.ERROR_SERVER_DISCONNECTED,
                    expected = SpeechError.FAILED,
                    said = "tired",
                ),
                Case(
                    name = "on device, stopped",
                    onDevice = true,
                    error = AndroidRecognizer.ERROR_CLIENT,
                    expected = SpeechError.NO_SPEECH,
                    stopped = true,
                ),
                Case(
                    name = "online, once speaking",
                    onDevice = false,
                    error = AndroidRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                    expected = SpeechError.LANGUAGE_UNAVAILABLE,
                    spoke = true,
                ),
                Case(
                    name = "online, once words came",
                    onDevice = false,
                    error = AndroidRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                    expected = SpeechError.LANGUAGE_UNAVAILABLE,
                    said = "tired",
                ),
                Case(
                    name = "online, stopped",
                    onDevice = false,
                    error = AndroidRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                    expected = SpeechError.LANGUAGE_UNAVAILABLE,
                    stopped = true,
                ),
            )
    for (case in cases) {
      val recognizer = AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler))
      val session = startListening(recognizer = recognizer, onDevice = case.onDevice)
      val created = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
      android.triggerOnReadyForSpeech(Bundle())
      if (case.spoke) listener.onBeginningOfSpeech()
      case.said?.let { android.triggerOnPartialResults(results(it)) }
      if (case.stopped) recognizer.stop()

      android.triggerOnError(case.error)
      shadowOf(Looper.getMainLooper()).idle()
      runCurrent()

      assertSame(case.name, created, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
      assertEquals(
          case.name,
          listOfNotNull(
              SpeechEvent.Listening,
              case.said?.let { SpeechEvent.PartialText(it) },
              SpeechEvent.Failed(case.expected),
          ),
          session.events,
      )
      assertTrue(case.name, android.isDestroyed)
    }
  }

  @Test
  fun androidErrorsAreGroupedByWhatTheUserCanDo() {
    val cases =
        mapOf(
            AndroidRecognizer.ERROR_NO_MATCH to SpeechError.NO_SPEECH,
            AndroidRecognizer.ERROR_SPEECH_TIMEOUT to SpeechError.NO_SPEECH,
            AndroidRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to SpeechError.NO_PERMISSION,
            AndroidRecognizer.ERROR_RECOGNIZER_BUSY to SpeechError.BUSY,
            AndroidRecognizer.ERROR_TOO_MANY_REQUESTS to SpeechError.BUSY,
            AndroidRecognizer.ERROR_NETWORK to SpeechError.NEEDS_CONNECTION,
            AndroidRecognizer.ERROR_NETWORK_TIMEOUT to SpeechError.NEEDS_CONNECTION,
            AndroidRecognizer.ERROR_SERVER to SpeechError.NEEDS_CONNECTION,
            AndroidRecognizer.ERROR_SERVER_DISCONNECTED to SpeechError.NEEDS_CONNECTION,
            AndroidRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED to SpeechError.LANGUAGE_UNAVAILABLE,
            AndroidRecognizer.ERROR_LANGUAGE_UNAVAILABLE to SpeechError.LANGUAGE_UNAVAILABLE,
            AndroidRecognizer.ERROR_AUDIO to SpeechError.FAILED,
            AndroidRecognizer.ERROR_CLIENT to SpeechError.FAILED,
            999 to SpeechError.FAILED,
        )
    for ((code, expected) in cases) {
      assertEquals("error $code", expected, speechErrorOf(code))
      // The on-device recognizer needs no connection: the same errors are its service failing
      val onDevice = if (expected == SpeechError.NEEDS_CONNECTION) SpeechError.FAILED else expected
      assertEquals("on device, error $code", onDevice, speechErrorOf(code, onDevice = true))
    }
  }

  /** What one listening session sent so far, and the coroutine collecting it. */
  private class Session(val events: List<SpeechEvent>, val job: Job)

  /** Whether Android's latest recognizer is the on-device one, which Robolectric doesn't say. */
  private fun latestIsOnDevice(): Boolean =
      ReflectionHelpers.getField(ShadowSpeechRecognizer.getLatestSpeechRecognizer(), "mOnDevice")

  /** Android's recognizer created by the last [startListening]. */
  private val android
    get() = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())

  /**
   * The listener given to Android's recognizer, for the Android 13 calls Robolectric can't trigger
   * (`onSegmentResults`, `onEndOfSegmentedSession`).
   */
  private val listener: RecognitionListener
    get() {
      val state =
          ShadowSpeechRecognizer::class
              .java
              .getDeclaredMethod("getState")
              .apply { isAccessible = true }
              .invoke(android)
      return state.javaClass
          .getDeclaredField("recognitionListener")
          .apply { isAccessible = true }
          .get(state) as RecognitionListener
    }

  /** Starts listening, and lets Android's recognizer receive its listener and intent. */
  private fun TestScope.startListening(
      language: SpeechLanguage = SpeechLanguage.ENGLISH,
      recognizer: AndroidSpeechRecognizer =
          AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler)),
      onDevice: Boolean = false,
  ): Session {
    val events = mutableListOf<SpeechEvent>()
    val job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          recognizer.listen(language, onDevice).toList(events)
        }
    shadowOf(Looper.getMainLooper()).idle()
    return Session(events, job)
  }

  /** Asks whether [language] works offline, and lets the phone receive the question. */
  private fun TestScope.askOffline(
      language: SpeechLanguage = SpeechLanguage.ENGLISH,
      recognizer: AndroidSpeechRecognizer =
          AndroidSpeechRecognizer(context, UnconfinedTestDispatcher(testScheduler)),
  ): Deferred<OfflineSpeech> {
    val answer =
        backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
          recognizer.offlineSupport(language)
        }
    shadowOf(Looper.getMainLooper()).idle()
    return answer
  }

  /** The phone's answer about its on-device languages. */
  private fun support(
      installed: List<String> = emptyList(),
      pending: List<String> = emptyList(),
      downloadable: List<String> = emptyList(),
  ): RecognitionSupport =
      RecognitionSupport.Builder()
          .setInstalledOnDeviceLanguages(installed)
          .setPendingOnDeviceLanguages(pending)
          .setSupportedOnDeviceLanguages(downloadable)
          .build()

  /** Fails the test: the screen's context was used instead of the app's. */
  private fun screenUsed(): Nothing = throw AssertionError("The screen's context was used")

  /** Android's results holding [text] as the most likely one. */
  private fun results(text: String) =
      Bundle().apply {
        putStringArrayList(AndroidRecognizer.RESULTS_RECOGNITION, arrayListOf(text, "other"))
      }

  /** Makes the phone look like it has a speech recognizer, as most Android phones do. */
  private fun installRecognitionService() {
    val packageManager = shadowOf(context.packageManager)
    packageManager.addServiceIfNotPresent(RECOGNITION_SERVICE)
    packageManager.addIntentFilterForService(
        RECOGNITION_SERVICE,
        IntentFilter(RecognitionService.SERVICE_INTERFACE),
    )
  }

  private companion object {
    val RECOGNITION_SERVICE = ComponentName("com.example.speech", "com.example.speech.Service")
  }
}
