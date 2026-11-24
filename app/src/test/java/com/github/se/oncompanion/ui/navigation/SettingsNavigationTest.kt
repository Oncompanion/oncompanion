// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.settings.DateFormatPreference
import com.github.se.oncompanion.model.settings.FakeSettingsRepository
import com.github.se.oncompanion.model.settings.SettingsRepositoryProvider
import com.github.se.oncompanion.model.settings.TimeFormatPreference
import com.github.se.oncompanion.model.user.*
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.auth.FakeGoogleCredentialProvider
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Verifies that Settings is reached from Profile and preserves its back stack. */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val originalSettings = SettingsRepositoryProvider.repository
  private val originalAuth = AuthRepositoryProvider.repository
  private val originalProfiles = UserProfileRepositoryProvider.repository

  @After
  fun restoreProviders() {
    SettingsRepositoryProvider.repository = originalSettings
    AuthRepositoryProvider.repository = originalAuth
    UserProfileRepositoryProvider.repository = originalProfiles
  }

  @Test
  fun profileSettings_backReturnsToProfile() {
    SettingsRepositoryProvider.repository = FakeSettingsRepository()
    val auth = FakeAuthRepository()
    val user = runBlocking { auth.signInWithGoogle("settings") }
    AuthRepositoryProvider.repository = auth
    UserProfileRepositoryProvider.repository =
        FakeUserProfileRepository().apply { seed(UserProfile(user.uid, Role.PATIENT, "Alex")) }
    lateinit var nav: TestNavHostController
    compose.setContent {
      val context = LocalContext.current
      nav = remember {
        TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
      }
      OncompanionTheme {
        AppNavHost(
            navController = nav,
            startRoute = Route.PROFILE,
            credentialProvider = FakeGoogleCredentialProvider(),
        )
      }
    }
    compose.onNodeWithTag(C.Tag.profile_settings).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.settings_screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.settings_appearance).performClick()
    compose.onNodeWithTag(C.Tag.appearance_screen).assertIsDisplayed()
    compose.runOnIdle { assertEquals(Screen.APPEARANCE, nav.currentDestination?.route) }
    compose.onNodeWithTag(C.Tag.settings_back).performClick()
    compose.onNodeWithTag(C.Tag.settings_screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.settings_appearance).performClick()
    compose.onNodeWithTag(C.Tag.appearance_screen).assertIsDisplayed()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(C.Tag.settings_screen).assertIsDisplayed()
    compose.runOnIdle { assertEquals(Screen.SETTINGS, nav.currentDestination?.route) }
    compose.onNodeWithTag(C.Tag.settings_back).performClick()
    compose.onNodeWithTag(C.Tag.profile_screen).assertIsDisplayed()
    compose.runOnIdle { assertEquals(Screen.PROFILE, nav.currentDestination?.route) }
    compose.onNodeWithTag(C.Tag.profile_settings).performScrollTo().performClick()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(C.Tag.profile_screen).assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE, fontScale = 2f)
  fun dateAndTimeNavigation_updatesPreviewAndReopensWithSavedChoices() {
    val repo = FakeSettingsRepository()
    SettingsRepositoryProvider.repository = repo
    val auth = FakeAuthRepository()
    val user = runBlocking { auth.signInWithGoogle("settings") }
    AuthRepositoryProvider.repository = auth
    UserProfileRepositoryProvider.repository =
        FakeUserProfileRepository().apply { seed(UserProfile(user.uid, Role.PATIENT, "Alex")) }
    compose.setContent {
      val context = LocalContext.current
      val nav = remember {
        TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
      }
      OncompanionTheme {
        AppNavHost(
            navController = nav,
            startRoute = Route.SETTINGS,
            credentialProvider = FakeGoogleCredentialProvider(),
        )
      }
    }

    compose
        .onNodeWithTag(C.Tag.settings_date_time)
        .performScrollTo()
        .apply { assertIsFullyVisible() }
        .performClick()
    compose
        .onNodeWithTag(C.Tag.settings_time_format)
        .performScrollTo()
        .apply { assertIsFullyVisible() }
        .performClick()
    compose
        .onNodeWithTag(C.Tag.settingsTimeChoice("TWENTY_FOUR_HOUR"))
        .performScrollTo()
        .assertIsFullyVisible()
    compose
        .onNodeWithTag(C.Tag.settingsTimeChoice("FOLLOW_DEVICE"))
        .performScrollTo()
        .assertIsSelected()
    compose
        .onNodeWithTag(C.Tag.settingsTimeChoice("TWELVE_HOUR"))
        .performScrollTo()
        .apply { assertIsFullyVisible() }
        .performClick()
    compose.onNodeWithTag(C.Tag.settingsTimeChoice("TWELVE_HOUR")).assertIsSelected()
    compose.onNodeWithTag(C.Tag.settings_back).performClick()
    compose
        .onNodeWithTag(C.Tag.settings_date_format)
        .performScrollTo()
        .apply { assertIsFullyVisible() }
        .performClick()
    compose
        .onNodeWithTag(C.Tag.settingsDateChoice("YEAR_MONTH_DAY"))
        .performScrollTo()
        .apply { assertIsFullyVisible() }
        .performClick()
    compose.onNodeWithTag(C.Tag.settingsDateChoice("YEAR_MONTH_DAY")).assertIsSelected()
    compose.onNodeWithTag(C.Tag.settings_back).performClick()
    compose
        .onNodeWithTag(C.Tag.settings_format_preview)
        .performScrollTo()
        .assertTextContains("2026-11-18", substring = true)
    compose.runOnIdle {
      assertEquals(DateFormatPreference.YEAR_MONTH_DAY, repo.settings.value.dateFormat)
      assertEquals(TimeFormatPreference.TWELVE_HOUR, repo.settings.value.timeFormat)
    }
    compose.onNodeWithTag(C.Tag.settings_back).performClick()
    compose
        .onNodeWithTag(C.Tag.settings_date_time)
        .performScrollTo()
        .apply { assertIsFullyVisible() }
        .performClick()
    compose
        .onNodeWithTag(C.Tag.settings_date_format)
        .performScrollTo()
        .apply { assertIsFullyVisible() }
        .performClick()
    compose.onNodeWithTag(C.Tag.settingsDateChoice("YEAR_MONTH_DAY")).assertIsSelected()
  }
}
