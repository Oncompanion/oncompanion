// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.reminder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentReminder
import com.github.se.oncompanion.model.appointment.AppointmentRepository
import com.github.se.oncompanion.model.appointment.AppointmentRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Device-wide Android access, independent of an appointment's saved reminder choice. */
enum class ReminderAccess {
  OFFER,
  APP_BLOCKED,
  CHANNEL_BLOCKED,
  EXACT_BLOCKED,
  READY,
}

/** The quiet Planning entry point; granting access never claims a reminder has been scheduled. */
data class ReminderPermissionUiState(
    val eligible: Boolean = false,
    val access: ReminderAccess? = null,
)

/** Observes only the signed-in patient's future appointments, regardless of the selected day. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReminderPermissionViewModel(
    private val appointments: AppointmentRepository = AppointmentRepositoryProvider.repository,
    private val auth: AuthRepository = AuthRepositoryProvider.repository,
    private val profiles: UserProfileRepository = UserProfileRepositoryProvider.repository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
  private val state = MutableStateFlow(ReminderPermissionUiState())
  /** Eligibility and the latest Android access checked by the view on resume. */
  val uiState = state.asStateFlow()
  private var owner: String? = null
  private var entries = emptyList<Appointment>()
  private var observation: Job? = null
  private var expiry: Job? = null

  init {
    observe()
  }

  /** Rechecks time/access after Android dialogs or settings, retrying failed observations. */
  fun refresh(access: ReminderAccess) {
    state.update { it.copy(access = access) }
    publish()
    if (observation?.isActive != true) observe()
  }

  private fun observe() {
    observation = viewModelScope.launch {
      auth
          .observeCurrentUser()
          .flatMapLatest { user ->
            owner = user?.uid
            if (user == null) flowOf(emptyList())
            else
                profiles
                    .observeProfile(user.uid)
                    .flatMapLatest { profile ->
                      if (profile?.role == Role.PATIENT) appointments.observeAppointments(user.uid)
                      else flowOf(emptyList())
                    }
                    .onStart { emit(emptyList()) }
          }
          .catch { emit(emptyList()) }
          .collect {
            entries = it
            publish()
          }
    }
  }

  private fun publish() {
    expiry?.cancel()
    // Hide the old account immediately, even before its auth callback has been delivered.
    val current = runCatching { auth.currentUser?.uid }.getOrNull()
    val last =
        entries.filter { it.reminder != AppointmentReminder.NONE }.maxOfOrNull { it.scheduledAt }
    val eligible = current != null && current == owner && last != null && last > clock.instant()
    state.update { it.copy(eligible = eligible) }
    if (eligible)
        expiry = viewModelScope.launch {
          delay(Duration.between(clock.instant(), requireNotNull(last)).toMillis().coerceAtLeast(1))
          publish()
        }
  }
}
