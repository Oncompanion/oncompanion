// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.navigation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepositoryProvider
import com.github.se.oncompanion.model.medication.PrescriptionConfirmation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn

/**
 * Counts the prescriptions a caregiver added that wait for the signed-in patient, for the badge on
 * the bottom bar's Prescriptions tab. One instance serves every tab, so they all show the same
 * count.
 *
 * It outlives sign-out, so it follows whoever is signed in rather than the first account. The count
 * comes from the device's cache when offline. It is only a reminder: while the prescriptions can't
 * be read, no badge shows, and it reads them again until it can.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BottomBarViewModel(
    authRepository: AuthRepository = AuthRepositoryProvider.repository,
    medicationRepository: MedicationRepository = MedicationRepositoryProvider.repository,
) : ViewModel() {

  /** The bar of a patient; [AppNavHost] sets caregiver mode from the chosen patient. */
  val uiState: StateFlow<BottomBarUiState> =
      authRepository
          .observeCurrentUser()
          .flatMapLatest { user ->
            if (user == null) flowOf(0)
            else
                medicationRepository
                    .observePrescriptions(user.uid)
                    .map { list ->
                      list.count { it.confirmation == PrescriptionConfirmation.PENDING }
                    }
                    // Retried here, so the next account signed in still gets its count at once
                    .noBadgeWhileFailing("Couldn't count the prescriptions to confirm")
          }
          .noBadgeWhileFailing("Couldn't follow the signed-in user")
          .map { BottomBarUiState(prescriptionsToReview = it) }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
              BottomBarUiState(),
          )

  /**
   * Hides the badge while this read fails, then reads again after [RETRY_DELAY_MS], so the badge
   * comes back on its own once the read works, as the list does when the user taps Retry.
   */
  private fun Flow<Int>.noBadgeWhileFailing(message: String): Flow<Int> = retryWhen { e, _ ->
    Log.w(TAG, message, e)
    emit(0)
    delay(RETRY_DELAY_MS)
    true
  }

  internal companion object {
    private const val TAG = "BottomBarViewModel"
    /** How long the badge waits before reading again after a failed read. */
    const val RETRY_DELAY_MS = 30_000L
    /** Keeps the count through a rotation instead of reading it again. */
    private const val STOP_TIMEOUT_MS = 5_000L
  }
}
