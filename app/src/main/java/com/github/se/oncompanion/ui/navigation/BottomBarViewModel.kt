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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Counts the prescriptions a caregiver added that wait for the signed-in patient (US-43), for the
 * badge on the bottom bar's Prescriptions tab. One instance serves every tab, so they all show the
 * same count.
 *
 * It outlives sign-out, so it follows whoever is signed in rather than the first account. The count
 * comes from the device's cache when offline. It is only a reminder: if the prescriptions can't be
 * read, no badge shows.
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
                    // Caught here, so the next account signed in still gets its count
                    .catch { e ->
                      Log.w(TAG, "Couldn't count the prescriptions to confirm", e)
                      emit(0)
                    }
          }
          .catch { e ->
            Log.w(TAG, "Couldn't follow the signed-in user", e)
            emit(0)
          }
          .map { BottomBarUiState(prescriptionsToReview = it) }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
              BottomBarUiState(),
          )

  private companion object {
    const val TAG = "BottomBarViewModel"
    /** Keeps the count through a rotation instead of reading it again. */
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
