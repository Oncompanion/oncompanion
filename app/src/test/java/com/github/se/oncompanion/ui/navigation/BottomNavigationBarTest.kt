// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.navigation

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.assertNoWordIsCut
import com.github.se.oncompanion.ui.assertSameColor
import com.github.se.oncompanion.ui.assertTextStyle
import com.github.se.oncompanion.ui.colorAt
import com.github.se.oncompanion.ui.textLayout
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tests of [BottomNavigationBar]. */
@RunWith(AndroidJUnit4::class)
class BottomNavigationBarTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun assertOnlySelected(selectedTab: Tab) {
    Tab.entries.forEach { tab ->
      val node = composeTestRule.onNodeWithTag(tab.testTag)
      if (tab == selectedTab) node.assertIsSelected() else node.assertIsNotSelected()
    }
  }

  @Test
  fun tabs_areInTheM3Order() {
    assertEquals(
        listOf(Tab.OVERVIEW, Tab.PLANNING, Tab.PRESCRIPTIONS, Tab.EVENTS),
        BottomBarUiState().tabs,
    )
  }

  @Test
  fun bar_showsEveryTabWithItsLabel() {
    composeTestRule.setContent {
      BottomNavigationBar(selectedTab = Tab.OVERVIEW, onTabSelected = {})
    }

    composeTestRule.onNodeWithTag(C.Tag.bottom_navigation_bar).assertIsDisplayed()
    Tab.entries.forEach { tab ->
      composeTestRule
          .onNode(hasTestTag(tab.testTag) and hasText(context.getString(tab.label)))
          .assertIsDisplayed()
    }
  }

  @Test
  fun bar_highlightsOnlyTheSelectedTab() {
    var selectedTab by mutableStateOf(Tab.OVERVIEW)
    composeTestRule.setContent {
      BottomNavigationBar(selectedTab = selectedTab, onTabSelected = {})
    }

    Tab.entries.forEach { tab ->
      composeTestRule.runOnIdle { selectedTab = tab }
      assertOnlySelected(tab)
    }
  }

  @Test
  fun everyTab_hasADifferentIconWhenSelected() {
    Tab.entries.forEach { tab ->
      assertNotEquals("${tab.name} uses the same icon when selected", tab.icon, tab.selectedIcon)
    }
  }

  @Test
  fun clickingEachTab_reportsIt() {
    val clicked = mutableListOf<Tab>()
    composeTestRule.setContent {
      BottomNavigationBar(selectedTab = Tab.OVERVIEW, onTabSelected = { clicked += it })
    }

    Tab.entries.forEach { tab -> composeTestRule.onNodeWithTag(tab.testTag).performClick() }

    composeTestRule.runOnIdle { assertEquals(Tab.entries.toList(), clicked) }
  }

  @Test
  fun caregiverMode_hasNoPrescriptionsTab() {
    composeTestRule.setContent {
      BottomNavigationBar(
          selectedTab = Tab.OVERVIEW,
          onTabSelected = {},
          state = BottomBarUiState(caregiverMode = true, prescriptionsToReview = 2),
      )
    }

    listOf(Tab.OVERVIEW, Tab.PLANNING, Tab.EVENTS).forEach {
      composeTestRule.onNodeWithTag(it.testTag).assertIsDisplayed()
    }
    composeTestRule.onNodeWithTag(Tab.PRESCRIPTIONS.testTag).assertDoesNotExist()
    composeTestRule
        .onNodeWithTag(C.Tag.bottom_navigation_prescriptions_badge, useUnmergedTree = true)
        .assertDoesNotExist()
  }

  @Test
  fun badge_saysHowManyPrescriptionsWait_toScreenReadersToo() {
    var count by mutableStateOf(0)
    composeTestRule.setContent {
      BottomNavigationBar(
          selectedTab = Tab.OVERVIEW,
          onTabSelected = {},
          state = BottomBarUiState(prescriptionsToReview = count),
      )
    }
    val badge =
        composeTestRule.onNodeWithTag(
            C.Tag.bottom_navigation_prescriptions_badge,
            useUnmergedTree = true,
        )

    // Nothing waits: no badge
    badge.assertDoesNotExist()
    val cases = listOf(1 to "1 prescription to confirm", 3 to "3 prescriptions to confirm")
    for ((waiting, description) in cases) {
      composeTestRule.runOnIdle { count = waiting }
      badge.assertIsDisplayed().assertContentDescriptionEquals(description)
      composeTestRule
          .onNode(
              hasTestTag(Tab.PRESCRIPTIONS.testTag) and hasText("$waiting"),
              useUnmergedTree = true,
          )
          .assertDoesNotExist()
    }
  }

  // ----- Look (design: M3, "Navigation bar (4 tabs, Speak slot)") -----

  private lateinit var colors: ColorScheme
  private lateinit var labelMedium: TextStyle

  private fun setThemedContent(
      state: BottomBarUiState = BottomBarUiState(),
      fontScale: Float = 1f,
  ) {
    composeTestRule.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        OncompanionTheme {
          colors = MaterialTheme.colorScheme
          labelMedium = MaterialTheme.typography.labelMedium
          BottomNavigationBar(selectedTab = Tab.OVERVIEW, onTabSelected = {}, state = state)
        }
      }
    }
  }

  // Real text drawing: Robolectric otherwise gives every label the same made-up height
  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun bar_is80dpTall() {
    setThemedContent()

    composeTestRule.onNodeWithTag(C.Tag.bottom_navigation_bar).assertHeightIsEqualTo(80.dp)
  }

  @Test
  fun labels_areLabelMedium_selectedInInk_theOthersInSlate() {
    setThemedContent()

    for (tab in Tab.entries) {
      val color = if (tab == Tab.OVERVIEW) colors.onSurface else colors.onSurfaceVariant
      composeTestRule
          .onNodeWithText(context.getString(tab.label), useUnmergedTree = true)
          .assertTextStyle(labelMedium, color)
    }
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun selectedTab_hasA64dpWideDawnIndicator() {
    setThemedContent()

    // The first of four 90dp tabs: its indicator is centered, from x = 13 to 77
    val bar = composeTestRule.onNodeWithTag(C.Tag.bottom_navigation_bar)
    val cases =
        listOf(
            Triple("left of the indicator", 11.dp, colors.surfaceContainer),
            Triple("inside its left end", 15.dp, colors.secondaryContainer),
            Triple("unselected tab", 135.dp, colors.surfaceContainer),
        )
    for ((name, x, expected) in cases) assertSameColor(name, expected, bar.colorAt(x, 28.dp))
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun badge_isTealAndRisesAboveTheIndicatorUncut() {
    setThemedContent(BottomBarUiState(prescriptionsToReview = 1))

    // The count badge overlaps the indicator's top edge, so it must not be clipped by it
    val bar = composeTestRule.onNodeWithTag(C.Tag.bottom_navigation_bar)
    val badge =
        composeTestRule
            .onNodeWithTag(C.Tag.bottom_navigation_prescriptions_badge, useUnmergedTree = true)
            .fetchSemanticsNode()
    val density = badge.layoutInfo.density
    val barTop = bar.fetchSemanticsNode().positionInRoot
    with(density) {
      val x = (badge.positionInRoot.x - barTop.x + badge.size.width / 2f).toDp()
      val top = (badge.positionInRoot.y - barTop.y).toDp()
      assertTrue("The badge starts at $top, inside the indicator", top < 12.dp)
      assertSameColor("top of the badge", colors.primary, bar.colorAt(x, top + 2.dp))
    }
  }

  /** The size each label is drawn at, failing if it isn't whole on its one line. */
  private fun labelSizes(): List<TextUnit> =
      Tab.entries.map { tab ->
        val label =
            composeTestRule.onNodeWithText(context.getString(tab.label), useUnmergedTree = true)
        label.assertNoWordIsCut()
        val layout = label.textLayout()
        assertEquals(
            "${tab.name} label is cut",
            layout.layoutInput.text.length,
            layout.getLineEnd(0),
        )
        layout.layoutInput.style.fontSize
      }

  // Real text measurement, so a label that doesn't fit is cut as on a phone
  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun largestFont_onAPhone_shrinksEveryLabelAlike_toKeepThemWhole() {
    setThemedContent(fontScale = 2f)

    val sizes = labelSizes()
    assertEquals("labels of different sizes: $sizes", 1, sizes.distinct().size)
    // They only shrink as much as needed: labelMedium's 12 sp fits a 90 dp tab, so never below it
    val drawnSp = sizes.first().value * 2f
    assertTrue("labels drawn at $drawnSp sp, smaller than the default", drawnSp >= 12f)
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(qualifiers = "w280dp-h560dp-xhdpi")
  fun largestFont_onANarrowPhone_keepsEveryLabelWhole_neverBelow11dp() {
    setThemedContent(fontScale = 2f)

    // 70 dp tabs: the labels shrink to the floor, and no further, to stay readable, yet stay whole
    for ((tab, size) in Tab.entries.zip(labelSizes())) {
      val drawnDp = size.value * 2f
      assertTrue("${tab.name} drawn at $drawnDp dp", drawnDp >= 11f - 0.01f)
    }
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun landscape_showsEveryTabWhole_atTheFullLargestFont() {
    setThemedContent(BottomBarUiState(prescriptionsToReview = 1), fontScale = 2f)

    // Wide tabs: the labels keep the user's font size
    assertEquals(List(Tab.entries.size) { labelMedium.fontSize }, labelSizes())
    Tab.entries.forEach { composeTestRule.onNodeWithTag(it.testTag).assertIsFullyVisible() }
    composeTestRule
        .onNodeWithTag(C.Tag.bottom_navigation_prescriptions_badge, useUnmergedTree = true)
        .assertIsDisplayed()
  }
}
