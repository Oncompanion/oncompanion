// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.LakeTopAppBar
import com.github.se.oncompanion.ui.theme.Spacing

/** Opens the device preferences; unfinished categories remain hidden. */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onAppearance: () -> Unit,
    modifier: Modifier = Modifier,
    onLanguage: () -> Unit = {},
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  SettingsContent(
      state,
      onBack,
      onAppearance,
      viewModel::retry,
      modifier,
      AppLanguage.current(),
      onLanguage,
  )
}

/** Displays only implemented categories and their persisted values. */
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onAppearance: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    language: AppLanguage = AppLanguage.FOLLOW_DEVICE,
    onLanguage: () -> Unit = {},
) {
  SettingsLayout(
      stringResource(R.string.settings_title),
      onBack,
      modifier.testTag(C.Tag.settings_screen),
  ) {
    SettingsFeedback(state, onRetry)
    state.settings?.let { settings ->
      Text(
          stringResource(R.string.settings_intro),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      SettingsLinkRow(
          stringResource(R.string.settings_appearance),
          appearanceLabel(settings.appearance),
          onAppearance,
          Modifier.testTag(C.Tag.settings_appearance),
      )
    }
    if (state.settings != null) {
      Text(
          stringResource(R.string.appearance_phone_scope),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    SettingsLinkRow(
        stringResource(R.string.settings_language),
        languageLabel(language),
        onLanguage,
        Modifier.testTag(C.Tag.settings_language),
    )
  }
}

@Composable
private fun SettingsLinkRow(title: String, value: String, onClick: () -> Unit, modifier: Modifier) {
  Surface(
      onClick = onClick,
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceContainerLowest,
      modifier = modifier.fillMaxWidth(),
  ) {
    Row(
        Modifier.padding(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
  }
}

/** Shares the lake header and scrollable body between settings destinations. */
@Composable
internal fun SettingsLayout(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
  Scaffold(
      modifier = modifier,
      topBar = {
        LakeTopAppBar(
            title,
            navigationIcon = {
              IconButton(onClick = onBack, modifier = Modifier.testTag(C.Tag.settings_back)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back))
              }
            },
        )
      },
  ) { padding ->
    Column(
        Modifier.fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        content = content,
    )
  }
}
