// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.full

/**
 * The top-level destinations reachable from the bottom navigation bar, in display order.
 *
 * As recommended by Material 3, [icon] is outlined and [selectedIcon] is its filled version, so the
 * selected tab also stands out by its icon.
 */
enum class Tab(
    val route: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val selectedIcon: Int,
    val testTag: String,
) {
  OVERVIEW(
      Route.OVERVIEW,
      R.string.tab_overview,
      R.drawable.ic_tab_overview,
      R.drawable.ic_tab_overview_selected,
      C.Tag.bottom_navigation_tab_overview,
  ),
  PLANNING(
      Route.PLANNING,
      R.string.planning_title,
      R.drawable.ic_tab_planning,
      R.drawable.ic_tab_planning_selected,
      C.Tag.bottom_navigation_tab_planning,
  ),
  /** The patient's only: a caregiver opens the prescriptions shared with them from the Overview. */
  PRESCRIPTIONS(
      Route.PRESCRIPTIONS,
      R.string.prescriptions_title,
      R.drawable.ic_tab_prescriptions,
      R.drawable.ic_tab_prescriptions_selected,
      C.Tag.bottom_navigation_tab_prescriptions,
  ),
  EVENTS(
      Route.EVENTS,
      R.string.events_title,
      R.drawable.ic_tab_events,
      R.drawable.ic_tab_events_selected,
      C.Tag.bottom_navigation_tab_events,
  ),
}

/**
 * What the bar shows besides the selected tab, the same on every tab.
 *
 * @property caregiverMode a caregiver follows a patient: no Prescriptions tab (Figma M3)
 * @property prescriptionsToReview how many prescriptions a caregiver added wait for the patient
 *   (US-43), shown as a badge on the Prescriptions tab
 */
data class BottomBarUiState(
    val caregiverMode: Boolean = false,
    val prescriptionsToReview: Int = 0,
) {
  /** The tabs shown, in display order. */
  val tabs: List<Tab>
    get() = if (caregiverMode) Tab.entries - Tab.PRESCRIPTIONS else Tab.entries

  /** The count on [tab]'s badge, or `null` for no badge. */
  fun badge(tab: Tab): Int? = prescriptionsToReview.takeIf {
    tab == Tab.PRESCRIPTIONS && !caregiverMode && it > 0
  }
}

/**
 * The bottom navigation bar shown on the top-level screens (Figma M3: "Navigation bar (4 tabs)"):
 * 80 dp on Surface Container, equal tabs, the selected one with a dawn indicator and an ink label.
 *
 * Each screen passes its own [selectedTab], so the highlighted tab always matches the screen.
 */
@Composable
fun BottomNavigationBar(
    selectedTab: Tab,
    onTabSelected: (Tab) -> Unit,
    modifier: Modifier = Modifier,
    state: BottomBarUiState = BottomBarUiState(),
) {
  Surface(
      color = MaterialTheme.colorScheme.surfaceContainer,
      modifier = modifier.testTag(C.Tag.bottom_navigation_bar),
  ) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().windowInsetsPadding(NavigationBarDefaults.windowInsets)
    ) {
      val labelStyle = labelStyleFitting(state.tabs, maxWidth / state.tabs.size)
      Row(Modifier.fillMaxWidth().selectableGroup()) {
        state.tabs.forEach { tab ->
          TabItem(
              tab = tab,
              selected = tab == selectedTab,
              onClick = { onTabSelected(tab) },
              badge = state.badge(tab),
              labelStyle = labelStyle,
              modifier = Modifier.weight(1f).testTag(tab.testTag),
          )
        }
      }
    }
  }
}

/**
 * labelMedium at the user's font size, made smaller when the widest of [tabs]' labels wouldn't fit
 * in [tabWidth]: four tabs leave a phone about 90 dp each, and a label cut in the middle of a word
 * can't be read. Every label keeps the same size, so none looks more important.
 */
@Composable
private fun labelStyleFitting(tabs: List<Tab>, tabWidth: Dp): TextStyle {
  val style = MaterialTheme.typography.labelMedium
  val labels = tabs.map { stringResource(it.label) }
  val measurer = rememberTextMeasurer()
  val density = LocalDensity.current
  // Measured again only when the labels, their style, the font size or the width change
  return remember(labels, style, density, tabWidth) {
    val widest = labels.maxOf { measurer.measure(it, style).size.width }
    val room = with(density) { (tabWidth - Spacing.xs * 2).toPx() }
    if (widest <= room) style
    else {
      // The smallest size is in dp, so a larger font setting never makes a label smaller
      val smallest = with(density) { MIN_LABEL_SIZE.toSp() }.value
      val size = (style.fontSize.value * room / widest).coerceAtLeast(smallest)
      style.copy(fontSize = size.sp, lineHeight = style.lineHeight * (size / style.fontSize.value))
    }
  }
}

/**
 * One tab of the bar: its icon in the 64 x 32 indicator, then its label. Material's own item has a
 * 56 dp indicator, so the Figma's is drawn here. A [badge] shows on the icon, also read by screen
 * readers.
 */
@Composable
private fun TabItem(
    tab: Tab,
    selected: Boolean,
    onClick: () -> Unit,
    badge: Int?,
    labelStyle: TextStyle,
    modifier: Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val interactions = remember { MutableInteractionSource() }
  Column(
      modifier
          .selectable(selected, interactions, indication = null, role = Role.Tab, onClick = onClick)
          .padding(top = Spacing.md, bottom = Spacing.lg),
      verticalArrangement = Arrangement.spacedBy(Spacing.xs),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(Modifier.size(INDICATOR_SIZE), contentAlignment = Alignment.Center) {
      // The ripple stays inside the indicator, as on Material's bar; the badge may rise above it
      Box(
          Modifier.matchParentSize()
              .clip(MaterialTheme.shapes.full)
              .background(if (selected) colors.secondaryContainer else Color.Transparent)
              .indication(interactions, ripple())
      )
      // The label below already names the tab, so the icon is decorative
      Icon(
          painter = painterResource(if (selected) tab.selectedIcon else tab.icon),
          contentDescription = null,
          tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
      )
      if (badge != null) {
        CountBadge(badge, Modifier.align(Alignment.TopStart).offset(BADGE_OFFSET.x, BADGE_OFFSET.y))
      }
    }
    Text(
        stringResource(tab.label),
        style = labelStyle,
        color = if (selected) colors.onSurface else colors.onSurfaceVariant,
        maxLines = 1,
    )
  }
}

/**
 * The Figma's teal "Count badge", 20 dp across the indicator's top edge: read as "2 prescriptions
 * to confirm" rather than "2".
 */
@Composable
private fun CountBadge(count: Int, modifier: Modifier) {
  val description = pluralStringResource(R.plurals.tab_prescriptions_to_confirm, count, count)
  Badge(
      containerColor = MaterialTheme.colorScheme.primary,
      contentColor = MaterialTheme.colorScheme.onPrimary,
      modifier =
          modifier
              .defaultMinSize(BADGE_SIZE, BADGE_SIZE)
              .testTag(C.Tag.bottom_navigation_prescriptions_badge)
              .clearAndSetSemantics { contentDescription = description },
  ) {
    Text("$count")
  }
}

// The pill behind the selected tab's icon in the Figma
private val INDICATOR_SIZE = DpSize(64.dp, 32.dp)

// The Figma's "Tab badge": from the indicator's top left corner, half above its top edge
private val BADGE_OFFSET = DpOffset(38.dp, (-6).dp)
private val BADGE_SIZE = 20.dp

// labelMedium is 12 sp: labels only shrink below it when a tab is too narrow, never below this
private val MIN_LABEL_SIZE = 11.dp
