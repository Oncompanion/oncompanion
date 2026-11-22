// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.navigation.testing.TestNavHostController
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.domain.medication.DailyDoses
import com.github.se.oncompanion.domain.planning.ownPlanning
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.medication.FakeTakenDosesRepository
import com.github.se.oncompanion.model.planning.FakePlanningRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.navigation.NavigationActions
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class OverviewDosesSheetTest {

  // An activity rule, so the phone can be rotated (the activity recreated)
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private val ondansetron =
      TodayMedication(
          key = "k",
          medicationId = "ondansetron",
          name = "Ondansetron 8 mg",
          // Said in the card's words in the subtitle
          frequency = "1-1-1",
          dailyDoses = DailyDoses(3, null),
          dosesPerDay = 3,
          taken = 1,
      )

  /** What each callback of the sheet was called for, in order. */
  private val calls = mutableListOf<String>()

  /** Shows the sheet with [draft], replacing what was shown, so a test can show it again. */
  private fun showSheet(draft: Int) {
    composeTestRule.runOnUiThread {
      composeTestRule.activity.setContent {
        OverviewDosesSheet(
            DosesSheet(ondansetron, doses = 3, draft = draft),
            onLess = { calls += "less" },
            onMore = { calls += "more" },
            onDone = { calls += "done" },
            onClose = { calls += "close" },
        )
      }
    }
  }

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

  @Test
  fun theSheet_showsTheMedication_itsFrequencyToday_andTheCountSet() {
    showSheet(draft = 2)

    composeTestRule.onNodeWithText("Ondansetron 8 mg").assertIsDisplayed()
    composeTestRule.onNodeWithText("3 times a day · today").assertIsDisplayed()
    node(C.Tag.overview_doses_count)
        .assertTextEquals("2 of 3 taken")
        // TalkBack reads it again after each − or +
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    node(C.Tag.overview_doses_less)
        .assertContentDescriptionEquals("One dose fewer of Ondansetron 8 mg")
        .assertHeightIsAtLeast(48.dp)
    node(C.Tag.overview_doses_more)
        .assertContentDescriptionEquals("One more dose of Ondansetron 8 mg")
        .assertHeightIsAtLeast(48.dp)
  }

  @Test
  fun lessAndMore_eachCallBack_andStopAtTheirLimit() {
    // (draft) to (less enabled, more enabled)
    listOf(0 to (false to true), 1 to (true to true), 3 to (true to false)).forEach {
        (draft, enabled) ->
      calls.clear()
      showSheet(draft)
      val (less, more) = enabled

      listOf(C.Tag.overview_doses_less to less, C.Tag.overview_doses_more to more).forEach {
          (tag, isEnabled) ->
        val button = node(tag)
        if (isEnabled) button.assertIsEnabled() else button.assertIsNotEnabled()
        button.performClick()
      }
      composeTestRule.runOnIdle {
        assertEquals(
            "draft $draft",
            listOfNotNull("less".takeIf { less }, "more".takeIf { more }),
            calls,
        )
      }
    }
  }

  @Test
  fun done_saves_afterTheSheetSlidesAway() {
    showSheet(draft = 2)

    node(C.Tag.overview_doses_done).performClick()

    composeTestRule.runOnIdle { assertEquals(listOf("done"), calls) }
  }

  @Test
  fun back_orASwipeDown_closesWithoutSaving() {
    showSheet(draft = 2)
    Espresso.pressBack()
    composeTestRule.runOnIdle { assertEquals(listOf("close"), calls) }

    calls.clear()
    showSheet(draft = 2)
    node(C.Tag.overview_doses_sheet).performTouchInput { swipeDown() }
    composeTestRule.runOnIdle { assertEquals(listOf("close"), calls) }
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun inLandscape_doneIsReachable() {
    showSheet(draft = 1)

    node(C.Tag.overview_doses_done).performScrollTo().assertIsFullyVisible()
  }

  @Test
  @Config(qualifiers = "w360dp-h640dp", fontScale = 2f)
  fun at200PercentText_theButtonsAndDoneAreReachable() {
    showSheet(draft = 1)

    listOf(C.Tag.overview_doses_less, C.Tag.overview_doses_more, C.Tag.overview_doses_done)
        .forEach { node(it).performScrollTo().assertIsDisplayed() }
  }

  @Test
  fun phoneRotated_theSheetKeepsItsCount_andDoneSavesIt() {
    val now = LocalDateTime.of(2026, 10, 3, 9, 0)
    val doses = FakeTakenDosesRepository()
    val auth =
        FakeAuthRepository(onSignIn = { AuthUser(uid = "patient", displayName = "Alex") }).also {
          runBlocking { it.signInWithGoogle("token") }
        }
    val viewModel =
        OverviewViewModel(
            authRepository = auth,
            profileRepository = FakeUserProfileRepository(),
            observePlanning =
                ownPlanning(
                    FakePlanningRepository().apply {
                      items.value =
                          listOf(
                              PlanningItem(
                                  PlanningSource.Medication("prescription", "ondansetron"),
                                  PlanningTiming.DateOnly(now.toLocalDate()),
                                  "Ondansetron",
                                  frequency = "3 times a day",
                              )
                          )
                    }
                ),
            clock = Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
            zoneId = ZoneOffset.UTC,
            takenDosesRepository = doses,
        )
    val show = {
      composeTestRule.activity.setContent {
        OverviewScreen(
            navigationActions = NavigationActions(TestNavHostController(LocalContext.current)),
            viewModel = viewModel,
        )
      }
    }
    composeTestRule.runOnUiThread(show)
    node(C.Tag.overviewMedication(viewModel.uiState.value.todayMedications.single().key))
        .performClick()
    node(C.Tag.overview_doses_more).performClick()
    node(C.Tag.overview_doses_more).performClick()

    // Rotating recreates the activity; the ViewModel, and the sheet's count with it, survives it
    composeTestRule.activityRule.scenario.recreate()
    composeTestRule.runOnUiThread(show)
    node(C.Tag.overview_doses_count).assertTextEquals("2 of 3 taken")

    node(C.Tag.overview_doses_done).performClick()
    composeTestRule.waitForIdle()
    node(C.Tag.overview_doses_sheet).assertDoesNotExist()
    assertEquals(listOf(2), doses.writes.map { it.count })
  }
}
