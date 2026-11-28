// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleMembership
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.JoinedCareCircle
import com.github.se.oncompanion.model.carecircle.LastOpened
import com.github.se.oncompanion.model.carecircle.LastOpenedProvider
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomRepositoryProvider
import com.github.se.oncompanion.ui.overview.LatestEntry
import com.github.se.oncompanion.ui.overview.toLatestEntry
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A patient the caregiver follows.
 *
 * @property uid the patient's uid
 * @property firstName the patient's first name, as copied when the caregiver joined
 * @property sharedSections what the patient lets this caregiver see
 */
data class FollowedPatient(
    val uid: String,
    val firstName: String,
    val sharedSections: Set<CarePermission>,
)

/**
 * What a followed person logged last, as "Whose care?" says it under their name.
 *
 * @property latest their newest journal entry that the caregiver didn't write
 * @property isNew whether they logged it after the caregiver last had their day open
 */
data class PatientNews(val latest: LatestEntry, val isNew: Boolean)

/**
 * What "Whose care?" shows.
 *
 * @property patients the patients the caregiver follows, sorted by first name
 * @property isLoading true until the list arrives
 * @property hasError true if it couldn't be loaded; Retry can then work
 * @property isSignedOut true when nobody is signed in anymore (e.g. the session expired): Retry
 *   can't work, the caregiver has to sign in again
 * @property isConfirmed true when the server confirmed whether the caregiver is in each circle. An
 *   empty list only means they follow nobody once it is confirmed: right after joining again, the
 *   device can still remember the removal, a patient who can't be read isn't known either, and an
 *   empty list of circles from the cache may miss one joined on another phone
 * @property news what each person who shares their journal logged last, by uid; nobody else has one
 * @property today the caregiver's day, for "Yesterday" in the news
 */
data class WhoseCareUiState(
    val patients: List<FollowedPatient> = emptyList(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val isSignedOut: Boolean = false,
    val isConfirmed: Boolean = false,
    val news: Map<String, PatientNews> = emptyMap(),
    val today: LocalDate? = null,
) {
  /**
   * Which entries are new, as one key that changes when another one arrives, so the header's button
   * glows once for each; `null` when nothing is.
   */
  val newsKey: String?
    get() =
        news
            .filterValues { it.isNew }
            .map { (uid, news) -> "$uid/${news.latest.entry.id}" }
            .sorted()
            .joinToString(" ")
            .ifEmpty { null }

  /** Whether someone logged something since the caregiver last had their day open. */
  val hasNew: Boolean
    get() = newsKey != null
}

/**
 * Lists the patients a caregiver follows, with what each one shares with them (US-20), so they can
 * choose whose care to look at. The sections come from the caregiver's own member document in each
 * patient's circle, which a caregiver can read; a patient who removed them is left out.
 *
 * For each person who shares their journal, it also follows what they logged last, and whether that
 * is new: logged after the caregiver last had their day open ([show]), as the phone remembers in
 * [lastOpened].
 */
class WhoseCareViewModel(
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val inviteRepository: CareCircleInviteRepository =
        CareCircleInviteRepositoryProvider.repository,
    private val circleRepository: CareCircleRepository = CareCircleRepositoryProvider.repository,
    private val symptomRepository: SymptomRepository = SymptomRepositoryProvider.repository,
    private val lastOpened: LastOpened = LastOpenedProvider.store,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

  private val _uiState = MutableStateFlow(WhoseCareUiState())
  val uiState: StateFlow<WhoseCareUiState> = _uiState.asStateFlow()

  private var observation: Job? = null
  private var newsObservation: Job? = null

  /** The person whose day the caregiver has open, if any: what they log is seen at once. */
  private val shown = MutableStateFlow<String?>(null)

  init {
    load()
  }

  /** Starts (or, after an error, restarts) observing the followed patients. */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun load() {
    observation?.cancel()
    newsObservation?.cancel()
    _uiState.update { it.copy(isLoading = true, hasError = false, isSignedOut = false) }
    val uid = authRepository.currentUser?.uid
    if (uid == null) {
      // Still loading on screen until the caregiver is sent to sign in
      _uiState.update { it.copy(isSignedOut = true) }
      return
    }
    observation = viewModelScope.launch {
      inviteRepository
          .observeJoinedCircles(uid)
          // An empty list from the cache only: maybe a circle this phone doesn't know about yet.
          // Otherwise where the list came from doesn't matter: each patient answers for itself
          .map { it.circles to (it.circles.isEmpty() && it.isFromCache) }
          .distinctUntilChanged()
          .flatMapLatest { (circles, emptyFromCache) ->
            followed(circles, uid).map { answers -> answers to emptyFromCache }
          }
          .catch { e ->
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to load the followed patients", e)
            _uiState.update { it.copy(isLoading = false, hasError = true) }
          }
          .collect { (answers, emptyFromCache) ->
            _uiState.update {
              it.copy(
                  patients =
                      answers
                          .filterIsInstance<Answer.Followed>()
                          .map { answer -> answer.patient }
                          .sortedBy { patient -> patient.firstName.lowercase() },
                  isLoading = false,
                  isConfirmed =
                      !emptyFromCache && answers.none { answer -> answer == Answer.Unknown },
              )
            }
          }
    }
    newsObservation = viewModelScope.launch { observeNews(uid) }
  }

  /**
   * The caregiver now has [patientUid]'s day open, or none with `null`: what that person logged
   * until now is seen, and so is what the one open before logged while it was.
   */
  fun show(patientUid: String?) {
    val previous = shown.value
    shown.value = patientUid
    val caregiverUid = authRepository.currentUser?.uid ?: return
    val now = clock.instant()
    setOfNotNull(previous, patientUid).forEach { lastOpened.markOpened(caregiverUid, it, now) }
  }

  /** Follows what each person who shares their journal logged last, and whether it is new. */
  @OptIn(ExperimentalCoroutinesApi::class)
  private suspend fun observeNews(caregiverUid: String) {
    val latest =
        _uiState
            .map { state -> state.patients.filter { CarePermission.SYMPTOMS in it.sharedSections } }
            .distinctUntilChanged()
            .flatMapLatest { sharing ->
              if (sharing.isEmpty()) flowOf(emptyMap())
              else
                  combine(sharing.map { p -> latestOf(p.uid, caregiverUid).map { p.uid to it } }) {
                    it.mapNotNull { (uid, entry) -> entry?.let { uid to entry } }.toMap()
                  }
            }
    val opened: Flow<Map<String, Instant>?> =
        lastOpened
            .observe(caregiverUid)
            .map<Map<String, Instant>, Map<String, Instant>?> { it }
            .catch { e ->
              if (e is CancellationException) throw e
              // Unknown: nothing shows as new rather than everything
              Log.w(TAG, "Couldn't read when the days were last opened", e)
              emit(null)
            }
    combine(latest, opened, shown) { news, openedAt, shownUid ->
          seeNews(caregiverUid, news, openedAt, shownUid)
          news.mapValues { (uid, entry) ->
            val since = openedAt?.get(uid)
            PatientNews(
                entry.toLatestEntry(zoneId),
                isNew = uid != shownUid && since != null && entry.loggedAt.isAfter(since),
            )
          }
        }
        .collect { news ->
          _uiState.update { it.copy(news = news, today = LocalDate.now(clock.withZone(zoneId))) }
        }
  }

  /**
   * Remembers as seen what the caregiver didn't miss: what the person whose day is open logs, and
   * what someone logged before the phone first heard of them, so following someone doesn't start
   * with a dot.
   */
  private fun seeNews(
      caregiverUid: String,
      news: Map<String, SymptomEntry>,
      openedAt: Map<String, Instant>?,
      shownUid: String?,
  ) {
    if (openedAt == null) return
    news.forEach { (uid, entry) ->
      val since = openedAt[uid]
      if (since == null || (uid == shownUid && entry.loggedAt.isAfter(since))) {
        // Never before the entry, so a phone clock behind the server's can't make it new again
        lastOpened.markOpened(caregiverUid, uid, maxOf(clock.instant(), entry.loggedAt))
      }
    }
  }

  /** [patientUid]'s newest entry that [caregiverUid] didn't write, live; none if unreadable. */
  private fun latestOf(patientUid: String, caregiverUid: String): Flow<SymptomEntry?> =
      symptomRepository
          .observeSymptoms(patientUid)
          .map { entries ->
            entries.filter { it.addedBy != caregiverUid }.maxByOrNull { it.loggedAt }
          }
          .distinctUntilChanged()
          .catch { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "Couldn't read what $patientUid logged; no news", e)
            emit(null)
          }

  /** What each circle's patient answers about [caregiverUid], live. */
  private fun followed(circles: List<JoinedCareCircle>, caregiverUid: String): Flow<List<Answer>> =
      if (circles.isEmpty()) flowOf(emptyList())
      else combine(circles.map { patientOf(it, caregiverUid) }) { it.toList() }

  /**
   * Whether [circle]'s patient still has the caregiver in their circle, with what they share. A
   * patient whose answer isn't known (not confirmed by the server, or unreadable) is left out of
   * the list, but doesn't count as a removal.
   */
  private fun patientOf(circle: JoinedCareCircle, caregiverUid: String): Flow<Answer> =
      circleRepository
          .observeMembership(circle.patientUid, caregiverUid)
          .map { membership ->
            when (membership) {
              is CareCircleMembership.Member ->
                  Answer.Followed(
                      FollowedPatient(
                          circle.patientUid,
                          circle.patientFirstName,
                          membership.member.permissions,
                      )
                  )
              CareCircleMembership.NotMember -> Answer.Removed
              CareCircleMembership.Unconfirmed -> Answer.Unknown
            }
          }
          .catch { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "Couldn't read the access to ${circle.patientUid}; left out", e)
            emit(Answer.Unknown)
          }

  /** What one patient answers: still followed, removed the caregiver, or not known yet. */
  private sealed interface Answer {
    data class Followed(val patient: FollowedPatient) : Answer

    data object Removed : Answer

    data object Unknown : Answer
  }

  private companion object {
    const val TAG = "WhoseCareViewModel"
  }
}

/** When it was logged: the server's time once it has it, when it was felt until then. */
private val SymptomEntry.loggedAt: Instant
  get() = createdAt ?: occurredAt
