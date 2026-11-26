// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.reminder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.se.oncompanion.model.session.SessionAccess
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The medication reminders kept on the phone, so they're cancelled and never scheduled twice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MedicationReminderPlansPreferencesTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val raw = context.getSharedPreferences("medication_reminder_plans", Context.MODE_PRIVATE)
  private val plan = MedicationReminderPlan(Instant.parse("2030-01-01T18:00:00Z"), "hash")

  @Before
  fun setUp() {
    SessionAccess.reopen()
    MedicationReminderPlansProvider.reset()
  }

  @After
  fun tearDown() {
    raw.edit().clear().commit()
    MedicationReminderPlansProvider.reset()
    SessionAccess.reopen()
  }

  @Test
  fun eachAccount_keepsItsOwnReminders_untilTheLastIsRemoved() {
    val store = MedicationReminderPlansPreferences(context)
    store.write("patient", mapOf("med@2030-01-01T18:00" to plan))
    // A new store, as after the app restarts, reads them back
    assertEquals(
        mapOf("med@2030-01-01T18:00" to plan),
        MedicationReminderPlansPreferences(context).read("patient"),
    )
    assertTrue(store.read("other").isEmpty())
    store.write("patient", emptyMap())
    assertFalse(raw.contains("patient"))
  }

  @Test
  fun unreadableReminders_areForgotten_soTheyAreScheduledAgain() {
    val store = MedicationReminderPlansPreferences(context)
    val cases = listOf("not json", """{"med":["yesterday","hash"]}""", """{"med":"hash"}""")
    for (saved in cases) {
      raw.edit().putString("patient", saved).putString("other", "kept").commit()
      assertTrue(saved, store.read("patient").isEmpty())
      assertFalse(saved, raw.contains("patient"))
      assertEquals(saved, "kept", raw.getString("other", null))
    }
  }

  @Test
  fun nothingIsReadOrWritten_afterSignOutClosedTheSession() {
    val store = MedicationReminderPlansPreferences(context)
    store.write("patient", mapOf("med" to plan))
    SessionAccess.block()
    assertThrows(IllegalStateException::class.java) { store.read("patient") }
    assertThrows(IllegalStateException::class.java) { store.write("patient", emptyMap()) }
    assertTrue(raw.contains("patient"))
  }

  @Test
  fun install_keepsTheStoreATestAlreadySet() {
    val fake = FakeMedicationReminderPlans()
    MedicationReminderPlansProvider.repository = fake
    MedicationReminderPlansProvider.install(context)
    assertSame(fake, MedicationReminderPlansProvider.repository)
    MedicationReminderPlansProvider.reset()
    assertTrue(runCatching { MedicationReminderPlansProvider.repository }.isFailure)
    MedicationReminderPlansProvider.install(context)
    assertTrue(MedicationReminderPlansProvider.repository is MedicationReminderPlansPreferences)
  }
}
