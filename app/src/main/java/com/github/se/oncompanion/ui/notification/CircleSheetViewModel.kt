// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.notification

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleInvite
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryProvider
import com.github.se.oncompanion.model.common.keepUntilExpired
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepositoryProvider
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.medication.PrescriptionConfirmation
import com.github.se.oncompanion.model.notification.CircleNotification
import com.github.se.oncompanion.model.notification.NotificationRepository
import com.github.se.oncompanion.model.notification.NotificationRepositoryProvider
import com.github.se.oncompanion.ui.common.currentDay
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
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
import kotlinx.coroutines.launch

/**
 * One notification of the "Your circle" sheet, with what it is about as it is now.
 *
 * @property time when it arrived, in the phone's time zone
 * @property prescription the prescription it is about, read live: its [Prescription.confirmation]
 *   says whether it waits for the patient, was confirmed or was rejected (a rejected one is read
 *   from where it was moved, with its medications). `null` once it is gone: deleted, or rejected
 *   and then deleted by the caregiver who added it
 */
data class CircleCard(
    val notification: CircleNotification,
    val time: LocalTime,
    val prescription: Prescription?,
)

/** The notifications that arrived on [date], newest first. */
data class CircleDay(val date: LocalDate, val cards: List<CircleCard>)

/** What the "Your circle" sheet shows (Figma M3: "Overview · Patient · Care circle"). */
sealed interface CircleSheetUiState {
  /** Waiting for the first result, from the local cache or the server. */
  data object Loading : CircleSheetUiState

  /**
   * Nobody is signed in, or a read was refused. Being offline is not an error: the cache answers.
   */
  data object Error : CircleSheetUiState

  /** No one in the circle, nobody invited, and nothing received ("No one in your circle yet"). */
  data object NobodyYet : CircleSheetUiState

  /**
   * The circle and what its members did for the patient.
   *
   * @property joined the first names of the members, by name
   * @property invited the first names of the people invited who haven't joined yet
   * @property today the day the sheet calls "Today"
   * @property days the last week's notifications by day, newest first; none today means "Nothing
   *   new"
   */
  data class Circle(
      val joined: List<String>,
      val invited: List<String>,
      val today: LocalDate,
      val days: List<CircleDay>,
  ) : CircleSheetUiState
}

/** Whether something in the sheet is unseen: the sailboat then shows its dot. */
val CircleSheetUiState.hasUnseen: Boolean
  get() =
      this is CircleSheetUiState.Circle &&
          days.any { day -> day.cards.any { !it.notification.seen } }

/**
 * Tells the patient what their care circle did for them (US-50, #457): the "Your circle" sheet and
 * the dot on the sailboat button that opens it. It follows the signed-in account, so another
 * account never sees the previous one's circle.
 *
 * @param clock the time zone the days and times are shown in, and the day the sheet calls "Today"
 */
class CircleSheetViewModel(
    private val notificationRepository: NotificationRepository =
        NotificationRepositoryProvider.repository,
    private val careCircleRepository: CareCircleRepository =
        CareCircleRepositoryProvider.repository,
    private val inviteRepository: CareCircleInviteRepository =
        CareCircleInviteRepositoryProvider.repository,
    private val medicationRepository: MedicationRepository =
        MedicationRepositoryProvider.repository,
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

  private val _uiState = MutableStateFlow<CircleSheetUiState>(CircleSheetUiState.Loading)
  /**
   * What the sheet shows: [CircleSheetUiState.Loading] until every read answers, then the circle of
   * the signed-in account ([CircleSheetUiState.Circle] or [CircleSheetUiState.NobodyYet]), or
   * [CircleSheetUiState.Error] when a read fails or nobody is signed in.
   */
  val uiState: StateFlow<CircleSheetUiState> = _uiState.asStateFlow()

  private var observeJob: Job? = null

  /** The day the sheet calls "Today", kept right by [followToday]. */
  private val today = MutableStateFlow(LocalDate.now(clock))

  init {
    observe()
  }

  /**
   * Keeps "Today" and "Yesterday" right while the sheet is shown: updates the date now, then at
   * each midnight, until the returned job is cancelled. The sheet runs it each time it comes back,
   * so a phone that slept past midnight (where a waiting timer stops) is right at once.
   */
  fun followToday(): Job = viewModelScope.launch { currentDay(clock).collect { today.value = it } }

  /** Reads everything again after an [CircleSheetUiState.Error]. */
  fun retry() {
    observe()
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun observe() {
    observeJob?.cancel()
    _uiState.value = CircleSheetUiState.Loading
    observeJob = viewModelScope.launch {
      try {
        // A new account starts again from its own data; signed out, nothing of the last one stays
        authRepository
            .observeCurrentUser()
            .map { it?.uid }
            .distinctUntilChanged()
            .flatMapLatest { uid ->
              if (uid == null) flowOf(CircleSheetUiState.Error)
              // Caught for this account only: the next one signed in still reads its own circle
              else circleOf(uid).catch { emit(failed("Couldn't read the circle", it)) }
            }
            .collect { _uiState.value = it }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _uiState.value = failed("Couldn't follow the signed-in user", e)
      }
    }
  }

  /** A refused read: showing part of the circle would hide what failed. */
  private fun failed(message: String, e: Throwable): CircleSheetUiState {
    // The type only: a Firestore error's message can name the document path (AGENTS.md)
    Log.w(TAG, "$message: ${e.javaClass.simpleName}")
    return CircleSheetUiState.Error
  }

  private fun circleOf(uid: String): Flow<CircleSheetUiState> =
      combine(
          notificationRepository.observeRecent(uid),
          careCircleRepository.observeMembers(uid),
          // An expired invitation can't be used anymore: that person isn't coming. It leaves the
          // moment it expires, even while the sheet stays open
          inviteRepository.observePendingInvites(uid).keepUntilExpired(clock::instant) {
            it.expiresAt
          },
          prescriptionsOf(uid),
          today,
      ) { notifications, members, invites, prescriptions, today ->
        toUiState(notifications, members, invites, prescriptions, today)
      }

  /**
   * The prescriptions of [uid] by ID, the rejected ones included: a rejected prescription leaves
   * the prescriptions for a collection of its own, where it keeps its medications, marked
   * [PrescriptionConfirmation.REJECTED]. Both are read in step, so a card never shows one being
   * rejected as gone.
   */
  private fun prescriptionsOf(uid: String): Flow<Map<String, Prescription>> =
      medicationRepository.observePrescriptionsWithRejected(uid).map { all ->
        all.associateBy { it.id }
      }

  private fun toUiState(
      notifications: List<CircleNotification>,
      members: List<CareCircleMember>,
      invites: List<CareCircleInvite>,
      prescriptions: Map<String, Prescription>,
      today: LocalDate,
  ): CircleSheetUiState {
    if (members.isEmpty() && invites.isEmpty() && notifications.isEmpty()) {
      return CircleSheetUiState.NobodyYet
    }
    val days =
        notifications
            .groupBy { it.createdAt.atZone(clock.zone).toLocalDate() }
            .map { (date, sameDay) ->
              CircleDay(
                  date,
                  sameDay.map {
                    CircleCard(
                        it,
                        it.createdAt.atZone(clock.zone).toLocalTime(),
                        prescriptions[it.itemId],
                    )
                  },
              )
            }
    return CircleSheetUiState.Circle(
        joined = members.map { it.firstName },
        invited = invites.map { it.details.firstName },
        today = today,
        days = days,
    )
  }

  private companion object {
    const val TAG = "CircleSheetViewModel"
  }
}
