// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.reminder

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentType
import com.github.se.oncompanion.model.session.SessionAccess
import com.github.se.oncompanion.utils.FailedCommitContext
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Checks persistent coordinator receipts and notification wording through the production adapters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppointmentReminderStorageTest {
  private val context: Application = ApplicationProvider.getApplicationContext()
  private val due = Instant.parse("2030-01-01T13:00:00Z")

  @Before fun setUp() = SessionAccess.reopen()

  @After
  fun tearDown() {
    SessionAccess.reopen()
    AppointmentReminderPlansProvider.reset()
    com.github.se.oncompanion.domain.reminder.AppointmentRemindersProvider.reset()
  }

  @Test
  fun receiptsAreAccountScoped_andCorruptionOrClosedSessionFailsRatherThanReplays() {
    val first = AppointmentReminderPlansPreferences(context)
    val plan = AppointmentReminderPlan(due, "timing", "content")
    first.write("patient", mapOf("id/with special chars" to plan))
    val second = AppointmentReminderPlansPreferences(context)
    assertEquals(mapOf("id/with special chars" to plan), second.read("patient"))
    assertTrue(second.read("other").isEmpty())
    SessionAccess.block()
    assertThrows(IllegalStateException::class.java) { second.read("patient") }
    assertThrows(IllegalStateException::class.java) { second.write("patient", emptyMap()) }
    SessionAccess.reopen()
    context
        .getSharedPreferences("appointment_reminder_plans", 0)
        .edit()
        .putString("patient", "corrupt")
        .commit()
    assertThrows(org.json.JSONException::class.java) { second.read("patient") }
    first.write("patient", emptyMap())
    assertTrue(second.read("patient").isEmpty())
  }

  @Test
  fun failedCommitDoesNotAcceptThePlanOrApplyItAsynchronously() {
    val failed = FailedCommitContext(context)
    val store = AppointmentReminderPlansPreferences(failed)
    assertThrows(IllegalStateException::class.java) {
      store.write("patient", mapOf("visit" to AppointmentReminderPlan(due, "timing", "content")))
    }
    assertFalse(failed.applied)
    assertTrue(AppointmentReminderPlansPreferences(context).read("patient").isEmpty())
  }

  @Test
  fun uninstalledCoordinatorFailsExplicitly_thenInstallationIsStable() {
    val provider = com.github.se.oncompanion.domain.reminder.AppointmentRemindersProvider
    provider.reset()
    ReminderSchedulerProvider.install(context)
    assertThrows(IllegalStateException::class.java) { provider.coordinator }
    provider.install(context)
    val installed = provider.coordinator
    provider.install(context)
    assertSame(installed, provider.coordinator)
  }

  @Test
  fun providerInstallationDoesNothingAndPreservesTheInjectedStore() {
    AppointmentReminderPlansProvider.reset()
    assertThrows(IllegalStateException::class.java) { AppointmentReminderPlansProvider.repository }
    AppointmentReminderPlansProvider.install(context)
    val installed = AppointmentReminderPlansProvider.repository
    AppointmentReminderPlansProvider.install(context)
    assertSame(installed, AppointmentReminderPlansProvider.repository)
    val fake = FakeAppointmentReminderPlans()
    AppointmentReminderPlansProvider.repository = fake
    AppointmentReminderPlansProvider.install(context)
    assertSame(fake, AppointmentReminderPlansProvider.repository)
    assertTrue(installed.read("patient").isEmpty())
  }

  @Test
  fun textUsesDeviceLocaleAndDisplayZone_withOptionalLocation_butNoNotesOrAttribution() {
    val appointment =
        Appointment(
            "visit",
            "Consultation",
            due,
            AppointmentType.CONSULTATION,
            notes = "Private notes",
            addedBy = "caregiver",
            addedByName = "Sam",
        )
    val cases =
        listOf(
            Triple(Locale.US, "Europe/Zurich", "2:00 PM"),
            Triple(Locale.FRANCE, "Europe/Zurich", "14:00"),
            Triple(Locale.US, "America/New_York", "8:00 AM"),
        )
    for ((locale, zone, expected) in cases) {
      context.resources.updateConfiguration(
          Configuration(context.resources.configuration).apply { setLocale(locale) },
          context.resources.displayMetrics,
      )
      val text = AndroidAppointmentReminderText(context)
      for (location in listOf(null, "", "  ", "Fictional clinic")) {
        val result = text.format(appointment.copy(location = location), ZoneId.of(zone))
        assertEquals("Consultation", result.title)
        assertTrue(
            "${result.text} contains $expected",
            result.text.replace('\u202f', ' ').contains(expected),
        )
        assertFalse(result.text.contains("Private notes"))
        assertFalse(result.text.contains("Sam"))
        if (location == "Fictional clinic") assertTrue(result.text.endsWith(" · Fictional clinic"))
        else assertFalse(result.text.contains(" · "))
      }
    }
  }
}
