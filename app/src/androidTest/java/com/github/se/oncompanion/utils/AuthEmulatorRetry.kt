// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.utils

import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private const val AUTH_ATTEMPTS = 3
private const val AUTH_TIMEOUT_MS = 10_000L
private const val AUTH_RETRY_DELAY_MS = 250L

/** Creates one account, recovering its identity if a successful creation lost its response. */
internal suspend fun createEmulatorAccount(
    create: suspend () -> String,
    signIn: suspend () -> String,
): String =
    retryAuthEmulator("Create account") { attempt ->
      try {
        create()
      } catch (error: FirebaseAuthUserCollisionException) {
        // Only a retry may recover an account: a collision on the first call is a real failure.
        if (attempt == 1) throw error
        signIn()
      }
    }

/** Retries only transient Auth transport failures, within one deadline shared by all attempts. */
internal suspend fun <T> retryAuthEmulator(
    operation: String,
    block: suspend (attempt: Int) -> T,
): T {
  var attempts = 0
  val result =
      withTimeoutOrNull(AUTH_TIMEOUT_MS) {
        repeat(AUTH_ATTEMPTS) {
          attempts++
          try {
            return@withTimeoutOrNull Result.success(block(attempts))
          } catch (error: FirebaseException) {
            if (!error.isTransportFailure()) throw error
            if (attempts == AUTH_ATTEMPTS) {
              // Avoid SDK messages here: they can contain the account's email or credentials.
              throw AssertionError(
                  "Auth emulator $operation failed after $attempts attempts (${error.javaClass.simpleName})"
              )
            }
          }
          delay(AUTH_RETRY_DELAY_MS)
        }
        error("Auth retry loop finished without a result")
      }
  if (result == null) {
    throw AssertionError("Auth emulator $operation timed out after $attempts attempts (10 seconds)")
  }
  return result.getOrThrow()
}

private fun FirebaseException.isTransportFailure(): Boolean {
  // Credential, account and permission errors must not become retries, even with network-like text.
  if (this is FirebaseAuthException) return false
  if (this is FirebaseNetworkException) return true
  return generateSequence(this as Throwable) { it.cause }
      .any {
        it is IOException ||
            it.message.orEmpty().contains("unexpected end of stream", ignoreCase = true)
      }
}
