// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.carecircle.CareAccess
import com.github.se.oncompanion.domain.carecircle.ResolveCareCircleAccess
import com.github.se.oncompanion.domain.medication.DoseCount
import com.github.se.oncompanion.domain.planning.CarePlanning
import com.github.se.oncompanion.domain.planning.ObserveCarePlanning
import com.github.se.oncompanion.domain.planning.endOrDefault
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.signedInUid
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.medication.TakenDosesRepository
import com.github.se.oncompanion.model.medication.TakenDosesRepositoryProvider
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Period
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
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
 * One of the medications to take today, on the patient's "Today's medications" card, with the doses
 * they ticked (US-28).
 *
 * @property key identifies it, the same as in Planning
 * @property medicationId the [com.github.se.oncompanion.model.medication.Medication] it is, under
 *   which its doses are saved
 * @property name its name and dosage (e.g. "Ondansetron 8 mg")
 * @property frequency the frequency recorded on the prescription, if any
 * @property dosesPerDay how many doses can be ticked today, read from [frequency]; `null` when
 *   there is nothing to tick (taken only if needed, a frequency that doesn't say a number) or
 *   today's ticks couldn't be read
 * @property taken how many of them the patient ticked today, from 0 to [dosesPerDay]
 */
data class TodayMedication(
    val key: String,
    val medicationId: String,
    val name: String,
    val frequency: String? = null,
    val dosesPerDay: Int? = null,
    val taken: Int = 0,
)

/**
 * The sheet where the patient corrects how many doses of a medication taken several times a day
 * they took today (#546). Nothing is saved until Done: a sheet closed any other way, or an app
 * stopped with it open, leaves the saved count as it was.
 *
 * @property medication the medication corrected, as the card shows it now
 * @property doses its doses a day, at least 2
 * @property draft the count the patient set with − and +, from 0 to [doses]
 */
data class DosesSheet(val medication: TodayMedication, val doses: Int, val draft: Int)

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
) : ViewModel() {

  private val _uiState = MutableStateFlow(OverviewUiState(now = now()))
  val uiState: StateFlow<OverviewUiState> = _uiState.asStateFlow()

  private var dayObservation: Job? = null

  /** Follows the doses the patient ticked today, in patient mode. */
  private var dosesObservation: Job? = null

  /**
   * The doses ticked today by medication ID, or `null` while they are unknown (not read yet, or
   * they couldn't be): then no medication can be ticked, since a tick saves a count and ticking
   * from a wrong 0 would overwrite the real one.
   */
  private var takenToday: Map<String, Int>? = null

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
   * Starts (or, after an error, restarts) observing the planning data from today to [LOOKAHEAD]
   * later, so the next appointment is found even months ahead, as between two oncology check-ups.
   * In caregiver mode, it also (re)starts following the shared sections.
   */
  fun loadDay() {
    dayObservation?.cancel()
    val now = now()
    val today = now.toLocalDate()
    if (patientUid != null) followSharedSections(patientUid) else followTakenDoses(today)
    // A draft was for the day it was opened on: at midnight it would be saved on the new day. The
    // planning's loading state also empties today's medications, which closes it, but that's not
    // what the sheet should rely on
    _uiState.update { it.copy(now = now, isLoading = true, hasError = false, dosesSheet = null) }
    val range = PlanningRange(today, today.plus(LOOKAHEAD), zoneId)
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
   * shows changes (see [nextChangeAfter]), until the returned job is cancelled. The screen runs it
   * while it is shown. When the entries change, the next refresh is planned again from them.
   */
  fun followToday(): Job = viewModelScope.launch {
    items.collectLatest { planned ->
      while (true) {
        refreshNow()
        val now = clock.instant()
        val wait = Duration.between(now, nextChangeAfter(planned, now))
        // Rounded up, so it never wakes just before the change; at least 1 ms, so it always
        // suspends even if the clock moved
        delay(((wait.toNanos() + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI).coerceAtLeast(1))
      }
    }
  }

  /**
   * The next moment the Overview's content changes after [now]: an entry starts (Next up moves on,
   * an entry is happening), an entry ends (it is done), or midnight (the new day loads). Midnight
   * is always one of them, so it is never more than a day away. In instants, so the hour repeated
   * when the clocks go back can't give a moment already past.
   */
  private fun nextChangeAfter(planned: List<PlanningItem>, now: Instant): Instant =
      (planned
              .mapNotNull { it.timing as? PlanningTiming.Timed }
              .flatMap { listOf(it.instant, it.endOrDefault()) } +
              now.atZone(zoneId).toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant())
          .filter { it.isAfter(now) }
          .min()

  private fun now(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), zoneId)

  /** The next appointment and today's entries from [planned], at [now]. */
  private fun OverviewUiState.withItems(planned: List<PlanningItem>, now: LocalDateTime) =
      copy(
              nextAppointment = nextAppointmentOf(planned, now),
              nextUp = nextUpOf(planned, now),
              todayEntries = entriesOf(planned, now),
          )
          .withMedications(medicationsOf(planned, now))

  /**
   * [medications] shown, and the sheet kept only while its medication is still there with several
   * doses to tick (a caregiver can delete the prescription, or its frequency can change), its draft
   * within the new count.
   */
  private fun OverviewUiState.withMedications(medications: List<TodayMedication>) =
      copy(
          todayMedications = medications,
          dosesSheet =
              dosesSheet?.let { sheet ->
                sheetOf(
                    medications.find { it.medicationId == sheet.medication.medicationId },
                    sheet.draft,
                )
              },
      )

  /**
   * The sheet for [medication], with [draft] (its count saved for `null`), or `null` if it can't be
   * corrected there: unknown, nothing to tick, or taken once a day.
   */
  private fun sheetOf(medication: TodayMedication?, draft: Int?): DosesSheet? {
    if (medication == null) return null
    val doses = medication.dosesPerDay ?: return null
    if (doses < 2) return null
    return DosesSheet(medication, doses, draft?.coerceAtMost(doses) ?: medication.taken)
  }

  /**
   * The next event or appointment that hasn't started yet, today or on a later day, so a check-up
   * weeks away still shows. An event is in the planning only once the patient registered for it, so
   * a later one shows too.
   */
  private fun nextUpOf(planned: List<PlanningItem>, now: LocalDateTime): NextUp? {
    val next =
        planned
            .mapNotNull { item ->
              item.startIn()?.takeIf { it.isAfter(now) }?.let { start -> item to start }
            }
            .minByOrNull { it.second }
    return next?.let { (item, start) ->
      when (val source = item.source) {
        is PlanningSource.Appointment ->
            NextUp(
                TodayItemKind.APPOINTMENT,
                source.appointmentId,
                item.title,
                start,
                item.subtitle,
            )
        is PlanningSource.Event ->
            NextUp(TodayItemKind.EVENT, source.eventId, item.title, start, item.subtitle)
        // A medication has no time, so it's never upcoming
        is PlanningSource.Medication -> null
      }
    }
  }

  /**
   * Today's medications, with their dosage after their name and the doses ticked. One a caregiver
   * added stays off the Overview until the patient confirms its prescription: it isn't part of
   * their treatment before (Planning still lists it).
   */
  private fun medicationsOf(planned: List<PlanningItem>, now: LocalDateTime) =
      planned
          .filter { it.isConfirmed && it.timing.dateIn(zoneId) == now.toLocalDate() }
          .mapNotNull { item ->
            val source = item.source as? PlanningSource.Medication ?: return@mapNotNull null
            val frequency = item.frequency?.takeIf { it.isNotBlank() }
            val doses = DoseCount.of(frequency).takeIf { takenToday != null }
            TodayMedication(
                key = item.key,
                medicationId = source.medicationId,
                name =
                    listOfNotNull(item.title, item.dosage?.takeIf { it.isNotBlank() })
                        .joinToString(" "),
                frequency = frequency,
                dosesPerDay = doses,
                taken = doses?.let { takenToday?.get(source.medicationId)?.coerceIn(0, it) } ?: 0,
            )
          }

  /**
   * Follows the doses the patient ticked on [day], in patient mode. If they can't be read, nothing
   * can be ticked until the day is loaded again (Retry, or the next day).
   */
  private fun followTakenDoses(day: LocalDate) {
    dosesObservation?.cancel()
    takenToday = null
    val uid = authRepository.signedInUid(TAG) ?: return
    dosesObservation = viewModelScope.launch {
      takenDosesRepository
          .observeDay(uid, day)
          .catch { e ->
            Log.w(TAG, "Couldn't read the doses taken today", e)
            takenToday = null
            refreshMedications()
          }
          .collect { taken ->
            takenToday = taken
            refreshMedications()
          }
    }
  }

  private fun refreshMedications() {
    _uiState.update { it.withMedications(medicationsOf(items.value, it.now)) }
  }

  /**
   * The patient ticks a dose of [medicationId]: the next one, up to its doses a day, or, for a
   * medication taken once a day that is already ticked, unticks it (its button and row toggle).
   * Nothing happens for a medication without doses to tick.
   */
  fun tickDose(medicationId: String) {
    val medication = _uiState.value.todayMedications.find { it.medicationId == medicationId }
    val doses = medication?.dosesPerDay ?: return
    val count =
        if (doses == 1) 1 - medication.taken.coerceAtMost(1)
        else (medication.taken + 1).coerceAtMost(doses)
    if (count != medication.taken) saveTaken(medicationId, count)
  }

  /**
   * Opens the sheet to correct the doses of [medicationId], from the count saved. Only for a
   * medication taken several times a day whose ticks are known: one taken once a day toggles.
   */
  fun openDoses(medicationId: String) {
    _uiState.update { state ->
      val sheet = sheetOf(state.todayMedications.find { it.medicationId == medicationId }, null)
      if (sheet == null) state else state.copy(dosesSheet = sheet)
    }
  }

  /** One dose fewer in the sheet's draft, not below 0. */
  fun lessDoses() = changeDraft(-1)

  /** One dose more in the sheet's draft, up to the doses a day. */
  fun moreDoses() = changeDraft(1)

  private fun changeDraft(by: Int) {
    _uiState.update { state ->
      val sheet = state.dosesSheet ?: return@update state
      state.copy(dosesSheet = sheet.copy(draft = (sheet.draft + by).coerceIn(0, sheet.doses)))
    }
  }

  /**
   * Done: closes the sheet and saves its draft, if it differs from the count saved now (another
   * phone may have ticked since it opened).
   */
  fun saveDoses() {
    val sheet = _uiState.value.dosesSheet ?: return
    closeDoses()
    if (sheet.draft != sheet.medication.taken) saveTaken(sheet.medication.medicationId, sheet.draft)
  }

  /** Closes the sheet without saving: swiped down, Back, or a tap outside it. */
  fun closeDoses() {
    _uiState.update { it.copy(dosesSheet = null) }
  }

  /** Saves [count] doses of [medicationId] on the day shown; the repository reports a rejection. */
  private fun saveTaken(medicationId: String, count: Int) {
    val uid = authRepository.signedInUid(TAG) ?: return
    val day = _uiState.value.now.toLocalDate()
    // Shown at once, so a second quick tap builds on this one instead of saving the same count
    // again; the day's next snapshot confirms it, or brings back the saved count if it's rejected
    takenToday = takenToday?.plus(medicationId to count)
    refreshMedications()
    viewModelScope.launch {
      try {
        takenDosesRepository.setTaken(uid, day, medicationId, count)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Couldn't save the doses taken", e)
      }
    }
  }

  /** The first appointment that hasn't started yet. */
  private fun nextAppointmentOf(planned: List<PlanningItem>, now: LocalDateTime): NextAppointment? =
      planned
          .mapNotNull { item ->
            (item.source as? PlanningSource.Appointment)?.let { source ->
              item.startIn()?.let { start ->
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
        ofToday.mapNotNull { item -> item.startIn()?.let { item to it } }.sortedBy { it.second }
    val next = timed.firstOrNull { (_, start) -> start.isAfter(now) }?.first
    return untimed.map { it.toEntry(time = null, status = null) } +
        timed.map { (item, start) ->
          val status =
              when {
                !item.endIn().isAfter(now) -> TodayItemStatus.DONE
                !start.isAfter(now) -> TodayItemStatus.NOW
                item == next -> TodayItemStatus.NEXT
                else -> TodayItemStatus.LATER
              }
          item.toEntry(time = start.toLocalTime(), status = status)
        }
  }

  /** When a timed entry ends, in [zoneId]. */
  private fun PlanningItem.endIn(): LocalDateTime =
      LocalDateTime.ofInstant((timing as PlanningTiming.Timed).endOrDefault(), zoneId)

  /** When a timed entry starts, in [zoneId]; `null` for an entry without a time. */
  private fun PlanningItem.startIn(): LocalDateTime? =
      (timing as? PlanningTiming.Timed)?.let { LocalDateTime.ofInstant(it.instant, zoneId) }

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

    /** How far ahead the next appointment is looked for. */
    val LOOKAHEAD: Period = Period.ofYears(1)

    private const val NANOS_PER_MILLI = 1_000_000L
  }
}
