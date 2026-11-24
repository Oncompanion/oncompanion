// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview.caregiver

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.carecircle.CareAccess
import com.github.se.oncompanion.domain.carecircle.CareSection
import com.github.se.oncompanion.domain.carecircle.CaregiverName
import com.github.se.oncompanion.domain.carecircle.ObserveCareSection
import com.github.se.oncompanion.domain.carecircle.ResolveCareCircleAccess
import com.github.se.oncompanion.domain.planning.CarePlanning
import com.github.se.oncompanion.domain.planning.ObserveCarePlanning
import com.github.se.oncompanion.domain.planning.PlanningRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.signedInUid
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.medication.TakenDosesRepository
import com.github.se.oncompanion.model.medication.TakenDosesRepositoryProvider
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomRepositoryProvider
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import com.github.se.oncompanion.ui.overview.NextUp
import com.github.se.oncompanion.ui.overview.TodayMedication
import com.github.se.oncompanion.ui.overview.followDay
import com.github.se.oncompanion.ui.overview.nextUpOf
import com.github.se.oncompanion.ui.overview.overviewRange
import com.github.se.oncompanion.ui.overview.startedToday
import com.github.se.oncompanion.ui.overview.todayMedicationsOf
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
 * The patient's newest journal entry, as the Overview says it: "Nausea 7/10 · Fatigue 6/10".
 *
 * @property entry the entry itself, for the names of its symptoms ("Other" may have one)
 * @property at when the patient felt it, in the Overview's time zone
 * @property symptoms each symptom with its strength out of 10, when the entry has one per symptom
 * @property overall the entry's single intensity, for entries saved before a strength per symptom
 *   (#615); `null` when the symptoms have their own
 */
data class LatestEntry(
    val entry: SymptomEntry,
    val at: LocalDateTime,
    val symptoms: List<Pair<SymptomType, Int?>>,
    val overall: Int?,
)

/**
 * What the caregiver's own Overview shows: their greeting, then the day of the patient they follow.
 *
 * @property now the current time, for the greeting, today's date and Next up
 * @property firstName the caregiver's first name, or `null` for a greeting without a name
 * @property nextUp the patient's next event or appointment, only while they share their planning
 * @property dayStarted something the patient planned today has already started: without [nextUp],
 *   the day says "Nothing else today" rather than "Nothing planned today"
 * @property sections each section of the day, in display order, once the access is known
 * @property latestEntry the patient's newest journal entry, only while they share their symptoms;
 *   `null` too when their journal is empty
 * @property todayMedications the medications the patient takes today, with the doses they ticked,
 *   only while they share their prescriptions; without doses to show if those can't be read
 * @property isLoading true until the access and the planning are known
 * @property hasError true if the patient's day couldn't be loaded
 */
data class CaregiverOverviewUiState(
    val now: LocalDateTime,
    val firstName: String? = null,
    val nextUp: NextUp? = null,
    val dayStarted: Boolean = false,
    val sections: Map<CarePermission, SectionVisibility> = emptyMap(),
    val latestEntry: LatestEntry? = null,
    val todayMedications: List<TodayMedication> = emptyList(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
) {
  /** The sections the patient keeps private, in display order. */
  val privateSections: List<CarePermission>
    get() = sections.filterValues { it == SectionVisibility.PRIVATE }.keys.toList()

  /**
   * Whether the day shows the medications card: loaded without an error, and with medications to
   * take today, as on the patient's Overview. Otherwise the Prescriptions tile opens them.
   */
  val showsMedications: Boolean
    get() =
        !isLoading &&
            !hasError &&
            todayMedications.isNotEmpty() &&
            sections[CarePermission.PRESCRIPTIONS] == SectionVisibility.SHOWN
}

/**
 * Loads the caregiver's own Overview: their first name, and the day of [patientUid], limited to
 * what the patient shares. The planning comes from [observePlanning], as on the patient's Overview,
 * the newest journal entry from [symptomRepository] and today's medications from [medications] and
 * [takenDoses] through [observeSection], and what is shared from [resolveAccess]; all follow the
 * patient's changes while it is open.
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
    private val symptomRepository: SymptomRepository = SymptomRepositoryProvider.repository,
    private val observeSection: ObserveCareSection = ObserveCareSection(),
    private val medications: PlanningRepository = PlanningRepositoryProvider.medications,
    private val takenDoses: TakenDosesRepository = TakenDosesRepositoryProvider.repository,
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
    _uiState.update { it.withDayAt(now) }
    if (newDay) loadDay()
  }

  /**
   * Follows what the patient shares, their planning, their newest journal entry and today's
   * medications, from the start: an error (also an unexpected one) ends the loading, and [retry]
   * starts over.
   */
  private fun loadDay() {
    dayObservation?.cancel()
    val now = now()
    _uiState.update { it.copy(now = now, isLoading = true, hasError = false) }
    val range = overviewRange(now.toLocalDate(), zoneId)
    dayObservation = viewModelScope.launch {
      // Built inside the flow, so even a failure to start reaches the catch
      flow {
            emitAll(
                combine(
                    resolveAccess(patientUid),
                    observePlanning(patientUid, range),
                    observeLatestEntry(),
                    observeMedications(now.toLocalDate()),
                    ::PatientDay,
                )
            )
          }
          .catch { e ->
            Log.e(TAG, "Couldn't load the patient's day", e)
            _uiState.update { it.copy(isLoading = false, hasError = true) }
          }
          .collect(::show)
    }
  }

  /** The patient's newest journal entry, or `null` while their journal is empty. */
  private fun observeLatestEntry(): Flow<CareSection<SymptomEntry?>> =
      observeSection(CarePermission.SYMPTOMS, patientUid) { ownerUid ->
        symptomRepository.observeSymptoms(ownerUid).map { entries ->
          entries.maxByOrNull { it.occurredAt }
        }
      }

  /**
   * The medications the patient takes on [day], with the doses they ticked. Doses that can't be
   * read, or aren't known yet (this phone has no copy of the day, offline), leave the medications
   * without them, not the card in an error nor a wrong "0 of 3".
   */
  private fun observeMedications(day: LocalDate): Flow<CareSection<List<TodayMedication>>> =
      observeSection(CarePermission.PRESCRIPTIONS, patientUid) { ownerUid ->
        combine(
            medications.observeItems(
                ownerUid,
                PlanningRange(day, day.plusDays(1), zoneId),
                setOf(CarePermission.PRESCRIPTIONS),
            ),
            // In a flow, so that even a read that throws right away only loses the doses
            flow { emitAll(takenDoses.observeDayIfKnown(ownerUid, day)) }
                .catch { e ->
                  if (e is CancellationException) throw e
                  Log.w(TAG, "Couldn't read the doses taken today", e)
                  emit(null)
                },
        ) { items, taken ->
          todayMedicationsOf(items, day.atStartOfDay(), zoneId, taken)
        }
      }

  /** Shows what the access lets the caregiver see of the patient's [day]. */
  private fun show(day: PatientDay) {
    val (access, planning, journal, todayMedications) = day
    val sections =
        (access as? CareAccess.Granted)
            ?.let { granted ->
              DAY_SECTIONS.associateWith {
                if (granted.canSee(it)) SectionVisibility.SHOWN else SectionVisibility.PRIVATE
              }
            }
            .orEmpty()
    val readsPlanning = sections[CarePermission.PLANNING] == SectionVisibility.SHOWN
    // Dropped as soon as the patient stops sharing their planning, before the planning says so
    items.value =
        if (readsPlanning) (planning as? CarePlanning.Shown)?.items.orEmpty() else emptyList()
    val shown = { section: CarePermission -> sections[section] == SectionVisibility.SHOWN }
    _uiState.update {
      it.copy(
              sections = sections,
              // Like the planning, both are dropped as soon as the patient stops sharing them
              latestEntry = journal.dataIf(shown(CarePermission.SYMPTOMS))?.let(::latestEntryOf),
              todayMedications =
                  todayMedications.dataIf(shown(CarePermission.PRESCRIPTIONS)).orEmpty(),
              isLoading = day.isLoading(shown),
              hasError = day.hasError(shown),
          )
          .withDayAt(it.now)
    }
  }

  /** The day at [now]: Next up, and whether something planned today has already started. */
  private fun CaregiverOverviewUiState.withDayAt(now: LocalDateTime) =
      copy(
          now = now,
          nextUp = nextUpOf(items.value, now, zoneId),
          dayStarted = startedToday(items.value, now, zoneId),
      )

  /**
   * [entry] as the Overview says it: a strength for each symptom when it has them, otherwise its
   * single intensity once, for all of its symptoms.
   */
  private fun latestEntryOf(entry: SymptomEntry): LatestEntry =
      LatestEntry(
          entry = entry,
          at = LocalDateTime.ofInstant(entry.occurredAt, zoneId),
          symptoms = entry.types.map { it to entry.strengths[it] },
          overall = entry.intensity.takeIf { entry.strengths.isEmpty() },
      )

  /** The caregiver's name for the greeting; without a name if it is not known. */
  private fun loadFirstName() {
    val uid = authRepository.signedInUid(TAG) ?: return
    viewModelScope.launch {
      val name = caregiverName(uid).takeIf { it != CaregiverName.DEFAULT }
      _uiState.update { it.copy(firstName = name) }
    }
  }

  private fun now(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), zoneId)

  /** Everything the caregiver's Overview reads of the patient, as last emitted. */
  private data class PatientDay(
      val access: CareAccess,
      val planning: CarePlanning,
      val journal: CareSection<SymptomEntry?>,
      val todayMedications: CareSection<List<TodayMedication>>,
  ) {
    /**
     * Whether the day is still loading: the access, or a section the access says is [shown] that
     * isn't read yet. A private section is never waited for, as it is never read.
     */
    fun isLoading(shown: (CarePermission) -> Boolean): Boolean =
        access is CareAccess.Unconfirmed ||
            planning is CarePlanning.Loading ||
            (shown(CarePermission.PLANNING) && planning.notReadYet()) ||
            (shown(CarePermission.SYMPTOMS) && journal.notReadYet()) ||
            (shown(CarePermission.PRESCRIPTIONS) && todayMedications.notReadYet())

    /** Whether the planning, or a section the access says is [shown], couldn't be read. */
    fun hasError(shown: (CarePermission) -> Boolean): Boolean =
        planning is CarePlanning.Failed ||
            (shown(CarePermission.SYMPTOMS) && journal is CareSection.Failed) ||
            (shown(CarePermission.PRESCRIPTIONS) && todayMedications is CareSection.Failed)

    /**
     * Whether the planning's own listener doesn't read it yet although the access says it is
     * shared, as when the patient has just started sharing it: "Nothing planned today" would be
     * wrong.
     */
    private fun CarePlanning.notReadYet(): Boolean =
        this is CarePlanning.NotShared || this is CarePlanning.NoAccess

    /**
     * Whether a section (the journal, the medications) isn't read yet: loading, or its own listener
     * doesn't see it shared yet although the access does, as when the patient has just started
     * sharing it ("No entries yet" would be wrong).
     */
    private fun CareSection<*>.notReadYet(): Boolean =
        this is CareSection.Loading || this is CareSection.NotShared || this is CareSection.NoAccess
  }

  /** The section's data while it is [shared] and read, otherwise `null`. */
  private fun <T> CareSection<T>.dataIf(shared: Boolean): T? =
      (this as? CareSection.Shown)?.data?.takeIf { shared }

  private companion object {
    const val TAG = "CaregiverOverviewVM"

    /** The sections of the patient's day, in the order their cards show. */
    val DAY_SECTIONS =
        listOf(CarePermission.PLANNING, CarePermission.PRESCRIPTIONS, CarePermission.SYMPTOMS)
  }
}
