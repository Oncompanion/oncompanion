// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Covers the locale values shared by the picker and Android. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 34])
class AppLanguageTest {
  @Test
  fun followDeviceClearsTheAppOverride() {
    assertTrue(AppLanguage.FOLLOW_DEVICE.locales().isEmpty)
    assertEquals(
        AppLanguage.FOLLOW_DEVICE,
        AppLanguage.fromLocales(LocaleListCompat.getEmptyLocaleList()),
    )
  }

  @Test
  fun explicitChoicesRoundTripThroughAndroidLocaleTags() {
    assertEquals("en", AppLanguage.ENGLISH.locales().toLanguageTags())
    assertEquals("fr", AppLanguage.FRENCH.locales().toLanguageTags())
    assertEquals(
        AppLanguage.ENGLISH,
        AppLanguage.fromLocales(LocaleListCompat.forLanguageTags("en-GB")),
    )
    assertEquals(
        AppLanguage.FRENCH,
        AppLanguage.fromLocales(LocaleListCompat.forLanguageTags("fr-CH")),
    )
    assertEquals(
        AppLanguage.FOLLOW_DEVICE,
        AppLanguage.fromLocales(LocaleListCompat.forLanguageTags("de")),
    )
  }
}
