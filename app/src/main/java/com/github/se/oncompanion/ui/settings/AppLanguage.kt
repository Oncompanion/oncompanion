// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** The language overrides offered by Settings. An empty override lets Android follow the phone. */
internal enum class AppLanguage(private val tag: String) {
  FOLLOW_DEVICE(""),
  ENGLISH("en"),
  FRENCH("fr");

  internal fun locales(): LocaleListCompat = LocaleListCompat.forLanguageTags(tag)

  internal fun apply() {
    AppCompatDelegate.setApplicationLocales(locales())
  }

  companion object {
    internal fun current(): AppLanguage = fromLocales(AppCompatDelegate.getApplicationLocales())

    internal fun fromLocales(locales: LocaleListCompat): AppLanguage =
        entries.firstOrNull { it.tag == locales[0]?.language } ?: FOLLOW_DEVICE
  }
}
