// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview.caregiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.overview.CardBadge
import com.github.se.oncompanion.ui.overview.CardLink
import com.github.se.oncompanion.ui.overview.DayCard
import com.github.se.oncompanion.ui.overview.Eyebrow
import com.github.se.oncompanion.ui.overview.LatestEntry
import com.github.se.oncompanion.ui.overview.entrySummary
import com.github.se.oncompanion.ui.symptom.dayLabel
import com.github.se.oncompanion.ui.symptom.symptomTimeFormatter
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.cardDetail
import com.github.se.oncompanion.ui.theme.cardTitle
import java.time.LocalDate

/**
 * "Journal" (Figma M3, "Alex's day"): when the patient last logged how they felt, and what, e.g.
 * "Yesterday at 18:10" and "Nausea 7/10 · Fatigue 6/10"; "No entries yet" while their journal is
 * empty. "Full journal ›" opens the journal.
 *
 * @param latest the patient's newest entry, `null` while their journal is empty
 * @param today the day the Overview shows, for "Today" and "Yesterday"
 */
@Composable
internal fun JournalCard(latest: LatestEntry?, today: LocalDate, onOpenJournal: () -> Unit) {
  DayCard(Modifier.testTag(C.Tag.caregiver_overview_journal)) {
    Row(
        Modifier.padding(
            start = Spacing.lg,
            end = Spacing.sm,
            top = Spacing.md,
            bottom = Spacing.md,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
      CardBadge(R.drawable.ic_heart)
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Eyebrow(stringResource(R.string.caregiver_overview_journal), Modifier.weight(1f))
          CardLink(
              stringResource(R.string.caregiver_overview_full_journal),
              onOpenJournal,
              Modifier.testTag(C.Tag.caregiver_overview_full_journal),
          )
        }
        Text(
            latest?.let { loggedAt(it, today) }
                ?: stringResource(R.string.caregiver_overview_journal_empty),
            style = MaterialTheme.typography.cardTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        latest?.let {
          Text(
              entrySummary(it),
              style = MaterialTheme.typography.cardDetail,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

/** "Today at 08:30", "Yesterday at 18:10" or "Mon 16 Nov at 18:10", as the journal says days. */
@Composable
private fun loggedAt(latest: LatestEntry, today: LocalDate): String =
    stringResource(
        R.string.caregiver_overview_journal_at,
        dayLabel(latest.at.toLocalDate(), today),
        latest.at.format(symptomTimeFormatter),
    )
