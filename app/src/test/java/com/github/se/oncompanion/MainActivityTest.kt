// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion

import android.content.ComponentName
import android.content.Intent
import android.view.WindowManager
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.domain.auth.SignOut
import com.github.se.oncompanion.domain.auth.SignOutProvider
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.session.FakeLocalSessionRepository
import com.github.se.oncompanion.model.settings.AppearancePreference
import com.github.se.oncompanion.model.settings.FakeSettingsRepository
import com.github.se.oncompanion.model.settings.SettingsRepositoryProvider
import com.github.se.oncompanion.model.speech.AndroidSpeechRecognizer
import com.github.se.oncompanion.model.speech.SpeechRecognizerProvider
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.assertSameColor
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.reminder.AppointmentRemindersViewModel
import com.github.se.oncompanion.ui.theme.AppAppearanceViewModel
import com.github.se.oncompanion.ui.theme.SurfaceDark
import com.github.se.oncompanion.ui.theme.SurfaceLight
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

  private val originalSettings = SettingsRepositoryProvider.repository
  private val deviceSettings = FakeSettingsRepository()
  private val originalAuth = AuthRepositoryProvider.repository
  private val originalSignOut = SignOutProvider.signOut

  @get:Rule(order = 0)
  val providers =
      object : ExternalResource() {
        override fun before() {
          SettingsRepositoryProvider.repository = deviceSettings
          val auth = FakeAuthRepository()
          AuthRepositoryProvider.repository = auth
          SignOutProvider.signOut = SignOut(auth, FakeLocalSessionRepository())
          SpeechRecognizerProvider.reset()
        }

        override fun after() {
          SettingsRepositoryProvider.repository = originalSettings
          AuthRepositoryProvider.repository = originalAuth
          SignOutProvider.signOut = originalSignOut
          SpeechRecognizerProvider.reset()
        }
      }

  @get:Rule(order = 1) val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun reminderRequest_keepsItsActivityOwnerAcrossRotation_withoutBypassingSignIn() {
    val model =
        ViewModelProvider(composeTestRule.activity)[AppointmentRemindersViewModel::class.java]
    composeTestRule.runOnIdle { model.open("patient", "visit") }
    composeTestRule.activityRule.scenario.recreate()
    composeTestRule.waitForIdle()
    composeTestRule.runOnIdle {
      assertSame(
          model,
          ViewModelProvider(composeTestRule.activity)[AppointmentRemindersViewModel::class.java],
      )
      assertEquals(null, model.uiState.value.appointmentId)
    }
    composeTestRule.onNodeWithTag(C.Tag.sign_in_screen).assertIsDisplayed()
  }

  @Test
  fun warmReminderIntent_reusesTheActivityModel_andCannotBypassSignIn() {
    val activity = composeTestRule.activity
    val originalIntent = activity.intent
    val model = ViewModelProvider(activity)[AppointmentRemindersViewModel::class.java]
    val incoming =
        android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            "oncompanion-reminder://local/patient/appointment/visit".toUri(),
        )
    try {
      composeTestRule.runOnIdle {
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "onNewIntent",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(
                android.content.Intent::class.java,
                incoming,
            ),
        )
        assertSame(incoming, activity.intent)
      }
      composeTestRule.waitForIdle()
      composeTestRule.runOnIdle {
        assertSame(model, ViewModelProvider(activity)[AppointmentRemindersViewModel::class.java])
        assertEquals(null, model.uiState.value.appointmentId)
      }
      composeTestRule.onNodeWithTag(C.Tag.sign_in_screen).assertIsDisplayed()
    } finally {
      // ActivityScenario identifies lifecycle callbacks using its original launch intent.
      composeTestRule.runOnUiThread { activity.intent = originalIntent }
    }
  }

  /**
   * A fresh tap is queued for sign-in; relaunching that intent from Recents never queues it again.
   */
  @Test
  @Config(sdk = [28, 34])
  fun coldReminderIntent_ignoresHistoryButAcceptsFreshTaps() {
    for (fromHistory in listOf(false, true)) {
      val saved = SavedStateHandle()
      val incoming =
          Intent(
                  Intent.ACTION_VIEW,
                  "oncompanion-reminder://local/patient/appointment/visit".toUri(),
              )
              .apply { if (fromHistory) addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) }
      val controller = Robolectric.buildActivity(MainActivity::class.java, incoming)
      // Capture the real ViewModel's saved request while signed out, before navigation consumes it.
      ViewModelProvider(
          controller.get(),
          object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppointmentRemindersViewModel(saved) as T
          },
      )[AppointmentRemindersViewModel::class.java]
      try {
        controller.create().start().resume()
        assertEquals(
            if (fromHistory) null else listOf("patient", "visit"),
            saved.get<List<String>>("appointment_reminder_request"),
        )
      } finally {
        controller.pause().stop().destroy()
      }
    }
  }

  @Test
  fun reminderInstallation_preservesAnExplicitCoordinator() {
    val provider = com.github.se.oncompanion.domain.reminder.AppointmentRemindersProvider
    val original = provider.coordinator
    try {
      val replacement =
          com.github.se.oncompanion.domain.reminder.ReconcileAppointmentReminders(
              com.github.se.oncompanion.model.reminder.AppointmentReminderText { item, _ ->
                com.github.se.oncompanion.model.reminder.ReminderText(item.title, "Fictional date")
              }
          )
      provider.coordinator = replacement
      provider.install(composeTestRule.activity.applicationContext)
      assertSame(replacement, provider.coordinator)
    } finally {
      provider.coordinator = original
    }
  }

  @Test
  @Config(qualifiers = "notnight")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun savedDarkAppearanceStylesTheRealRootAndSystemBars() {
    assertRootAppearance(AppearancePreference.DARK)
  }

  @Test
  @Config(qualifiers = "night")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun savedLightAppearanceStylesTheRealRootAndSystemBars() {
    assertRootAppearance(AppearancePreference.LIGHT)
  }

  @Test
  @Config(sdk = [28])
  fun navigationBarRetainsDefaultScrimsOnAndroid9() {
    runBlocking { deviceSettings.setAppearance(AppearancePreference.DARK) }
    composeTestRule.waitForIdle()
    assertEquals(0x801b1b1b.toInt(), composeTestRule.activity.window.navigationBarColor)
    runBlocking { deviceSettings.setAppearance(AppearancePreference.LIGHT) }
    composeTestRule.waitForIdle()
    assertEquals(0xe6ffffff.toInt(), composeTestRule.activity.window.navigationBarColor)
  }

  @Test
  @Config(sdk = [28])
  fun splashWaitsUntilSavedAppearanceIsRead() {
    val gate = CompletableDeferred<Unit>()
    deviceSettings.readGate = gate
    // A fresh ViewModel store models a cold start rather than retaining the already-read choice.
    composeTestRule.runOnIdle { composeTestRule.activity.viewModelStore.clear() }
    composeTestRule.activityRule.scenario.recreate()
    composeTestRule.waitForIdle()
    val content = composeTestRule.activity.findViewById<android.view.View>(android.R.id.content)
    composeTestRule.runOnIdle {
      assertTrue(
          "Splash must cancel drawing before appearance is read",
          content.viewTreeObserver.dispatchOnPreDraw(),
      )
    }
    gate.complete(Unit)
    composeTestRule.waitForIdle()
    composeTestRule.runOnIdle {
      assertFalse(
          "Startup is ready after appearance is read",
          content.viewTreeObserver.dispatchOnPreDraw(),
      )
    }
  }

  private fun assertRootAppearance(choice: AppearancePreference) {
    runBlocking { deviceSettings.setAppearance(choice) }
    composeTestRule.waitForIdle()
    composeTestRule.activityRule.scenario.recreate()
    composeTestRule.waitForIdle()
    val pixels =
        composeTestRule.onNodeWithTag(C.Tag.main_screen_container).captureToImage().toPixelMap()
    // The corner is outside sign-in controls and belongs to the real root background.
    assertSameColor(
        "root surface",
        if (choice == AppearancePreference.DARK) SurfaceDark else SurfaceLight,
        pixels[pixels.width - 2, pixels.height - 2],
    )
    val activity = composeTestRule.activity
    assertEquals(
        choice == AppearancePreference.LIGHT,
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .isAppearanceLightStatusBars,
    )
  }

  @Test
  fun launchingMainActivity_displaysMainContainer() {
    composeTestRule.onNodeWithTag(C.Tag.main_screen_container).assertIsDisplayed()
  }

  @Test
  fun launchingMainActivity_startsOnSignInScreen() {
    composeTestRule.onNodeWithTag(C.Tag.sign_in_screen).assertIsDisplayed()
    composeTestRule
        .onNodeWithText(composeTestRule.activity.getString(R.string.sign_in_welcome))
        .assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.google_sign_in_button).assertIsDisplayed()
  }

  @Test
  fun launchingMainActivity_doesNotShowOtherScreens() {
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_screen).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_information_screen).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertDoesNotExist()
  }

  @Test
  fun mainActivity_launchesWithSplashScreenTheme() {
    val activity = composeTestRule.activity
    val info =
        activity.packageManager.getActivityInfo(
            ComponentName(activity, MainActivity::class.java),
            0,
        )
    assertEquals(R.style.Theme_oncompanion_Starting, info.theme)
  }

  @Test
  fun mainActivity_resizesForTheKeyboardInsteadOfSlidingTheWindowUp() {
    val softInputMode = composeTestRule.activity.window.attributes.softInputMode
    assertEquals(
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
    )
  }

  @Test
  fun mainActivity_givesThePhonesSpeechRecognizerTheAppsContext() {
    assertTrue(SpeechRecognizerProvider.recognizer is AndroidSpeechRecognizer)
  }

  /** Activity recreation reloads the same device override while retaining the account policy. */
  @Test
  fun savedAppearanceSurvivesActivityRecreation() {
    runBlocking { deviceSettings.setAppearance(AppearancePreference.DARK) }
    composeTestRule.waitUntil {
      ViewModelProvider(composeTestRule.activity)[AppAppearanceViewModel::class.java]
          .uiState
          .value
          .appearance == AppearancePreference.DARK
    }
    composeTestRule.activityRule.scenario.recreate()
    composeTestRule.waitUntil {
      ViewModelProvider(composeTestRule.activity)[AppAppearanceViewModel::class.java]
          .uiState
          .value
          .appearance == AppearancePreference.DARK
    }
    composeTestRule.onNodeWithTag(C.Tag.main_screen_container).assertIsDisplayed()
  }
}
