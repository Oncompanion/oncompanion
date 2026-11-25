// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.notification

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.medication.PrescriptionConfirmation
import com.github.se.oncompanion.model.medication.PrescriptionConfirmation.PENDING
import com.github.se.oncompanion.model.notification.CircleNotification
import com.github.se.oncompanion.model.notification.NotificationKind
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.colorAt
import com.github.se.oncompanion.ui.navigation.Screen
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class CircleNotificationCardTest {

  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun string(id: Int) = context.getString(id)

  private val opened = mutableListOf<String>()

  private fun prescription(
      confirmation: PrescriptionConfirmation,
      prescribedBy: String? = "Dr. Martin",
  ): Prescription {
    val day = LocalDate.of(2026, 10, 3)
    return Prescription(
        id = "p1",
        prescribedBy = prescribedBy,
        prescribedOn = day,
        medications =
            listOf("Ondansetron", "Dexamethasone").mapIndexed { i, name ->
              Medication(id = "p1-$i", prescriptionId = "p1", name = name, startDate = day)
            },
        confirmation = confirmation,
    )
  }

  private val notification =
      CircleNotification(
          kind = NotificationKind.PRESCRIPTION_ADDED,
          itemId = "p1",
          actorUid = "marc",
          actorFirstName = "Marc",
          createdAt = Instant.EPOCH,
      )

  /** Shows the card of [by]'s notification at [time], about [prescription] as it is now. */
  private fun show(
      prescription: Prescription?,
      time: LocalTime = LocalTime.of(8, 5),
      by: String = "Marc",
  ) {
    compose.setContent {
      OncompanionTheme {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          CircleNotificationCard(
              CircleCard(notification.copy(actorFirstName = by), time, prescription),
              { opened += it },
          )
        }
      }
    }
  }

  private val link = C.Tag.circleSheetOpen(notification.id)

  @Test
  fun aWaitingPrescription_saysWhoAndWhen_andOpensItsReview() {
    show(prescription(PrescriptionConfirmation.PENDING))

    compose.onNodeWithText("M").assertIsDisplayed()
    // The time as on the Overview, whatever the phone's 12- or 24-hour setting
    compose.onNodeWithText("Marc · 08:05 · added").assertIsDisplayed()
    compose.onNodeWithText("Dr. Martin").assertIsDisplayed()
    compose.onNodeWithText(string(R.string.circle_sheet_to_confirm)).assertIsDisplayed()
    compose.onNodeWithText("Ondansetron, Dexamethasone").assertIsDisplayed()
    // A full row, as in the Figma (44 dp there, Android's 48 dp touch target here)
    compose.onNodeWithTag(link).assertHeightIsAtLeast(48.dp).performClick()
    assertEquals(listOf(Screen.prescriptionEdit("p1")), opened)
  }

  @Test
  fun aConfirmedPrescription_withoutPrescriber_opensItsPage() {
    show(prescription(PrescriptionConfirmation.CONFIRMED, prescribedBy = " "), LocalTime.of(18, 30))

    compose.onNodeWithText("Marc · 18:30 · added").assertIsDisplayed()
    compose.onNodeWithText(string(R.string.circle_sheet_prescription)).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.circle_sheet_confirmed)).assertIsDisplayed()
    compose.onNodeWithTag(link).performClick()
    assertEquals(listOf(Screen.prescriptionDetail("p1")), opened)
  }

  @Test
  fun aRejectedPrescription_keepsItsMedications_withNothingToOpen() {
    show(prescription(PrescriptionConfirmation.REJECTED))

    compose.onNodeWithText(string(R.string.circle_sheet_rejected)).assertIsDisplayed()
    compose.onNodeWithText("Ondansetron, Dexamethasone").assertIsDisplayed()
    compose.onNodeWithTag(link).assertDoesNotExist()
  }

  @Test
  fun aGonePrescription_saysWhatItsPageSays_withNothingToOpen() {
    show(null, by = "sophie")

    // The initial is a capital, whatever case the name was typed in, and TalkBack skips it
    compose
        .onNodeWithText("S")
        .assertIsDisplayed()
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
    compose.onNodeWithText("sophie · 08:05 · added").assertIsDisplayed()
    compose.onNodeWithText(string(R.string.prescription_detail_not_found_title)).assertIsDisplayed()
    compose.onNodeWithTag(link).assertDoesNotExist()
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun onlyAPrescriptionStillWaiting_isOutlinedInDawn() {
    var dawn = Color.Unspecified
    compose.setContent {
      OncompanionTheme {
        dawn = MaterialTheme.colorScheme.secondary
        Column {
          for (confirmation in PrescriptionConfirmation.entries) {
            val item = "p-$confirmation"
            CircleNotificationCard(
                CircleCard(
                    notification.copy(itemId = item),
                    LocalTime.NOON,
                    prescription(confirmation).copy(id = item),
                ),
                {},
            )
          }
        }
      }
    }

    for (confirmation in PrescriptionConfirmation.entries) {
      val id = CircleNotification.idOf(NotificationKind.PRESCRIPTION_ADDED, "p-$confirmation")
      // Halfway down the card's left edge, on its outline
      val edge = compose.onNodeWithTag(C.Tag.circleSheetCard(id)).colorAt(0.5.dp, 40.dp)
      val outlined = abs(edge.red - dawn.red) + abs(edge.green - dawn.green) < 0.05f
      assertEquals("$confirmation outlined in dawn", confirmation == PENDING, outlined)
    }
  }

  @Test
  @Config(qualifiers = "w320dp-h640dp", fontScale = 2f)
  fun largestFont_onASmallPhone_keepsTheStateAndTheLinkWhole() {
    show(prescription(PrescriptionConfirmation.PENDING))

    compose
        .onNodeWithText(string(R.string.circle_sheet_to_confirm))
        .performScrollTo()
        .assertIsFullyVisible()
    compose.onNodeWithTag(link).performScrollTo().assertIsFullyVisible()
  }
}
