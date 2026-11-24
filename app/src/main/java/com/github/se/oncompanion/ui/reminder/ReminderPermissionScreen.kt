// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.reminder.REMINDER_CHANNEL_ID
import com.github.se.oncompanion.model.reminder.ReminderPermissionHistory
import com.github.se.oncompanion.model.reminder.ReminderPermissionHistoryProvider
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.LakeBanner
import com.github.se.oncompanion.ui.theme.Spacing

/**
 * Explicit taps alone open Android-owned permission UI; saves never depend on its result.
 *
 * @param history optional storage override; otherwise uses the shared device history provider
 */
@Composable
fun rememberReminderPermissionNotice(
    viewModel: ReminderPermissionViewModel,
    history: ReminderPermissionHistory? = null,
): (@Composable () -> Unit)? {
  val context = LocalContext.current
  val requestHistory =
      history ?: remember(context) { ReminderPermissionHistoryProvider.get(context) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var opening by remember { mutableStateOf(false) }
  val launcher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        opening = false
        viewModel.refresh(reminderAccess(context, requestHistory))
      }
  LifecycleResumeEffect(viewModel, requestHistory) {
    opening = false
    viewModel.refresh(reminderAccess(context, requestHistory))
    onPauseOrDispose {}
  }
  if (!state.eligible || state.access == ReminderAccess.READY) return null
  return {
    ReminderPermissionContent(
        state,
        onEnable = {
          if (!opening) {
            opening = true
            // Read again at the tap: permission may have changed while Planning was visible.
            val access = reminderAccess(context, requestHistory)
            if (access == ReminderAccess.OFFER && Build.VERSION.SDK_INT >= 33) {
              requestHistory.markRequested()
              launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
              if (access != ReminderAccess.READY) openReminderSettings(context, access)
              opening = false
              viewModel.refresh(reminderAccess(context, requestHistory))
            }
          }
        },
    )
  }
}

/** Approved neutral banner, reusing the shared responsive message/action component. */
@Composable
fun ReminderPermissionContent(state: ReminderPermissionUiState, onEnable: () -> Unit) {
  if (!state.eligible || state.access == null || state.access == ReminderAccess.READY) return
  val message =
      when (state.access) {
        ReminderAccess.OFFER -> R.string.reminder_permission_offer
        ReminderAccess.APP_BLOCKED -> R.string.reminder_permission_blocked
        ReminderAccess.CHANNEL_BLOCKED -> R.string.reminder_permission_channel
        else -> R.string.reminder_permission_exact
      }
  Column(Modifier.padding(Spacing.lg).testTag(C.Tag.reminder_permission_notice)) {
    LakeBanner(
        stringResource(message),
        actionLabel =
            stringResource(
                if (state.access == ReminderAccess.OFFER) R.string.reminder_permission_enable
                else R.string.reminder_permission_settings
            ),
        onAction = onEnable,
        actionTestTag = C.Tag.reminder_permission_action,
    )
  }
}

internal const val CHANNEL = REMINDER_CHANNEL_ID

internal fun reminderAccess(
    context: Context,
    history: ReminderPermissionHistory = ReminderPermissionHistoryProvider.get(context),
): ReminderAccess {
  val manager = context.getSystemService(NotificationManager::class.java)
  val permissionMissing =
      Build.VERSION.SDK_INT >= 33 &&
          ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
              PackageManager.PERMISSION_GRANTED
  if (permissionMissing || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
    val unasked = !history.requested
    return if (permissionMissing && unasked) ReminderAccess.OFFER else ReminderAccess.APP_BLOCKED
  }
  if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE)
      return ReminderAccess.CHANNEL_BLOCKED
  if (
      Build.VERSION.SDK_INT >= 31 &&
          !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
  )
      return ReminderAccess.EXACT_BLOCKED
  return ReminderAccess.READY
}

internal fun openReminderSettings(context: Context, access: ReminderAccess) {
  val intent =
      when (access) {
        ReminderAccess.CHANNEL_BLOCKED ->
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
        ReminderAccess.EXACT_BLOCKED ->
            if (Build.VERSION.SDK_INT >= 31)
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    "package:${context.packageName}".toUri(),
                )
            else
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
      }
  try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  } catch (_: ActivityNotFoundException) {
    // OEMs may omit a specific settings page; the app details page still exposes its permissions.
    context.startActivity(
        Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${context.packageName}".toUri(),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
  }
}
