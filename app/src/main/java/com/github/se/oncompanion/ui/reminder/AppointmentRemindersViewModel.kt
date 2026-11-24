// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.reminder

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.auth.*
import com.github.se.oncompanion.domain.reminder.*
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.session.SessionAccess
import com.github.se.oncompanion.model.user.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Scheduling status and an account-checked appointment navigation request, never display content.
 */
data class AppointmentRemindersUiState(
    val sync: AppointmentReminderSync = AppointmentReminderSync.IDLE,
    val appointmentId: String? = null,
)

/**
 * Owns one collector across rotation; no permission dialogs or dependency on a screen's lifetime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppointmentRemindersViewModel
@JvmOverloads
constructor(
    private val saved: SavedStateHandle,
    private val coordinator: ReconcileAppointmentReminders =
        AppointmentRemindersProvider.coordinator,
    private val auth: AuthRepository = AuthRepositoryProvider.repository,
    private val profiles: UserProfileRepository = UserProfileRepositoryProvider.repository,
    private val signOut: SignOut = SignOutProvider.signOut,
) : ViewModel(), SessionMemory {
  private val mutable = MutableStateFlow(AppointmentRemindersUiState())
  /** Current scheduling outcome and a one-shot navigation target. */
  val uiState = mutable.asStateFlow()
  private val refreshes = MutableStateFlow(0L)

  init {
    signOut.register(this)
    viewModelScope.launch {
      combine(refreshes, signOut.state) { _, phase -> phase }
          .collectLatest { phase ->
            if (phase == SignOutState.IDLE || phase == SignOutState.COMPLETE) {
              coordinator.observe(repairUndelivered = true).collect { result ->
                mutable.update { it.copy(sync = result) }
              }
            }
          }
    }
    viewModelScope.launch {
      combine(
              saved.getStateFlow<List<String>?>(PENDING, null),
              auth.observeCurrentUser(),
              refreshes,
          ) { request, user, _ ->
            request to user
          }
          .collectLatest { (request, user) ->
            mutable.update { it.copy(appointmentId = null) }
            if (request == null || user == null) return@collectLatest
            if (user.uid != request[0]) {
              handled()
              return@collectLatest
            }
            try {
              profiles.observeProfile(user.uid).collect { profile ->
                val target = SessionAccess.immediate {
                  request[1].takeIf {
                    auth.currentUser?.uid == request[0] && profile?.role == Role.PATIENT
                  }
                }
                mutable.update { it.copy(appointmentId = target) }
              }
            } catch (error: CancellationException) {
              throw error
            } catch (_: Exception) {
              mutable.update { it.copy(appointmentId = null) }
            }
          }
    }
  }

  /** Rechecks cached appointments after settings or a transient read failure, without prompting. */
  fun refresh() {
    refreshes.value++
  }

  /**
   * Keeps only identifiers across sign-in and rotation; a different account discards the request.
   */
  fun open(owner: String, appointment: String) {
    if (owner.isBlank() || appointment.isBlank()) return
    saved[PENDING] = listOf(owner, appointment)
  }

  /** Consumes before navigating, so recreation cannot open the same request again. */
  fun handled() {
    saved[PENDING] = null
    mutable.update { it.copy(appointmentId = null) }
  }

  /** Checks the live account once more immediately before navigation. */
  fun consume(): Boolean =
      try {
        SessionAccess.immediate {
          val request = saved.get<List<String>>(PENDING)
          val allowed =
              request != null &&
                  auth.currentUser?.uid == request[0] &&
                  mutable.value.appointmentId == request[1]
          handled()
          allowed
        }
      } catch (_: IllegalStateException) {
        handled()
        false
      }

  override fun clear() {
    handled()
  }

  override fun onCleared() {
    signOut.unregister(this)
  }

  private companion object {
    const val PENDING = "appointment_reminder_request"
  }
}
