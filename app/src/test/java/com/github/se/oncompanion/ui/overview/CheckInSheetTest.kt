// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.testing.TestNavHostController
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.domain.planning.ownPlanning
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.planning.FakePlanningRepository
import com.github.se.oncompanion.model.speech.FakeSpeechRecognizer
import com.github.se.oncompanion.model.speech.SpeechRecognizerProvider
import com.github.se.oncompanion.model.symptom.FakeSymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomRepositoryProvider
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.common.SavePhase
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.symptom.AddSymptomError
import com.github.se.oncompanion.ui.symptom.AddSymptomUiState
import com.github.se.oncompanion.ui.symptom.AddSymptomViewModel
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class CheckInSheetTest {

  // An activity rule, so the phone can be rotated (the activity recreated)
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private val context
    get() = composeTestRule.activity

  private val morning = LocalDateTime.of(2026, 10, 3, 9, 30)

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

  private fun tile(type: SymptomType) = node(C.Tag.overviewCheckInSheet(type.name))

  // ----- The sheet, for each state -----

  /** What each action of the sheet was called with, in order. */
  private val calls = mutableListOf<String>()

  private val actions =
      CheckInActions(
          onToggleType = { calls += "toggle $it" },
          onDateChange = { calls += "date $it" },
          onTimeChange = { calls += "time $it" },
          onNotesChange = { calls += "notes $it" },
          onSave = { calls += "save" },
          onClose = { calls += "close" },
      )

  private val nausea =
      AddSymptomUiState(
          now = morning,
          types = listOf(SymptomType.NAUSEA),
          strengths = mapOf(SymptomType.NAUSEA to 4),
          strengthOf = SymptomType.NAUSEA,
      )

  /** Shows the sheet in [state], replacing what was shown, so a test can show another state. */
  private fun showSheet(state: AddSymptomUiState) {
    composeTestRule.runOnUiThread {
      context.setContent { OncompanionTheme { CheckInSheet(state, actions) } }
    }
  }

  @Test
  fun everySymptomHasATile_sayingWhetherItIsPicked_andThePickedOneIsAskedHowStrong() {
    showSheet(nausea)

    SymptomType.entries.forEach {
      val tile = tile(it).performScrollTo()
      if (it == SymptomType.NAUSEA) tile.assertIsOn() else tile.assertIsOff()
    }
    composeTestRule
        .onNodeWithText("How strong is your nausea? (optional)")
        .performScrollTo()
        .assertIsDisplayed()
    node(C.Tag.overview_check_in_sheet_when).assertTextContains("Now, 09:30 · Change")
    node(C.Tag.overview_check_in_sheet_save).performScrollTo().assertIsEnabled()
    node(C.Tag.overview_check_in_sheet_saving).assertDoesNotExist()
  }

  @Test
  @Config(qualifiers = "fr-rCH")
  fun checkInSheetShowsFrenchLabelsAndFormattedTime() {
    showSheet(nausea)

    composeTestRule.onNodeWithText("Comment vous sentez-vous ?").assertIsDisplayed()
    composeTestRule.onNodeWithContentDescription("Fermer sans enregistrer").assertIsDisplayed()
    node(C.Tag.overview_check_in_sheet_when)
        .performScrollTo()
        .assertTextContains("Maintenant, 09:30 · Modifier")
    composeTestRule
        .onNodeWithText("Ajouter une note (facultatif)")
        .performScrollTo()
        .assertIsDisplayed()
    node(C.Tag.overview_check_in_sheet_save)
        .performScrollTo()
        .assertTextContains("Enregistrer dans mon journal")

    showSheet(nausea.copy(date = morning.toLocalDate().minusDays(1), time = LocalTime.of(8, 15)))
    node(C.Tag.overview_check_in_sheet_when)
        .performScrollTo()
        .assertTextContains("Hier, 08:15 · Modifier")
  }

  @Test
  fun eachControl_callsItsAction() {
    showSheet(nausea)
    tile(SymptomType.FEVER).performScrollTo().performClick()
    tile(SymptomType.NAUSEA).performScrollTo().performClick()
    node(C.Tag.overview_check_in_sheet_note).performScrollTo().performTextInput("After lunch")
    node(C.Tag.overview_check_in_sheet_save).performScrollTo().performClick()
    node(C.Tag.overview_check_in_sheet_close).performClick()

    composeTestRule.runOnIdle {
      assertEquals(
          listOf(
              "toggle FEVER",
              "toggle NAUSEA",
              "notes After lunch",
              "save",
              "close",
          ),
          calls,
      )
    }
  }

  @Test
  fun whenRow_picksADayThenATime() {
    showSheet(nausea)

    node(C.Tag.overview_check_in_sheet_when).performScrollTo().performClick()
    node(C.Tag.symptom_add_picker_confirm).performClick()
    node(C.Tag.symptom_add_picker_confirm).performClick()

    composeTestRule.runOnIdle {
      assertEquals(listOf("date ${morning.toLocalDate()}", "time 09:30"), calls)
    }
  }

  @Test
  fun aChosenDayAndTime_areShown_andAFutureOneIsFlagged_andCantBeSaved() {
    // (state) to (the row's text, flagged)
    listOf(
            nausea.copy(date = LocalDate.of(2026, 10, 2), time = LocalTime.of(21, 0)) to
                ("Yesterday, 21:00 · Change" to false),
            nausea.copy(
                date = morning.toLocalDate(),
                time = LocalTime.of(23, 0),
                isInFuture = true,
            ) to ("Today, 23:00 · Change" to true),
        )
        .forEach { (state, expected) ->
          showSheet(state)
          val (text, flagged) = expected
          node(C.Tag.overview_check_in_sheet_when).assertTextContains(text)
          val error =
              composeTestRule.onNodeWithText(context.getString(R.string.symptom_add_in_future))
          val save = node(C.Tag.overview_check_in_sheet_save).performScrollTo()
          if (flagged) {
            error.assertIsDisplayed()
            save.assertIsNotEnabled()
          } else {
            error.assertDoesNotExist()
            save.assertIsEnabled()
          }
        }
  }

  @Test
  fun other_isAskedItsName_andCantBeSavedWithoutIt() {
    val other =
        AddSymptomUiState(
            now = morning,
            types = listOf(SymptomType.OTHER),
            strengthOf = SymptomType.OTHER,
        )
    showSheet(other)
    node(C.Tag.symptom_add_other_name).performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("How strong is it? (optional)").assertExists()
    node(C.Tag.overview_check_in_sheet_save).performScrollTo().assertIsNotEnabled()

    // Once named, it is called by its name
    showSheet(other.copy(otherName = "Hiccups"))
    composeTestRule.onNodeWithText("How strong is your Hiccups? (optional)").assertExists()
    node(C.Tag.overview_check_in_sheet_save).performScrollTo().assertIsEnabled()
  }

  @Test
  fun whileSaving_nothingClosesTheSheet_andItSaysItIsSaving() {
    showSheet(nausea.copy(save = SavePhase.SAVING))

    node(C.Tag.overview_check_in_sheet_saving).performScrollTo().assertIsDisplayed()
    node(C.Tag.overview_check_in_sheet_close).assertIsNotEnabled()
    Espresso.pressBack()
    composeTestRule.runOnIdle { assertEquals(emptyList<String>(), calls) }
    node(C.Tag.overview_check_in_sheet).assertExists()
  }

  @Test
  fun back_closesTheSheetWithoutSaving() {
    showSheet(nausea)
    Espresso.pressBack()
    composeTestRule.runOnIdle { assertEquals(listOf("close"), calls) }
  }

  @Test
  fun aSaveError_isShownInTheSheet() {
    AddSymptomError.entries.forEach { error ->
      showSheet(nausea.copy(error = error))
      val message =
          when (error) {
            AddSymptomError.NOT_SIGNED_IN -> R.string.symptom_add_error_not_signed_in
            AddSymptomError.SAVE_FAILED -> R.string.symptom_add_error_save_failed
          }
      node(C.Tag.overview_check_in_sheet_error)
          .performScrollTo()
          .assertTextContains(context.getString(message))
    }
  }

  // ----- On the Overview -----

  /** Records the routes it is asked to open instead of navigating. */
  private class RecordingNavigationActions(navController: NavHostController) :
      NavigationActions(navController) {
    val openedRoutes = mutableListOf<String>()

    override fun navigateTo(screen: String) {
      openedRoutes += screen
    }
  }

  /** The Overview and its check-in, over ViewModels that survive a rotation. */
  private class Overview(
      val symptoms: FakeSymptomRepository,
      val checkIn: AddSymptomViewModel,
      val show: () -> Unit,
  ) {
    lateinit var navigationActions: RecordingNavigationActions
  }

  /** A clock a test moves forward by hand. */
  private class MovingClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
  }

  private fun overview(
      clock: Clock = Clock.fixed(morning.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
  ): Overview {
    val auth =
        FakeAuthRepository(onSignIn = { AuthUser(uid = "patient", displayName = "Alex") }).also {
          runBlocking { it.signInWithGoogle("token") }
        }
    val symptoms = FakeSymptomRepository(clock = { clock.instant() })
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
        Overview(symptoms, checkIn) {
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

  private fun tapCheckInTile(type: SymptomType) =
      node(C.Tag.overviewCheckIn(type.name)).performScrollTo().performClick()

  @Test
  fun aTile_opensTheSheet_eachSymptomGetsItsStrength_andSavingStoresOneEntryAndClosesIt() {
    val overview = overview()
    tapCheckInTile(SymptomType.NAUSEA)
    tile(SymptomType.NAUSEA).assertIsOn()
    composeTestRule.runOnIdle { overview.checkIn.onStrengthChange(4) }
    tile(SymptomType.FATIGUE).performScrollTo().performClick()
    composeTestRule.runOnIdle { overview.checkIn.onStrengthChange(6) }

    node(C.Tag.overview_check_in_sheet_save).performScrollTo().performClick()
    composeTestRule.waitForIdle()

    node(C.Tag.overview_check_in_sheet).assertDoesNotExist()
    val entry = overview.symptoms.symptoms("patient").single()
    assertEquals(listOf(SymptomType.NAUSEA, SymptomType.FATIGUE), entry.types)
    assertEquals(mapOf(SymptomType.NAUSEA to 4, SymptomType.FATIGUE to 6), entry.strengths)
    assertTrue(overview.navigationActions.openedRoutes.isEmpty())
    // The check-in now says what was saved, instead of its tiles
    composeTestRule
        .onNodeWithText("Nausea\u00A04/10 · Fatigue\u00A06/10")
        .performScrollTo()
        .assertIsDisplayed()
    node(C.Tag.overview_check_in).assertDoesNotExist()
  }

  @Test
  fun aFailedSave_keepsTheSheetOpenWithWhatWasPicked_andNothingLeftSaving() {
    val overview = overview()
    overview.symptoms.writeError = IOException("disk full")
    tapCheckInTile(SymptomType.PAIN)
    tile(SymptomType.HEADACHE).performScrollTo().performClick()

    node(C.Tag.overview_check_in_sheet_save).performScrollTo().performClick()
    composeTestRule.waitForIdle()

    node(C.Tag.overview_check_in_sheet_error)
        .performScrollTo()
        .assertTextContains(context.getString(R.string.symptom_add_error_save_failed))
    node(C.Tag.overview_check_in_sheet_saving).assertDoesNotExist()
    tile(SymptomType.PAIN).performScrollTo().assertIsOn()
    tile(SymptomType.HEADACHE).performScrollTo().assertIsOn()

    // Saving again once it works stores the entry
    overview.symptoms.writeError = null
    node(C.Tag.overview_check_in_sheet_save).performScrollTo().performClick()
    composeTestRule.waitForIdle()
    node(C.Tag.overview_check_in_sheet).assertDoesNotExist()
    assertEquals(
        listOf(SymptomType.PAIN, SymptomType.HEADACHE),
        overview.symptoms.symptoms("patient").single().types,
    )
  }

  @Test
  fun closedMidEdit_savesNothing_andTheNextTileStartsAfresh() {
    val overview = overview()
    tapCheckInTile(SymptomType.NAUSEA)
    tile(SymptomType.FEVER).performScrollTo().performClick()
    node(C.Tag.overview_check_in_sheet_close).performClick()
    composeTestRule.waitForIdle()
    node(C.Tag.overview_check_in_sheet).assertDoesNotExist()

    // "More…" opens it empty
    node(C.Tag.overview_check_in_more).performScrollTo().performClick()
    SymptomType.entries.forEach { tile(it).performScrollTo().assertIsOff() }
    node(C.Tag.overview_check_in_sheet_save).performScrollTo().assertIsNotEnabled()
    assertTrue(overview.symptoms.symptoms("patient").isEmpty())
  }

  @Test
  fun whileOpen_theTimeFollowsTheClock_andATimeItPassesCanBeSaved() {
    val clock = MovingClock(morning.toInstant(ZoneOffset.UTC))
    val overview = overview(clock)
    tapCheckInTile(SymptomType.NAUSEA)
    val whenRow = node(C.Tag.overview_check_in_sheet_when).performScrollTo()
    whenRow.assertTextContains(context.getString(R.string.overview_check_in_sheet_now, "09:30"))

    // Ten minutes later, at the next tick
    clock.now = morning.plusMinutes(10).toInstant(ZoneOffset.UTC)
    composeTestRule.mainClock.advanceTimeBy(60_000)
    whenRow.assertTextContains(context.getString(R.string.overview_check_in_sheet_now, "09:40"))

    // A time still to come can't be saved, until the clock passes it
    composeTestRule.runOnIdle { overview.checkIn.onTimeChange(LocalTime.of(9, 45)) }
    node(C.Tag.overview_check_in_sheet_save).performScrollTo().assertIsNotEnabled()
    clock.now = morning.plusMinutes(20).toInstant(ZoneOffset.UTC)
    composeTestRule.mainClock.advanceTimeBy(60_000)
    node(C.Tag.overview_check_in_sheet_save).performScrollTo().assertIsEnabled()
  }

  @Test
  fun phoneRotated_theSheetStaysOpen_withWhatWasPicked() {
    val auth =
        FakeAuthRepository(onSignIn = { AuthUser(uid = "patient", displayName = "Alex") }).also {
          runBlocking { it.signInWithGoogle("token") }
        }
    val originalAuth = AuthRepositoryProvider.repository
    val originalSymptoms = SymptomRepositoryProvider.repository
    val originalProfiles = UserProfileRepositoryProvider.repository
    AuthRepositoryProvider.repository = auth
    SymptomRepositoryProvider.repository = FakeSymptomRepository()
    UserProfileRepositoryProvider.repository = FakeUserProfileRepository()
    SpeechRecognizerProvider.recognizer = FakeSpeechRecognizer()
    try {
      val clock = Clock.fixed(morning.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
      val viewModel =
          OverviewViewModel(
              authRepository = auth,
              profileRepository = FakeUserProfileRepository(),
              observePlanning = ownPlanning(FakePlanningRepository()),
              clock = clock,
              zoneId = ZoneOffset.UTC,
          )
      // The check-in's ViewModel as the app makes it, kept by the activity across a rotation
      val show = {
        context.setContent {
          OverviewScreen(
              RecordingNavigationActions(TestNavHostController(LocalContext.current)),
              viewModel = viewModel,
          )
        }
      }
      composeTestRule.runOnUiThread(show)
      tapCheckInTile(SymptomType.NAUSEA)
      tile(SymptomType.OTHER).performScrollTo().performClick()
      node(C.Tag.symptom_add_other_name).performScrollTo().performTextInput("Hiccups")

      composeTestRule.activityRule.scenario.recreate()
      composeTestRule.runOnUiThread(show)

      tile(SymptomType.NAUSEA).performScrollTo().assertIsOn()
      tile(SymptomType.OTHER).performScrollTo().assertIsOn()
      node(C.Tag.symptom_add_other_name).performScrollTo().assertTextContains("Hiccups")
    } finally {
      AuthRepositoryProvider.repository = originalAuth
      SymptomRepositoryProvider.repository = originalSymptoms
      UserProfileRepositoryProvider.repository = originalProfiles
      SpeechRecognizerProvider.reset()
    }
  }

  // ----- Large text and landscape -----

  /** Material's description of the sheet's drag handle. */
  private fun dragHandle() =
      composeTestRule.onNodeWithContentDescription("Drag handle", useUnmergedTree = true)

  @Test
  fun upright_theSheetHasItsHandle() {
    showSheet(nausea)
    dragHandle().assertExists()
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun sideways_theHandleGivesItsRoomToTheKeyboard() {
    showSheet(nausea)
    node(C.Tag.overview_check_in_sheet_close).assertExists()
    dragHandle().assertDoesNotExist()
  }

  @Test
  @Config(qualifiers = "w360dp-h640dp", fontScale = 2f)
  fun at200PercentText_everyTileAndSave_canBeReachedWhole() {
    showSheet(nausea)

    SymptomType.entries.forEach { tile(it).performScrollTo().assertIsFullyVisible() }
    node(C.Tag.overview_check_in_sheet_note).performScrollTo().assertIsFullyVisible()
    node(C.Tag.overview_check_in_sheet_save).performScrollTo().assertIsFullyVisible()
  }
}
