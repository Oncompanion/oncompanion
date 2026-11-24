// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.github.se.oncompanion.ui.theme.Spacing

/** Shared accessible single-choice row with optional supporting text. */
@Composable
internal fun SettingsChoiceRow(
    label: String,
    hint: String?,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    selectedTag: String? = null,
) {
  Row(
      modifier
          .fillMaxWidth()
          .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
          .padding(Spacing.lg),
      horizontalArrangement = Arrangement.spacedBy(Spacing.md),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.titleMedium)
      if (hint != null) {
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (selected) {
      Icon(
          Icons.Default.Check,
          contentDescription = null,
          modifier = if (selectedTag != null) Modifier.testTag(selectedTag) else Modifier,
      )
    }
  }
}
