// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps the selected app-language override in sync with the platform. */
internal class LanguageViewModel(
    private val localeAdapter: AppLocaleAdapter = AppCompatLocaleAdapter,
) : ViewModel() {
  private val selected = MutableStateFlow(localeAdapter.current())
  val uiState = selected.asStateFlow()

  /** Reloads the override when a settings screen resumes. */
  fun refresh() {
    selected.value = localeAdapter.current()
  }

  /** Applies the chosen override and updates the selection even if resources stay the same. */
  fun select(language: AppLanguage) {
    localeAdapter.apply(language)
    selected.value = language
  }
}
