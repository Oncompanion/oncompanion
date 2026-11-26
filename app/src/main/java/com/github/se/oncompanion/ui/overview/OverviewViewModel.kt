// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.carecircle.CareAccess
import com.github.se.oncompanion.domain.carecircle.ResolveCareCircleAccess
import com.github.se.oncompanion.domain.planning.CarePlanning
import com.github.se.oncompanion.domain.planning.ObserveCarePlanning
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.signedInUid
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.medication.TakenDosesRepository
import com.github.se.oncompanion.model.medication.TakenDosesRepositoryProvider
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.model.speech.SpeechRecognizer
import com.github.se.oncompanion.model.speech.SpeechRecognizerProvider
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomRepositoryProvider
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import com.github.se.oncompanion.ui.common.DoseTicks
import com.github.se.oncompanion.ui.common.DosesSheet
import com.github.se.oncompanion.ui.common.TodayMedication
import com.github.se.oncompanion.ui.common.todayMedicationsOf
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a [TodayEntry] is, which decides its icon. */
enum class TodayItemKind {
  MEDICATION,
  EVENT,
  APPOINTMENT,
}

/** Where a timed [TodayEntry] stands in the day, at the current time. */
enum class TodayItemStatus {
  /** An event or appointment that is over. */
  DONE,
  /** An event or appointment happening right now. */
  NOW,
  /** The first timed entry still to come. */
  NEXT,
  /** Planned after the next one. */
  LATER,
}

/**
 * The user's next appointment, as shown on the Overview.
 *
 * @property id the source appointment identity used to open its details
 * @property title what the appointment is (e.g. "Oncology check-up")
 * @property dateTime when it starts, in the phone's time zone
 * @property place where it takes place, if the user entered it
 */
data class NextAppointment(
    val id: String,
    val title: String,
    val dateTime: LocalDateTime,
    val place: String? = null,
)

/**
 * One entry of the Overview's "Today" list, taken from the planning data.
 *
 * @property key identifies the entry, the same as in Planning
 * @property time when it starts, or `null` for an entry with a date but no time (a medication)
 * @property subtitle a short detail line (place, recorded frequency...), if any
 * @property status where a timed entry stands now; `null` for an entry without a time, since the
 *   app never decides whether a medication was taken
 * @property notConfirmedAddedBy who added a medication whose prescription the patient hasn't
 *   confirmed yet (US-43), shown as "Added by Sam · Not confirmed"; `null` otherwise
 */
data class TodayEntry(
    val key: String,
    val kind: TodayItemKind,
    val time: LocalTime?,
    val title: String,
    val subtitle: String? = null,
    val status: TodayItemStatus? = null,
    val notConfirmedAddedBy: String? = null,
)

/**
 * What the patient's "Next up" card shows: the next event or appointment still to come today or, if
 * there is none, the next appointment of a later day.
 *
 * @property kind [TodayItemKind.EVENT] or [TodayItemKind.APPOINTMENT], which decides what opens
 * @property id the event's or the appointment's ID, to open its page
 * @property dateTime when it starts, in the phone's time zone
 * @property place where it takes place, if known
 */
data class NextUp(
    val kind: TodayItemKind,
    val id: String,
    val title: String,
    val dateTime: LocalDateTime,
    val place: String? = null,
)

/**
 * What the Overview shows.
 *
 * @property now the current time, for the greeting, today's date and the entries' status
 * @property firstName the user's first name, or `null` for a greeting without a name
 * @property nextUp the patient's next event or appointment, see [NextUp]
 * @property todayMedications the medications to take today, in Planning's order
 * @property todayEntries today's entries: those without a time first, then by time
 * @property isLoading true until the planning data arrives
 * @property hasError true if it couldn't be loaded
 * @property isNotShared in caregiver mode, the patient doesn't share their planning: no entries
 * @property sharedSections in caregiver mode, the sections behind the shortcuts the patient shares
 * @property dosesSheet the sheet correcting a medication's doses, or `null` when it is closed
 * @property canSpeak the phone can listen, so the check-in offers to speak a symptom (US-73)
 * @property savedToday the entry the patient saved last among those of today, shown by the check-in
 *   instead of its tiles until the day ends (#544); `null` when there is none
 * @property undoFailed undoing [savedToday] failed: the screen says so once, then calls
 *   [OverviewViewModel.undoFailureShown]
 */
data class OverviewUiState(
    val now: LocalDateTime,
    val firstName: String? = null,
    val nextAppointment: NextAppointment? = null,
    val nextUp: NextUp? = null,
    val todayMedications: List<TodayMedication> = emptyList(),
    val todayEntries: List<TodayEntry> = emptyList(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val isNotShared: Boolean = false,
    val sharedSections: Set<CarePermission> = emptySet(),
    val dosesSheet: DosesSheet? = null,
    val canSpeak: Boolean = false,
    val savedToday: LatestEntry? = null,
    val undoFailed: Boolean = false,
)

/**
 * Loads the user's day for the Overview (US-23): their first name, their next appointment and
 * today's entries. The appointment and the entries come from the same planning data as the Planning
 * screen, so both always agree.
 *
 * In caregiver mode (US-20), the day is that of [patientUid], when the patient shares their
 * planning.
 *
 * @param clock the current time
 * @param zoneId the time zone the day is shown in
 * @param patientUid in caregiver mode, the patient whose day is shown; null for the user's own
 * @param resolveAccess which sections the patient shares, for the shortcuts
 * @param takenDosesRepository the doses the patient ticks on their medications (US-28)
 * @param speechRecognizer the phone's recognizer, for whether the patient can speak a symptom;
 *   `null` before the app installed it, so the check-in doesn't offer to speak
 * @param symptomRepository the patient's journal, for the entry they saved today and its Undo
 */
class OverviewViewModel(
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val profileRepository: UserProfileRepository = UserProfileRepositoryProvider.repository,
    private val observePlanning: ObserveCarePlanning = ObserveCarePlanning(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val patientUid: String? = null,
    private val resolveAccess: ResolveCareCircleAccess =
        ResolveCareCircleAccess(auth = authRepository),
    private val takenDosesRepository: TakenDosesRepository =
        TakenDosesRepositoryProvider.repository,
    speechRecognizer: SpeechRecognizer? = SpeechRecognizerProvider.installed,
    private val symptomRepository: SymptomRepository = SymptomRepositoryProvider.repository,
) : ViewModel() {

  private val _uiState =
      MutableStateFlow(
          OverviewUiState(now = now(), canSpeak = speechRecognizer?.isAvailable() == true)
      )
  val uiState: StateFlow<OverviewUiState> = _uiState.asStateFlow()

  private var dayObservation: Job? = null

  /**
   * The doses the patient ticked today and the sheet correcting them, in patient mode; unknown in
   * caregiver mode, so nothing can be ticked.
   */
  private val doseTicks =
      DoseTicks(viewModelScope, takenDosesRepository, authRepository, ::refreshMedications)

  /** Follows the patient's journal for the entry they saved today, in patient mode. */
  private var journalObservation: Job? = null

  /** The patient's journal as last read; empty until it is, or if it can't be. */
  private var journal: List<SymptomEntry> = emptyList()

  /**
   * The entries the patient undid, hidden at once, until the journal confirms they are gone (then
   * forgotten, so one the server puts back shows again).
   */
  private val undone = mutableSetOf<String>()

  /** Follows which sections the patient shares, in caregiver mode. */
  private var sharedObservation: Job? = null

  /**
   * The planning entries as the repository gave them; what they show depends on the time. A flow,
   * so [followToday] plans its next refresh again when they change.
   */
  private val items = MutableStateFlow<List<PlanningItem>>(emptyList())

  init {
    loadFirstName()
    loadDay()
  }

  /**
   * In caregiver mode, the sections the patient shares, for the shortcuts: all of them from one
   * access, so the tiles always agree. If it can't be read, no tile shows until Retry.
   */
  private fun followSharedSections(patientUid: String) {
    sharedObservation?.cancel()
    sharedObservation = viewModelScope.launch {
      flow { emitAll(resolveAccess(patientUid)) }
          .catch { e ->
            Log.w(TAG, "Couldn't check the shared sections", e)
            emit(CareAccess.Unconfirmed)
          }
          .collect { access ->
            _uiState.update {
              it.copy(sharedSections = (access as? CareAccess.Granted)?.sections.orEmpty())
            }
          }
    }
  }

  /**
   * Starts (or, after an error, restarts) observing the planning data of [overviewRange], so the
   * next appointment is found even months ahead, as between two oncology check-ups. In caregiver
   * mode, it also (re)starts following the shared sections; for the patient, the doses ticked
   * today.
   */
  fun loadDay() {
    dayObservation?.cancel()
    val now = now()
    val today = now.toLocalDate()
    if (patientUid != null) {
      followSharedSections(patientUid)
    } else {
      doseTicks.follow(authRepository.signedInUid(TAG), today)
      followJournal()
    }
    // Following the day closes the sheet: a draft was for the day it was opened on
    _uiState.update { it.copy(now = now, isLoading = true, hasError = false) }
    val range = overviewRange(today, zoneId)
    dayObservation = viewModelScope.launch {
      observePlanning(patientUid, range)
          .catch { e ->
            Log.e(TAG, "Failed to load the day", e)
            _uiState.update { it.copy(isLoading = false, hasError = true) }
          }
          .collect { planning ->
            if (planning is CarePlanning.Failed) Log.e(TAG, "Failed to load the day")
            else items.value = (planning as? CarePlanning.Shown)?.items.orEmpty()
            _uiState.update { state ->
              state
                  .copy(
                      isLoading = planning is CarePlanning.Loading,
                      hasError = planning is CarePlanning.Failed,
                      isNotShared = planning is CarePlanning.NotShared,
                  )
                  .withItems(items.value, state.now)
            }
          }
    }
  }

  /**
   * Updates the time, so the greeting, the date, the next appointment and the entries' status stay
   * right while the ViewModel lives ([followToday] calls it at each change). On a new day, it also
   * reloads the planning data from the new day.
   */
  fun refreshNow() {
    val now = now()
    val newDay = now.toLocalDate() != _uiState.value.now.toLocalDate()
    _uiState.update { it.copy(now = now).withItems(items.value, now) }
    if (newDay) loadDay()
  }

  /**
   * Keeps the day right while the Overview is open: refreshes the time now, then each time what it
   * shows changes (see [followDay]), until the returned job is cancelled. The screen runs it while
   * it is shown. When the entries change, the next refresh is planned again from them.
   */
  fun followToday(): Job = viewModelScope.followDay(items, clock, zoneId, ::refreshNow)

  private fun now(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), zoneId)

  /** The next appointment and today's entries from [planned], at [now]. */
  private fun OverviewUiState.withItems(planned: List<PlanningItem>, now: LocalDateTime) =
      copy(
              nextAppointment = nextAppointmentOf(planned, now),
              nextUp = nextUpOf(planned, now, zoneId),
              todayEntries = entriesOf(planned, now),
          )
          .withMedications(todayMedicationsOf(planned, now, zoneId, doseTicks.taken))

  /** [medications] shown, with the sheet as [DoseTicks.keepSheetFor] leaves it. */
  private fun OverviewUiState.withMedications(medications: List<TodayMedication>): OverviewUiState {
    doseTicks.keepSheetFor(medications)
    return copy(todayMedications = medications, dosesSheet = doseTicks.sheet)
  }

  private fun refreshMedications() {
    _uiState.update {
      it.withMedications(todayMedicationsOf(items.value, it.now, zoneId, doseTicks.taken))
    }
  }

  private fun medication(medicationId: String) =
      _uiState.value.todayMedications.find { it.medicationId == medicationId }

  /**
   * The patient ticks a dose of [medicationId]: the next one, up to its doses a day, or, for a
   * medication taken once a day that is already ticked, unticks it (its button and row toggle).
   * Nothing happens for a medication without doses to tick.
   */
  fun tickDose(medicationId: String) = doseTicks.tick(medication(medicationId))

  /**
   * Opens the sheet to correct the doses of [medicationId], from the count saved. Only for a
   * medication taken several times a day whose ticks are known: one taken once a day toggles.
   */
  fun openDoses(medicationId: String) = doseTicks.openSheet(medication(medicationId))

  /** One dose fewer in the sheet's draft, not below 0. */
  fun lessDoses() = doseTicks.lessDoses()

  /** One dose more in the sheet's draft, up to the doses a day. */
  fun moreDoses() = doseTicks.moreDoses()

  /**
   * Done: closes the sheet and saves its draft on the day shown, if it differs from the count saved
   * now (another phone may have ticked since it opened).
   */
  fun saveDoses() = doseTicks.saveSheet()

  /** Closes the sheet without saving: swiped down, Back, or a tap outside it. */
  fun closeDoses() = doseTicks.closeSheet()

  /**
   * Follows the patient's journal, for the entry they saved today. If it can't be read, the
   * check-in shows its tiles: logging another symptom matters more than seeing the last one.
   */
  private fun followJournal() {
    journalObservation?.cancel()
    journal = emptyList()
    val uid = authRepository.signedInUid(TAG)
    if (uid != null) {
      journalObservation = viewModelScope.launch {
        // Built inside the flow, so even a failure to start reaches the catch
        flow { emitAll(symptomRepository.observeSymptoms(uid)) }
            .catch { e ->
              // The type only: a message can name the entry's path
              Log.w(TAG, "Couldn't read the journal: ${e.javaClass.simpleName}")
              emit(emptyList())
            }
            .collect { entries ->
              journal = entries
              // An undone entry stays hidden only until the journal drops it: if the server
              // rejects the deletion later, Firestore puts it back and it shows again
              val ids = entries.mapTo(HashSet()) { it.id }
              undone.retainAll(ids)
              refreshSaved()
            }
      }
    }
    refreshSaved()
  }

  /** Shows the entry saved on the day shown, so a new day starts with the check-in's tiles. */
  private fun refreshSaved() {
    _uiState.update { state -> state.copy(savedToday = savedOn(state.now.toLocalDate())) }
  }

  /**
   * The entry the patient saved last among those they felt on [day], or `null`. One a caregiver
   * added for them (US-46) isn't theirs to undo. One still being saved has the time the device
   * saved it, so several saved offline keep their order; one without any creation time is the
   * newest.
   */
  private fun savedOn(day: LocalDate): LatestEntry? =
      journal
          .filter {
            it.id !in undone &&
                it.addedBy == null &&
                it.occurredAt.atZone(zoneId).toLocalDate() == day
          }
          .maxByOrNull { it.createdAt ?: Instant.MAX }
          ?.toLatestEntry(zoneId)

  /**
   * Undo: deletes the entry saved today, [id]. It is hidden at once, so the previous one of the day
   * (or the tiles) shows even before the journal confirms it; if the deletion fails, it comes back
   * and [OverviewUiState.undoFailed] says so. Only the entry shown can be undone: a second quick
   * tap on Undo doesn't delete the previous entry that has just replaced it.
   */
  fun undoSaved(id: String) {
    if (_uiState.value.savedToday?.entry?.id != id) return
    val uid = authRepository.signedInUid(TAG)
    if (uid == null) {
      _uiState.update { it.copy(undoFailed = true) }
      return
    }
    undone += id
    refreshSaved()
    viewModelScope.launch {
      try {
        symptomRepository.deleteSymptom(uid, id)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Couldn't undo the entry saved: ${e.javaClass.simpleName}")
        undone -= id
        refreshSaved()
        _uiState.update { it.copy(undoFailed = true) }
      }
    }
  }

  /** The screen said that Undo failed. */
  fun undoFailureShown() {
    _uiState.update { it.copy(undoFailed = false) }
  }

  /** The first appointment that hasn't started yet. */
  private fun nextAppointmentOf(planned: List<PlanningItem>, now: LocalDateTime): NextAppointment? =
      planned
          .mapNotNull { item ->
            (item.source as? PlanningSource.Appointment)?.let { source ->
              item.startIn(zoneId)?.let { start ->
                NextAppointment(source.appointmentId, item.title, start, item.subtitle)
              }
            }
          }
          .filter { it.dateTime.isAfter(now) }
          .minByOrNull { it.dateTime }

  /**
   * Today's entries: those without a time first, as in Planning, then the timed ones by time. A
   * timed entry is happening from its start until its end, then it's done; the first one still to
   * come is the next.
   */
  private fun entriesOf(planned: List<PlanningItem>, now: LocalDateTime): List<TodayEntry> {
    val today = now.toLocalDate()
    val ofToday = planned.filter { it.timing.dateIn(zoneId) == today }
    val untimed = ofToday.filter { it.timing is PlanningTiming.DateOnly }
    val timed =
        ofToday
            .mapNotNull { item -> item.startIn(zoneId)?.let { item to it } }
            .sortedBy { it.second }
    val next = timed.firstOrNull { (_, start) -> start.isAfter(now) }?.first
    return untimed.map { it.toEntry(time = null, status = null) } +
        timed.map { (item, start) ->
          val status =
              when {
                !item.endIn(zoneId).isAfter(now) -> TodayItemStatus.DONE
                !start.isAfter(now) -> TodayItemStatus.NOW
                item == next -> TodayItemStatus.NEXT
                else -> TodayItemStatus.LATER
              }
          item.toEntry(time = start.toLocalTime(), status = status)
        }
  }

  private fun PlanningItem.toEntry(time: LocalTime?, status: TodayItemStatus?) =
      TodayEntry(
          key = key,
          kind =
              when (source) {
                is PlanningSource.Medication -> TodayItemKind.MEDICATION
                is PlanningSource.Event -> TodayItemKind.EVENT
                is PlanningSource.Appointment -> TodayItemKind.APPOINTMENT
              },
          time = time,
          title = title,
          // A medication's detail is its recorded frequency, as in Planning
          subtitle = subtitle ?: frequency,
          status = status,
          notConfirmedAddedBy = addedByName.takeIf { !isConfirmed },
      )

  /**
   * Shows the first name from the user's profile, or else from their Google account. The greeting
   * is only a nicety: if anything fails here, the Overview simply greets without a name.
   */
  private fun loadFirstName() {
    val user =
        try {
          authRepository.currentUser
        } catch (e: Exception) {
          Log.w(TAG, "Couldn't read the signed-in user", e)
          null
        } ?: return
    _uiState.update { it.copy(firstName = user.firstNameGuess()) }
    viewModelScope.launch {
      try {
        profileRepository.observeProfile(user.uid).collect { profile ->
          val name = profile?.firstName?.takeIf { it.isNotBlank() } ?: return@collect
          _uiState.update { it.copy(firstName = name) }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't load the profile", e)
      }
    }
  }

  private companion object {
    const val TAG = "OverviewViewModel"
  }
}
