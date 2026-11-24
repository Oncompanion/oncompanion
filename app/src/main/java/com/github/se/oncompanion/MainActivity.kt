// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.domain.auth.SignOutProvider
import com.github.se.oncompanion.domain.reminder.AppointmentRemindersProvider
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.hospital.HospitalRepositoryProvider
import com.github.se.oncompanion.model.medication.SeenPrescriptionsPreferences
import com.github.se.oncompanion.model.medication.SeenPrescriptionsProvider
import com.github.se.oncompanion.model.reminder.ReminderSchedulerProvider
import com.github.se.oncompanion.model.session.PreferencesCleanupJournal
import com.github.se.oncompanion.model.settings.SettingsRepositoryProvider
import com.github.se.oncompanion.model.speech.SpeechRecognizerProvider
import com.github.se.oncompanion.ui.navigation.AppNavHost
import com.github.se.oncompanion.ui.navigation.StartViewModel
import com.github.se.oncompanion.ui.reminder.AppointmentRemindersViewModel
import com.github.se.oncompanion.ui.reminder.appointmentReminder
import com.github.se.oncompanion.ui.theme.AppAppearanceRoot
import com.github.se.oncompanion.ui.theme.AppAppearanceViewModel

/** Installs session recovery before authenticated navigation or Firestore operations start. */
class MainActivity : ComponentActivity() {

  private val startViewModel: StartViewModel by viewModels()
  private val appearanceViewModel: AppAppearanceViewModel by viewModels()

  private val reminders: AppointmentRemindersViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    // Android's splash screen stays until we know where to start (sign-in, onboarding or Overview)
    val splashScreen = installSplashScreen()
    val journal = PreferencesCleanupJournal(getSharedPreferences("session_cleanup", MODE_PRIVATE))
    // Capture legacy queue owners before any ViewModel can start Firestore operations.
    SignOutProvider.signOut.initialize(journal)
    // The phone's speech recognizer keeps the app's context, so ViewModels never handle one
    SpeechRecognizerProvider.install(applicationContext)
    // Which accepted prescriptions a caregiver closed is kept on the phone (US-43)
    SeenPrescriptionsProvider.store =
        SeenPrescriptionsPreferences(getSharedPreferences("seen_prescriptions", MODE_PRIVATE))
    // Never restore an authenticated navigation stack after interrupted or completed sign-out.
    val restore =
        !journal.pending &&
            runCatching { AuthRepositoryProvider.repository.currentUser != null }
                .getOrDefault(false)
    ReminderSchedulerProvider.install(applicationContext)
    AppointmentRemindersProvider.install(applicationContext)
    SettingsRepositoryProvider.install(applicationContext)
    super.onCreate(if (restore) savedInstanceState else null)
    if (savedInstanceState == null) acceptReminder(intent)
    // Install the shared search adapter before fields capture it; blank configuration stays manual.
    HospitalRepositoryProvider.configureAtStartup(getString(R.string.hospital_proxy_endpoint))
    splashScreen.setKeepOnScreenCondition {
      startViewModel.startRoute.value == null ||
          appearanceViewModel.uiState.value.appearance == null
    }
    // Start edge-to-edge layout; the shared appearance root sets icon contrast and keeps the
    // default navigation-bar scrims. Screens use Scaffold to keep controls clear of the bars.
    enableEdgeToEdge()
    setContent {
      AppAppearanceRoot(this, appearanceViewModel) {
        val startRoute by startViewModel.startRoute.collectAsStateWithLifecycle()
        val reminderState by reminders.uiState.collectAsStateWithLifecycle()
        startRoute?.let {
          AppNavHost(
              startRoute = it,
              reminderAppointment = reminderState.appointmentId,
              onReminderHandled = reminders::consume,
              onReminderAccessChanged = reminders::refresh,
          )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    reminders.refresh()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    acceptReminder(intent)
  }

  private fun acceptReminder(intent: Intent) {
    intent.appointmentReminder()?.let { (owner, item) -> reminders.open(owner, item) }
  }
}
