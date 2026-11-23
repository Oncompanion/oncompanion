// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.notification

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
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepositoryProvider
import com.github.se.oncompanion.model.medication.Prescription
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
 * @property prescription the prescription it is about, read live; `null` once it is gone (deleted
 *   or rejected), so the card never offers to open it
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
              if (uid == null) flowOf(CircleSheetUiState.Error) else circleOf(uid)
            }
            .collect { _uiState.value = it }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // A refused read: showing part of the circle would hide what failed
        _uiState.value = CircleSheetUiState.Error
      }
    }
  }

  private fun circleOf(uid: String): Flow<CircleSheetUiState> =
      combine(
          notificationRepository.observeRecent(uid),
          careCircleRepository.observeMembers(uid),
          inviteRepository.observePendingInvites(uid),
          medicationRepository.observePrescriptions(uid),
          today,
      ) { notifications, members, invites, prescriptions, today ->
        toUiState(notifications, members, invites, prescriptions, today)
      }

  private fun toUiState(
      notifications: List<CircleNotification>,
      members: List<CareCircleMember>,
      invites: List<CareCircleInvite>,
      prescriptions: List<Prescription>,
      today: LocalDate,
  ): CircleSheetUiState {
    // An expired invitation can't be used any more: that person isn't coming
    val invited = invites.filterNot { it.isExpired(clock.instant()) }
    if (members.isEmpty() && invited.isEmpty() && notifications.isEmpty()) {
      return CircleSheetUiState.NobodyYet
    }
    val byId = prescriptions.filterNot { it.isRejected }.associateBy { it.id }
    val days =
        notifications
            .groupBy { it.createdAt.atZone(clock.zone).toLocalDate() }
            .map { (date, sameDay) ->
              CircleDay(
                  date,
                  sameDay.map {
                    CircleCard(it, it.createdAt.atZone(clock.zone).toLocalTime(), byId[it.itemId])
                  },
              )
            }
    return CircleSheetUiState.Circle(
        joined = members.map { it.firstName },
        invited = invited.map { it.details.firstName },
        today = today,
        days = days,
    )
  }
}
