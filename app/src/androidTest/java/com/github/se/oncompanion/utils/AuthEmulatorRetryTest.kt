// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.utils

import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises retry policy with virtual time; no dropped real connections or emulator timing needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthEmulatorRetryTest {
  @Test
  fun successReturnsImmediatelyIncludingNull(): Unit = runTest {
    assertEquals("uid", retryAuthEmulator("Sign in") { "uid" })
    assertEquals(null, retryAuthEmulator<String?>("Sign in") { null })
    assertEquals(0L, currentTime)
  }

  @Test
  fun transientFailuresRecoverWithinThreeAttempts(): Unit = runTest {
    val failures =
        listOf(
            FirebaseNetworkException("Unavailable"),
            FirebaseException("Internal", IOException("Connection reset")),
            FirebaseException("An internal error [ unexpected end of stream on okhttp.Address ]"),
            FirebaseException("Internal", IllegalStateException("Transport", IOException())),
        )
    for (failure in failures) {
      var calls = 0
      val start = currentTime
      assertEquals(
          "uid",
          retryAuthEmulator("Sign in") {
            if (++calls < 3) throw failure
            "uid"
          },
      )
      assertEquals(3, calls)
      assertEquals(500L, currentTime - start)
    }
  }

  @Test
  fun exhaustedRetriesReportOperationAndCountWithoutSdkMessage(): Unit = runTest {
    var calls = 0
    val failure = runCatching {
      retryAuthEmulator("Create account") {
        calls++
        throw FirebaseNetworkException("secret@example.com")
      }
    }
        .exceptionOrNull()
    assertTrue(failure is AssertionError)
    assertTrue(failure!!.message.orEmpty().contains("Create account failed after 3 attempts"))
    assertFalse(failure.message.orEmpty().contains("secret@example.com"))
    assertEquals(3, calls)
    assertEquals(500L, currentTime)
  }

  @Test
  fun permanentErrorsAndAssertionsFailWithoutRetry(): Unit = runTest {
    val failures =
        listOf(
            FirebaseException("Internal error without a transport cause"),
            FirebaseAuthException("ERROR_WRONG_PASSWORD", "unexpected end of stream"),
            FirebaseAuthUserCollisionException("ERROR_EMAIL_ALREADY_IN_USE", "Already exists"),
            IllegalArgumentException("Invalid input"),
            AssertionError("Expected a signed-in user"),
            CancellationException("Cancelled"),
        )
    for (failure in failures) {
      var calls = 0
      val caught = runCatching {
        retryAuthEmulator("Sign in") {
          calls++
          throw failure
        }
      }
          .exceptionOrNull()
      assertSame(failure, caught)
      assertEquals(1, calls)
    }
    assertEquals(0L, currentTime)
  }

  @Test
  fun oneDeadlineBoundsHungCallsAndRetryDelays(): Unit = runTest {
    var calls = 0
    val failure = runCatching {
      retryAuthEmulator("Sign in") {
        if (++calls == 1) throw FirebaseNetworkException("Unavailable")
        awaitCancellation()
      }
    }
        .exceptionOrNull()
    assertTrue(failure is AssertionError)
    assertTrue(failure!!.message.orEmpty().contains("timed out after 2 attempts"))
    assertEquals(10_000L, currentTime)
  }

  @Test
  fun parentTimeoutIsNotReplacedByHelperFailure(): Unit = runTest {
    val failure = runCatching {
      withTimeout(100) { retryAuthEmulator("Sign in") { awaitCancellation() } }
    }
        .exceptionOrNull()
    assertTrue(failure is CancellationException)
    assertEquals(100L, currentTime)
  }

  @Test
  fun lostCreateResponseRecoversSameAccount(): Unit = runTest {
    var creates = 0
    var signIns = 0
    val uid =
        createEmulatorAccount(
            create = {
              if (++creates == 1) throw FirebaseNetworkException("Response lost")
              throw FirebaseAuthUserCollisionException(
                  "ERROR_EMAIL_ALREADY_IN_USE",
                  "Already exists",
              )
            },
            signIn = {
              signIns++
              "original-uid"
            },
        )
    assertEquals("original-uid", uid)
    assertEquals(2, creates)
    assertEquals(1, signIns)
  }

  @Test
  fun recoverySignInRetriesWithoutAnotherSignup(): Unit = runTest {
    var creates = 0
    var signIns = 0
    val uid =
        createEmulatorAccount(
            create = {
              if (++creates == 1) throw FirebaseNetworkException("Response lost")
              throw FirebaseAuthUserCollisionException(
                  "ERROR_EMAIL_ALREADY_IN_USE",
                  "Already exists",
              )
            },
            signIn = {
              if (++signIns == 1) throw FirebaseNetworkException("Recovery response lost")
              "original-uid"
            },
        )
    assertEquals("original-uid", uid)
    assertEquals(2, creates)
    assertEquals(2, signIns)
    assertEquals(500L, currentTime)
  }

  @Test
  fun freshCreateDoesNotSignInAndFirstCollisionFails(): Unit = runTest {
    assertEquals(
        "fresh-uid",
        createEmulatorAccount({ "fresh-uid" }, { error("Unexpected sign-in") }),
    )
    val collision =
        FirebaseAuthUserCollisionException("ERROR_EMAIL_ALREADY_IN_USE", "Already exists")
    val failure = runCatching {
      createEmulatorAccount(
          { throw collision },
          { error("Must not recover a preexisting account") },
      )
    }
        .exceptionOrNull()
    assertSame(collision, failure)
  }
}
