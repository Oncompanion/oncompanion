// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.notification

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory [NotificationRepository] for ViewModel unit tests (no Firebase).
 *
 * Follows the contract of [NotificationRepository]:
 * - [notify] requires [CircleNotification.isValid] (else [IllegalArgumentException]), stores it
 *   unseen with `createdAt = clock()`, and ignores a notification already sent.
 * - [observeRecent] emits the notifications of the last [CircleNotification.KEPT_FOR] before
 *   [clock], newest first, then every change.
 * - [markSeen] marks the given IDs seen and ignores those that don't exist.
 *
 * Test hooks: [deliver] stores a notification exactly as given (e.g. old or already seen),
 * replacing one with the same ID; [notifyError], when set, is thrown by [notify], which then stores
 * nothing.
 *
 * @param clock the current time: the 7-day window and the time a notification is sent
 */
class FakeNotificationRepository(private val clock: () -> Instant = Instant::now) :
    NotificationRepository {

  // uid -> notification ID -> notification
  private val store = MutableStateFlow<Map<String, Map<String, CircleNotification>>>(emptyMap())

  /** When set, [notify] throws it, as a write failing on the device would. */
  var notifyError: Exception? = null

  /** Stores [notification] for [uid] as given. */
  fun deliver(uid: String, notification: CircleNotification) {
    store.update { it + (uid to (it[uid].orEmpty() + (notification.id to notification))) }
  }

  /** The notifications stored for [uid], newest first, whatever their age. */
  fun notificationsOf(uid: String): List<CircleNotification> =
      store.value[uid].orEmpty().values.sortedByDescending { it.createdAt }

  override suspend fun notify(recipientUid: String, notification: CircleNotification) {
    notifyError?.let { throw it }
    require(notification.isValid()) { "Invalid notification" }
    if (store.value[recipientUid]?.containsKey(notification.id) == true) return
    deliver(recipientUid, notification.copy(createdAt = clock(), seen = false))
  }

  override fun observeRecent(uid: String): Flow<List<CircleNotification>> =
      store
          .map { all -> all[uid].orEmpty().values.sortedByDescending { it.createdAt } }
          .keepRecent(clock)

  override suspend fun markSeen(uid: String, ids: Collection<String>) {
    store.update { all ->
      val mine = all[uid] ?: return@update all
      all + (uid to mine.mapValues { (id, n) -> if (id in ids) n.copy(seen = true) else n })
    }
  }
}
