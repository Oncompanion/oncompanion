// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.lakeColors

/**
 * A link to another screen on a card or a sheet, with a chevron: "Planning ›" on the Overview's
 * cards, "Full journal ›" on the caregiver's, "See prescription ›" and "Manage ›" in the "Your
 * circle" sheet. On the Overview it keeps a 20 dp row: Compose extends the touch target of a
 * smaller clickable to 48 dp. The sheet's links are full rows (design: M3, "Care circle · Note and
 * prescription"), so the sheet makes them taller with [modifier].
 */
@Composable
internal fun CardLink(label: String, onClick: () -> Unit, modifier: Modifier) {
  Row(
      modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClick = onClick),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
  ) {
    // The chevron keeps its room: words too long for the line wrap instead of pushing it out
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.lakeColors.primaryText,
        modifier = Modifier.weight(1f, fill = false),
    )
    Icon(
        painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.lakeColors.primaryText,
        modifier = Modifier.size(CHEVRON_SIZE),
    )
  }
}

/** The chevron after a link ("Planning ›", "N more ›"). */
internal val CHEVRON_SIZE = 18.dp

/**
 * The touch height of a link, or of a banner's or snackbar's text action: Android's 48 dp minimum,
 * even where it looks smaller. The "Your circle" sheet's links are full rows of it (44 dp).
 */
internal val LINK_HEIGHT = 48.dp
