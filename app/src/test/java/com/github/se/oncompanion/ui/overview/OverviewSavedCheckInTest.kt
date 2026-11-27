// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.navigation.NavHostController
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.domain.planning.ownPlanning
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.planning.FakePlanningRepository
import com.github.se.oncompanion.model.speech.FakeSpeechRecognizer
import com.github.se.oncompanion.model.symptom.FakeSymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Screen
import com.github.se.oncompanion.ui.symptom.AddSymptomViewModel
import java.io.IOException
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tests of the check-in once the patient saved a symptom today: the entry instead of the tiles, its
 * actions, and "Speak how you feel".
 */
@RunWith(AndroidJUnit4::class)
class OverviewSavedCheckInTest {

  // An activity rule, so the phone can be rotated (the activity recreated)
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private val context
    get() = composeTestRule.activity

  private val morning = LocalDateTime.of(2026, 10, 3, 10, 0)

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)

  /** Nausea 7/10 and Fatigue 6/10, felt at 09:30 and saved then. */
  private val entry =
      SymptomEntry(
          id = "e1",
          types = listOf(SymptomType.NAUSEA, SymptomType.FATIGUE),
          occurredAt = morning.withMinute(30).withHour(9).toInstant(ZoneOffset.UTC),
          strengths = mapOf(SymptomType.NAUSEA to 7, SymptomType.FATIGUE to 6),
          createdAt = morning.withMinute(30).withHour(9).toInstant(ZoneOffset.UTC),
      )

  private val saved =
      OverviewUiState(
          now = morning,
          isLoading = false,
          savedToday = entry.toLatestEntry(ZoneOffset.UTC),
      )

  /** What each action was called with, in order. */
  private val calls = mutableListOf<String>()

  private fun showContent(state: OverviewUiState, caregiverOf: String? = null) {
    composeTestRule.runOnUiThread {
      context.setContent {
        OverviewContent(
            uiState = state,
            onShortcutClick = { calls += "shortcut $it" },
            onOpenPlanning = {},
            onOpenAppointment = {},
            onRetry = {},
            onLogSymptom = { calls += "log $it" },
            onUndoSaved = { calls += "undo $it" },
            onEditSaved = { calls += "edit $it" },
            caregiverOf = caregiverOf,
            onSpeak = { calls += "speak" },
        )
      }
    }
  }

  // ----- The card, for each state -----

  @Test
  fun theCheckIn_showsTheEntrySavedToday_insteadOfItsTiles_onlyForThePatient() {
    showContent(saved)
    node(C.Tag.overview_saved).performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.overview_saved_title)).assertExists()
    composeTestRule.onNodeWithText("09:30").assertExists()
    composeTestRule.onNodeWithText("Nausea\u00A07/10 · Fatigue\u00A06/10").assertExists()
    node(C.Tag.overview_check_in).assertDoesNotExist()

    // Nothing saved today: the tiles
    showContent(saved.copy(savedToday = null))
    node(C.Tag.overview_check_in).assertExists()
    node(C.Tag.overview_saved).assertDoesNotExist()

    // A caregiver's Overview has no check-in at all
    showContent(saved, caregiverOf = "Anne")
    node(C.Tag.overview_saved).assertDoesNotExist()
    node(C.Tag.overview_check_in).assertDoesNotExist()
  }

  @Test
  fun eachAction_callsItsCallback_withTheEntryShown() {
    showContent(saved.copy(canSpeak = true))
    listOf(
            C.Tag.overview_saved_undo,
            C.Tag.overview_saved_edit,
            C.Tag.overview_saved_log_another,
            C.Tag.overview_speak,
            C.Tag.overview_journal_link,
        )
        .forEach { node(it).performScrollTo().performClick() }

    composeTestRule.runOnIdle {
      assertEquals(
          listOf(
              "undo e1",
              "edit e1",
              "log null",
              "speak",
              "shortcut ${OverviewShortcut.SYMPTOMS}",
          ),
          calls,
      )
    }
  }

  @Test
  fun speakHowYouFeel_staysUnderLogAnother_onlyWhenThePhoneCanListen() {
    showContent(saved.copy(canSpeak = true))
    val logAnother = bounds(C.Tag.overview_saved_log_another)
    node(C.Tag.overview_speak).performScrollTo().assertIsDisplayed()
    assertTrue(bounds(C.Tag.overview_speak).top >= logAnother.bottom)

    showContent(saved)
    node(C.Tag.overview_saved).assertExists()
    node(C.Tag.overview_speak).assertDoesNotExist()
  }

  // ----- Layout (the rows depend on the measured text) -----

  private fun bounds(tag: String) = node(tag).getUnclippedBoundsInRoot()

  private fun summaryBounds() =
      composeTestRule
          .onNodeWithText("Nausea\u00A07/10 · Fatigue\u00A06/10", useUnmergedTree = true)
          .getUnclippedBoundsInRoot()

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(qualifiers = "w390dp-h874dp")
  fun undoAndEdit_sitBesideTheEntry_asInTheFigma() {
    showContent(saved)
    node(C.Tag.overview_saved_entry).performScrollTo()
    val summary = summaryBounds()
    val undo = bounds(C.Tag.overview_saved_undo)
    val edit = bounds(C.Tag.overview_saved_edit)
    assertTrue("$undo after $summary", undo.left >= summary.right)
    assertTrue("$undo beside $summary", undo.top < summary.bottom)
    assertEquals(undo.top, edit.top)
    // Undo and Edit leave the summary room for a whole symptom per line: "Nausea 7/10 ·"
    val layouts = mutableListOf<TextLayoutResult>()
    composeTestRule
        .onNodeWithText("Nausea\u00A07/10 · Fatigue\u00A06/10", useUnmergedTree = true)
        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    val layout = layouts.single()
    val firstLine = layout.layoutInput.text.text.substring(0, layout.getLineEnd(0))
    assertTrue(firstLine, layout.lineCount <= 2 && firstLine.trimEnd().endsWith("·"))
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(qualifiers = "w360dp-h640dp", fontScale = 2f)
  fun atLargeFont_undoAndEditGoUnderTheEntry_andEverythingCanBeReached() {
    showContent(saved.copy(canSpeak = true))
    node(C.Tag.overview_saved_entry).performScrollTo()
    val summary = summaryBounds()
    val undo = bounds(C.Tag.overview_saved_undo)
    assertTrue("$undo under $summary", undo.top >= summary.bottom)
    assertEveryActionFullyVisible()
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun inLandscape_everyActionCanBeReached_whole() {
    showContent(saved.copy(canSpeak = true))
    assertEveryActionFullyVisible()
  }

  /** Undo, Edit, "Log another", Speak and Journal can each be scrolled to and are shown whole. */
  private fun assertEveryActionFullyVisible() {
    listOf(
            C.Tag.overview_saved_undo,
            C.Tag.overview_saved_edit,
            C.Tag.overview_saved_log_another,
            C.Tag.overview_speak,
            C.Tag.overview_journal_link,
        )
        .forEach { node(it).performScrollTo().assertIsFullyVisible() }
  }

  // ----- On the Overview, over its ViewModel -----

  /** Records the routes it is asked to open instead of navigating. */
  private class RecordingNavigationActions(navController: NavHostController) :
      NavigationActions(navController) {
    val openedRoutes = mutableListOf<String>()

    override fun navigateTo(screen: String) {
      openedRoutes += screen
    }
  }

  private class Overview(val symptoms: FakeSymptomRepository, val show: () -> Unit) {
    lateinit var navigationActions: RecordingNavigationActions
  }

  /**
   * The patient's Overview with [entries] saved today (by default [entry]), over ViewModels that
   * survive a rotation.
   */
  private fun overview(vararg entries: SymptomEntry = arrayOf(entry)): Overview {
    val auth =
        FakeAuthRepository(onSignIn = { AuthUser(uid = "patient", displayName = "Alex") }).also {
          runBlocking { it.signInWithGoogle("token") }
        }
    val clock = Clock.fixed(morning.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    val symptoms =
        FakeSymptomRepository(clock = { clock.instant() }).apply { seed("patient", *entries) }
    val viewModel =
        OverviewViewModel(
            authRepository = auth,
            profileRepository = FakeUserProfileRepository(),
            observePlanning = ownPlanning(FakePlanningRepository()),
            clock = clock,
            zoneId = ZoneOffset.UTC,
            symptomRepository = symptoms,
        )
    val checkIn =
        AddSymptomViewModel(
            auth,
            symptoms,
            clock,
            speechRecognizer = FakeSpeechRecognizer(),
            symptomName = { it.name },
        )
    lateinit var overview: Overview
    overview =
        Overview(symptoms) {
          context.setContent {
            val navigationActions =
                RecordingNavigationActions(TestNavHostController(LocalContext.current))
            overview.navigationActions = navigationActions
            OverviewScreen(navigationActions, viewModel = viewModel, checkIn = { checkIn })
          }
        }
    composeTestRule.runOnUiThread(overview.show)
    return overview
  }

  @Test
  fun undo_deletesTheEntry_andBringsBackTheTiles() {
    val overview = overview()
    node(C.Tag.overview_saved_undo).performScrollTo().performClick()
    composeTestRule.waitForIdle()

    assertTrue(overview.symptoms.symptoms("patient").isEmpty())
    node(C.Tag.overview_saved).assertDoesNotExist()
    node(C.Tag.overview_check_in).assertExists()
  }

  @Test
  fun undo_tappedAgainAndAgain_undoesEachEntryShown_untilTheTiles() {
    // Saved at 08:00, then at 09:30 ([entry])
    val earlier =
        entry.copy(
            id = "e0",
            types = listOf(SymptomType.HEADACHE),
            strengths = mapOf(SymptomType.HEADACHE to 4),
            occurredAt = morning.withHour(8).toInstant(ZoneOffset.UTC),
            createdAt = morning.withHour(8).toInstant(ZoneOffset.UTC),
        )
    val overview = overview(earlier, entry)

    // The card changes to the entry saved before; the next tap undoes that one
    node(C.Tag.overview_saved_undo).performScrollTo().performClick()
    composeTestRule.onNodeWithText("Headache 4/10").assertExists()
    composeTestRule.onNodeWithText("08:00").assertExists()
    assertEquals(listOf("e0"), overview.symptoms.symptoms("patient").map { it.id })

    node(C.Tag.overview_saved_undo).performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertTrue(overview.symptoms.symptoms("patient").isEmpty())
    node(C.Tag.overview_saved).assertDoesNotExist()
    node(C.Tag.overview_check_in).assertExists()
  }

  @Test
  fun aFailedUndo_keepsTheEntry_andSaysSo() {
    val overview = overview()
    overview.symptoms.writeError = IOException("offline")
    node(C.Tag.overview_saved_undo).performScrollTo()
    // Paused, so waiting for the screen doesn't run the snackbar's whole few seconds
    composeTestRule.mainClock.autoAdvance = false
    node(C.Tag.overview_saved_undo).performClick()
    composeTestRule.mainClock.advanceTimeBy(SNACKBAR_SHOWN_MS)

    composeTestRule
        .onNodeWithText(context.getString(R.string.overview_saved_undo_failed))
        .assertIsDisplayed()
    node(C.Tag.overview_saved).assertExists()
    assertEquals(listOf("e1"), overview.symptoms.symptoms("patient").map { it.id })
  }

  @Test
  fun edit_opensTheEntryInTheForm_andLogAnother_opensTheSheetEmpty() {
    val overview = overview()
    node(C.Tag.overview_saved_edit).performScrollTo().performClick()
    composeTestRule.runOnIdle {
      assertEquals(listOf(Screen.symptomEdit("e1")), overview.navigationActions.openedRoutes)
    }

    node(C.Tag.overview_saved_log_another).performScrollTo().performClick()
    SymptomType.entries.forEach {
      node(C.Tag.overviewCheckInSheet(it.name)).performScrollTo().assertIsOff()
    }
  }

  @Test
  fun phoneRotated_theCardStays() {
    val overview = overview()
    node(C.Tag.overview_saved).performScrollTo().assertIsDisplayed()

    // Rotating recreates the activity; the ViewModels survive it
    composeTestRule.activityRule.scenario.recreate()
    composeTestRule.runOnUiThread(overview.show)

    node(C.Tag.overview_saved).performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Nausea\u00A07/10 · Fatigue\u00A06/10").assertExists()
  }

  private companion object {
    /** Long enough for the snackbar to appear, well before it goes. */
    const val SNACKBAR_SHOWN_MS = 1_000L
  }
}
