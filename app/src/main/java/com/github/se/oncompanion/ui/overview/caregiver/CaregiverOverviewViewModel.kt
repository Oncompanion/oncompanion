// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview.caregiver

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.carecircle.CareAccess
import com.github.se.oncompanion.domain.carecircle.CaregiverName
import com.github.se.oncompanion.domain.carecircle.ResolveCareCircleAccess
import com.github.se.oncompanion.domain.planning.CarePlanning
import com.github.se.oncompanion.domain.planning.ObserveCarePlanning
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.signedInUid
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import com.github.se.oncompanion.ui.overview.NextUp
import com.github.se.oncompanion.ui.overview.followDay
import com.github.se.oncompanion.ui.overview.nextUpOf
import com.github.se.oncompanion.ui.overview.overviewRange
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whether a section of the patient's day shows to the caregiver. */
enum class SectionVisibility {
  /** The patient shares it: its card shows. */
  SHOWN,
  /** The patient keeps it private: a locked card shows instead, with none of its data. */
  PRIVATE,
}

/**
 * What the caregiver's own Overview shows: their greeting, then the day of the patient they follow.
 *
 * @property now the current time, for the greeting, today's date and Next up
 * @property firstName the caregiver's first name, or `null` for a greeting without a name
 * @property nextUp the patient's next event or appointment, only while they share their planning
 * @property sections each section of the day, in display order, once the access is known
 * @property isLoading true until the access and the planning are known
 * @property hasError true if the patient's day couldn't be loaded
 */
data class CaregiverOverviewUiState(
    val now: LocalDateTime,
    val firstName: String? = null,
    val nextUp: NextUp? = null,
    val sections: Map<CarePermission, SectionVisibility> = emptyMap(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
) {
  /** The sections the patient keeps private, in display order. */
  val privateSections: List<CarePermission>
    get() = sections.filterValues { it == SectionVisibility.PRIVATE }.keys.toList()
}

/**
 * Loads the caregiver's own Overview: their first name, and the day of [patientUid], limited to
 * what the patient shares. The planning comes from [observePlanning], as on the patient's Overview,
 * and what is shared from [resolveAccess]; both follow the patient's changes while it is open.
 *
 * @param patientUid the patient whose day is shown; one ViewModel per patient
 * @param clock the current time
 * @param zoneId the time zone the day is shown in
 */
class CaregiverOverviewViewModel(
    private val patientUid: String,
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    profileRepository: UserProfileRepository = UserProfileRepositoryProvider.repository,
    private val observePlanning: ObserveCarePlanning = ObserveCarePlanning(),
    private val resolveAccess: ResolveCareCircleAccess =
        ResolveCareCircleAccess(auth = authRepository),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

  private val caregiverName = CaregiverName(profileRepository, authRepository)

  private val _uiState = MutableStateFlow(CaregiverOverviewUiState(now = now()))
  val uiState: StateFlow<CaregiverOverviewUiState> = _uiState.asStateFlow()

  private var dayObservation: Job? = null

  /** The patient's entries the caregiver may see; what they show depends on the time. */
  private val items = MutableStateFlow<List<PlanningItem>>(emptyList())

  init {
    loadFirstName()
    loadDay()
  }

  /** Loads the patient's day again, after an error. */
  fun retry() = loadDay()

  /**
   * Keeps the day right while the Overview is open, see [followDay]; the screen runs it while it is
   * shown and cancels the returned job.
   */
  fun followToday(): Job = viewModelScope.followDay(items, clock, zoneId, ::refreshNow)

  /** Updates the time and Next up; on a new day, it also reloads the planning from the new day. */
  private fun refreshNow() {
    val now = now()
    val newDay = now.toLocalDate() != _uiState.value.now.toLocalDate()
    _uiState.update { it.copy(now = now, nextUp = nextUpOf(items.value, now, zoneId)) }
    if (newDay) loadDay()
  }

  /**
   * Follows what the patient shares and their planning, from the start: an error (also an
   * unexpected one) ends the loading, and [retry] starts over.
   */
  private fun loadDay() {
    dayObservation?.cancel()
    val now = now()
    _uiState.update { it.copy(now = now, isLoading = true, hasError = false) }
    val range = overviewRange(now.toLocalDate(), zoneId)
    dayObservation = viewModelScope.launch {
      // Built inside the flow, so even a failure to start reaches the catch
      flow {
        emitAll(combine(resolveAccess(patientUid), observePlanning(patientUid, range), ::Pair))
      }
          .catch { e ->
            Log.e(TAG, "Couldn't load the patient's day", e)
            _uiState.update { it.copy(isLoading = false, hasError = true) }
          }
          .collect { (access, planning) -> show(access, planning) }
    }
  }

  /** Shows what [access] lets the caregiver see of [planning]. */
  private fun show(access: CareAccess, planning: CarePlanning) {
    val sections =
        (access as? CareAccess.Granted)
            ?.let { granted ->
              DAY_SECTIONS.associateWith {
                if (granted.canSee(it)) SectionVisibility.SHOWN else SectionVisibility.PRIVATE
              }
            }
            .orEmpty()
    // Dropped as soon as the patient stops sharing their planning, before the planning says so
    items.value =
        if (sections[CarePermission.PLANNING] == SectionVisibility.SHOWN)
            (planning as? CarePlanning.Shown)?.items.orEmpty()
        else emptyList()
    _uiState.update {
      it.copy(
          sections = sections,
          nextUp = nextUpOf(items.value, it.now, zoneId),
          isLoading = access is CareAccess.Unconfirmed || planning is CarePlanning.Loading,
          hasError = planning is CarePlanning.Failed,
      )
    }
  }

  /** The caregiver's name for the greeting; without a name if it is not known. */
  private fun loadFirstName() {
    val uid = authRepository.signedInUid(TAG) ?: return
    viewModelScope.launch {
      val name = caregiverName(uid).takeIf { it != CaregiverName.DEFAULT }
      _uiState.update { it.copy(firstName = name) }
    }
  }

  private fun now(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), zoneId)

  private companion object {
    const val TAG = "CaregiverOverviewVM"

    /** The sections of the patient's day, in the order their cards show. */
    val DAY_SECTIONS =
        listOf(CarePermission.PLANNING, CarePermission.PRESCRIPTIONS, CarePermission.SYMPTOMS)
  }
}
