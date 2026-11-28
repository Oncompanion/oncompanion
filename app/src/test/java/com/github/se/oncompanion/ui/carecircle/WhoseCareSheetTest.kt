// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tests of [WhoseCareSheetContent]. */
@RunWith(AndroidJUnit4::class)
class WhoseCareSheetTest {

  @get:Rule val compose = createComposeRule()

  private fun patient(uid: String, firstName: String) =
      FollowedPatient(uid, firstName, setOf(CarePermission.PLANNING))

  private val alex = patient("p-alex", "Alex")
  private val anne = patient("p-anne", "Anne")

  /**
   * Alex, shown now, and Anne (design: M3, "Overview · Caregiver · Whose care? · Whose care?
   * sheet"); then Jean and Léa (design: M3, "Overview · Caregiver · Whose care? · Four people").
   */
  private val fourPeople =
      WhoseCareUiState(
          patients = listOf(alex, anne, patient("p-jean", "Jean"), patient("p-lea", "Léa")),
          isLoading = false,
          isConfirmed = true,
      )

  /** What the sheet was asked to do, in order. */
  private val calls = mutableListOf<String>()

  private fun show(state: WhoseCareUiState = fourPeople) {
    compose.setContent {
      WhoseCareSheetContent(
          uiState = state,
          currentUid = alex.uid,
          onChoose = { calls += "choose ${it.firstName}" },
          onDismiss = { calls += "dismiss" },
          onFollowSomeoneNew = { calls += "follow" },
          onRetry = { calls += "retry" },
      )
    }
  }

  @Test
  fun eachState_showsOnlyItsContent_andFollowSomeoneNewIsAlwaysThere() {
    var state by mutableStateOf(fourPeople)
    compose.setContent { WhoseCareSheetContent(state, alex.uid, {}, {}, {}, {}) }
    val content =
        listOf(
            C.Tag.whose_care_loading,
            C.Tag.whose_care_error,
            C.Tag.whose_care_unconfirmed,
            C.Tag.whose_care_empty,
            C.Tag.whoseCarePatient(alex.uid),
        )
    val nobody = fourPeople.copy(patients = emptyList())
    val cases =
        listOf(
            WhoseCareUiState() to C.Tag.whose_care_loading,
            // An error wins over a list left from before
            fourPeople.copy(hasError = true) to C.Tag.whose_care_error,
            // Nobody yet, but the circles may still be on their way
            nobody.copy(isConfirmed = false) to C.Tag.whose_care_unconfirmed,
            nobody to C.Tag.whose_care_empty,
            fourPeople to C.Tag.whoseCarePatient(alex.uid),
        )
    for ((shown, expected) in cases) {
      state = shown
      compose.waitForIdle()
      content.forEach { tag ->
        val node = compose.onNodeWithTag(tag)
        if (tag == expected) node.assertExists() else node.assertDoesNotExist()
      }
      compose.onNodeWithText("Whose care?").assertIsDisplayed()
      compose.onNodeWithTag(C.Tag.whose_care_follow_new).assertExists()
    }
  }

  @Test
  fun anotherPerson_isChosen_theOneShownNow_onlyClosesTheSheet() {
    show()

    compose.onNodeWithTag(C.Tag.whoseCarePatient(anne.uid)).performClick()
    compose.onNodeWithTag(C.Tag.whoseCarePatient(alex.uid)).performClick()

    assertEquals(listOf("choose Anne", "dismiss"), calls)
  }

  @Test
  fun theOneShownNow_isSelected_theOthersAreNot() {
    show()

    compose.onNodeWithTag(C.Tag.whoseCarePatient(alex.uid)).assertIsSelected()
    listOf("p-anne", "p-jean", "p-lea").forEach {
      compose.onNodeWithTag(C.Tag.whoseCarePatient(it)).assertIsNotSelected()
    }
  }

  @Test
  fun followSomeoneNew_andRetry_doWhatTheySay() {
    var state by mutableStateOf(fourPeople.copy(hasError = true))
    compose.setContent {
      WhoseCareSheetContent(state, alex.uid, {}, {}, { calls += "follow" }, { calls += "retry" })
    }

    compose.onNodeWithTag(C.Tag.whose_care_action).performClick()
    compose.onNodeWithTag(C.Tag.whose_care_follow_new).performClick()
    // Not confirmed yet: Retry reads the circles again
    state = fourPeople.copy(patients = emptyList(), isConfirmed = false)
    compose.onNodeWithTag(C.Tag.whose_care_action).performClick()

    assertEquals(listOf("retry", "follow", "retry"), calls)
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(qualifiers = "w360dp-h640dp", fontScale = 2f)
  fun atLargeFont_everyRowCanBeReached_whole() {
    show()
    assertEveryRowFullyVisible()
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun inLandscape_everyRowCanBeReached_whole() {
    show()
    assertEveryRowFullyVisible()
  }

  /**
   * Each person's row and "Follow someone new" can be scrolled to and are shown whole, and no text
   * in them is cut: a row too short for its words would still be "fully visible" itself.
   */
  private fun assertEveryRowFullyVisible() {
    (fourPeople.patients.map { C.Tag.whoseCarePatient(it.uid) } + C.Tag.whose_care_follow_new)
        .forEach { compose.onNodeWithTag(it).performScrollTo().assertIsFullyVisible() }
    listOf("Léa", "Follow someone new", "Enter the invite code they shared").forEach { text ->
      val layouts = mutableListOf<TextLayoutResult>()
      compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(
          SemanticsActions.GetTextLayoutResult
      ) {
        it(layouts)
      }
      // Every character on a line that shows, and every line inside the text's own height
      val layout = layouts.single()
      val shown = layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)
      assertFalse("\"$text\" is cut", layout.didOverflowHeight || shown < text.length)
    }
  }
}
