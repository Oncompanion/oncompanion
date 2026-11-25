// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.utils

import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/** How long a security rules test waits for the Firestore emulator to answer one operation. */
private val RULES_TIMEOUT = 10.seconds

/** Waits for [task], which the rules must allow, and returns its result. */
suspend fun <T> allowed(task: Task<T>): T = withTimeout(RULES_TIMEOUT) { task.await() }

/**
 * A creation time a client tries to choose instead of the server's, to check the rules refuse it.
 * Never the current time: the emulator keeps milliseconds and the device's clock is off by some, so
 * `Timestamp.now()` can be exactly the server's time and the write is then rightly accepted (#522).
 */
val forgedTime = Timestamp(Instant.parse("2000-01-01T00:00:00Z"))

/**
 * Waits for [task] and fails unless the rules rejected it with PERMISSION_DENIED. [what] names the
 * case in the failure, for a test that checks several in a loop.
 */
suspend fun denied(task: Task<*>, what: String = "the operation") {
  try {
    withTimeout(RULES_TIMEOUT) { task.await() }
    fail("Expected PERMISSION_DENIED but $what succeeded")
  } catch (e: FirebaseFirestoreException) {
    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
  }
}
