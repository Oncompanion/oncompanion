// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences

/**
 * Simulates a failed disk commit and records any unsafe asynchronous fallback by production code.
 */
class FailedCommitContext(base: Context) : ContextWrapper(base) {
  var applied = false
    private set

  override fun getApplicationContext(): Context = this

  override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
    val actual = super.getSharedPreferences(name, mode)
    return object : SharedPreferences by actual {
      override fun edit(): SharedPreferences.Editor {
        val editor = actual.edit()
        return object : SharedPreferences.Editor by editor {
          override fun commit() = false

          override fun apply() {
            applied = true
          }
        }
      }
    }
  }
}
