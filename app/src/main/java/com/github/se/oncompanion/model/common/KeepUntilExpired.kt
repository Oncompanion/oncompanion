// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.common

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

/**
 * Keeps only the items that haven't expired, and removes each one the moment it expires, while the
 * flow is collected. The database sends nothing when an item expires: without this, an open screen
 * would keep showing it until something else changes.
 *
 * @param clock the current time
 * @param expiry when an item expires, or `null` if it never does (it is then always kept)
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<List<T>>.keepUntilExpired(
    clock: () -> Instant,
    expiry: (T) -> Instant?,
): Flow<List<T>> = transformLatest { all ->
  // Emit what hasn't expired now, then wait until the next one does; a new list restarts this
  while (true) {
    val now = clock()
    val kept = all.filter { item -> expiry(item)?.let { it > now } ?: true }
    emit(kept)
    val nextExpiry = kept.mapNotNull(expiry).minOrNull() ?: break
    delay(Duration.between(now, nextExpiry).toMillis().coerceAtLeast(1))
  }
}
