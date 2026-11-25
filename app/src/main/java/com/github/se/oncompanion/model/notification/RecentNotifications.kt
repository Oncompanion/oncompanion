// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.notification

import com.github.se.oncompanion.model.common.keepUntilExpired
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Keeps only the notifications younger than [CircleNotification.KEPT_FOR], and removes each one the
 * moment it turns that old, while the flow is collected. The database sends nothing when a
 * notification ages: without this, an open feed would keep it until Firestore's TTL deletes it, and
 * offline even longer.
 *
 * @param clock the current time
 */
fun Flow<List<CircleNotification>>.keepRecent(
    clock: () -> Instant
): Flow<List<CircleNotification>> =
    keepUntilExpired(clock) { it.createdAt.plus(CircleNotification.KEPT_FOR) }
