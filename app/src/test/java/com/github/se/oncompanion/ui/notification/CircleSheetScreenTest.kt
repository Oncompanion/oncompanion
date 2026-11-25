// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.notification

import android.content.Context
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.FakeCareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.medication.PrescriptionConfirmation
import com.github.se.oncompanion.model.notification.CircleNotification
import com.github.se.oncompanion.model.notification.FakeNotificationRepository
import com.github.se.oncompanion.model.notification.NotificationKind
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.navigation.Screen
import com.github.se.oncompanion.ui.textLayout
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class CircleSheetScreenTest {

  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

  private val today = LocalDate.of(2026, 10, 3)

  private fun prescription(
      id: String,
      prescribedBy: String? = "Dr. Martin",
      confirmation: PrescriptionConfirmation = PrescriptionConfirmation.PENDING,
  ) =
      Prescription(
          id = id,
          prescribedBy = prescribedBy,
          prescribedOn = today,
          medications =
              listOf("Ondansetron", "Dexamethasone").mapIndexed { i, name ->
                Medication(id = "$id-$i", prescriptionId = id, name = name, startDate = today)
              },
          confirmation = confirmation,
      )

  private fun card(
      itemId: String,
      time: LocalTime,
      prescription: Prescription?,
      by: String = "Marc",
  ) =
      CircleCard(
          CircleNotification(
              kind = NotificationKind.PRESCRIPTION_ADDED,
              itemId = itemId,
              actorUid = by.lowercase(),
              actorFirstName = by,
              createdAt = Instant.EPOCH,
          ),
          time,
          prescription,
      )

  private fun id(itemId: String) =
      CircleNotification.idOf(NotificationKind.PRESCRIPTION_ADDED, itemId)

  private val circle =
      CircleSheetUiState.Circle(
          joined = listOf("Marc", "Sophie"),
          invited = listOf("Julie"),
          today = today,
          days =
              listOf(
                  CircleDay(today, listOf(card("p1", LocalTime.of(8, 5), prescription("p1")))),
                  CircleDay(
                      today.minusDays(1),
                      listOf(
                          card(
                              "p2",
                              LocalTime.of(18, 30),
                              prescription("p2", null, PrescriptionConfirmation.CONFIRMED),
                              by = "Sophie",
                          ),
                          card(
                              "p4",
                              LocalTime.of(12, 0),
                              prescription("p4", "Dr. Leroy", PrescriptionConfirmation.REJECTED),
                          ),
                          card("p3", LocalTime.of(9, 0), null),
                      ),
                  ),
              ),
      )

  private var managed = 0
  private var added = 0
  private var retried = 0
  private val opened = mutableListOf<String>()

  private var shown: CircleSheetUiState by mutableStateOf(CircleSheetUiState.Loading)
  private var contentSet = false

  /** Shows [state]; the content is set once per test, as the test rule allows. */
  private fun show(state: CircleSheetUiState) {
    shown = state
    if (contentSet) return
    contentSet = true
    compose.setContent {
      OncompanionTheme {
        CircleSheetContent(
            uiState = shown,
            onManage = { managed++ },
            onAddSomeone = { added++ },
            onOpen = { opened += it },
            onRetry = { retried++ },
        )
      }
    }
  }

  @Test
  fun loading_showsOnlyTheProgress() {
    show(CircleSheetUiState.Loading)
    compose.onNodeWithTag(C.Tag.circle_sheet_loading).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.circle_sheet_manage).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.circle_sheet_error).assertDoesNotExist()
  }

  @Test
  fun error_saysSo_andRetryCallsBack() {
    show(CircleSheetUiState.Error)
    compose.onNodeWithText(string(R.string.care_circle_error)).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.circle_sheet_retry).performClick()
    assertEquals(1, retried)
  }

  @Test
  fun nobodyYet_explains_andAddsSomeone_withoutManage() {
    show(CircleSheetUiState.NobodyYet)
    compose.onNodeWithText(string(R.string.care_circle_empty_title)).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.circle_sheet_nobody_message)).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.circle_sheet_manage).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.circle_sheet_add_someone).performScrollTo().performClick()
    assertEquals(1, added)
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun nobodyYet_drawsTheEmptySeats() {
    var outline = Color.Unspecified
    compose.setContent {
      OncompanionTheme {
        outline = MaterialTheme.colorScheme.outline
        CircleSheetContent(CircleSheetUiState.NobodyYet, {}, {}, {}, {})
      }
    }
    // Only the seats are captured: their dashes are the only outline-colored pixels there
    val pixels = compose.onNodeWithTag(C.Tag.circle_sheet_seats).captureToImage().toPixelMap()
    val drawn =
        (0 until pixels.height).sumOf { y ->
          (0 until pixels.width).count { x ->
            val c = pixels[x, y]
            abs(c.red - outline.red) + abs(c.green - outline.green) + abs(c.blue - outline.blue) <
                0.1f
          }
        }
    assertTrue("the empty seats are drawn", drawn > 0)
  }

  @Test
  @Config(qualifiers = "night")
  fun atNight_theTitleAndTheDays_areLightOnTheDarkStone() {
    var onSurface = Color.Unspecified
    compose.setContent {
      OncompanionTheme {
        onSurface = MaterialTheme.colorScheme.onSurface
        CircleSheetContent(circle, {}, {}, {}, {})
      }
    }
    for (heading in listOf(R.string.circle_sheet_title, R.string.circle_sheet_today)) {
      val color = compose.onNodeWithText(string(heading)).textLayout().layoutInput.style.color
      assertEquals(string(heading), onSurface, color)
    }
  }

  @Test
  fun circle_namesItsMembersAndInvitations_andManageCallsBack() {
    val cases =
        listOf(
            circle to "Marc, Sophie · Julie invited",
            circle.copy(invited = emptyList()) to "Marc, Sophie",
            circle.copy(joined = emptyList()) to "Julie invited",
        )
    for ((state, line) in cases) {
      show(state)
      compose.onNodeWithText(string(R.string.circle_sheet_title)).assertIsDisplayed()
      compose.onNodeWithTag(C.Tag.circle_sheet_members).assertTextEquals(line)
    }
    compose.onNodeWithTag(C.Tag.circle_sheet_manage).assertHeightIsAtLeast(48.dp).performClick()
    assertEquals(1, managed)

    // Everyone left, but what they did stays: no names line, the cards still show
    show(circle.copy(joined = emptyList(), invited = emptyList()))
    compose.onNodeWithTag(C.Tag.circle_sheet_members).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.circleSheetCard(id("p1"))).assertIsDisplayed()
  }

  @Test
  fun circle_showsEachDayAndItsCards_inOrder_andACardsLinkCallsBack() {
    show(circle)

    compose.onNodeWithTag(C.Tag.circle_sheet_nothing_today).assertDoesNotExist()
    // Today, its card, then Yesterday and its cards, newest first: each card's state is the card's
    val tops =
        listOf(
                compose.onNodeWithText(string(R.string.circle_sheet_today)),
                compose.onNodeWithTag(C.Tag.circleSheetCard(id("p1"))),
                compose.onNodeWithText(string(R.string.circle_sheet_yesterday)),
                compose.onNodeWithTag(C.Tag.circleSheetCard(id("p2"))),
                compose.onNodeWithTag(C.Tag.circleSheetCard(id("p4"))),
                compose.onNodeWithTag(C.Tag.circleSheetCard(id("p3"))),
            )
            .map { it.fetchSemanticsNode().boundsInRoot.top }
    assertEquals(tops.sorted(), tops)
    assertEquals(tops.size, tops.distinct().size)
    // TalkBack can jump between the title and the days
    for (heading in listOf(R.string.circle_sheet_title, R.string.circle_sheet_today)) {
      compose
          .onNodeWithText(string(heading))
          .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }
    // A card's link reaches whoever shows the sheet, with the route the card picked
    compose.onNodeWithTag(C.Tag.circleSheetOpen(id("p1"))).performClick()
    assertEquals(listOf(Screen.prescriptionEdit("p1")), opened)
  }

  @Test
  fun nothingToday_saysSo_aboveTheLastDays() {
    val cases =
        listOf(
            circle.copy(days = circle.days.drop(1)),
            circle.copy(days = emptyList()),
        )
    for (state in cases) {
      show(state)
      compose.onNodeWithText(string(R.string.circle_sheet_nothing_today)).assertIsDisplayed()
      compose.onNodeWithText(string(R.string.circle_sheet_today)).assertDoesNotExist()
    }
  }

  @Test
  fun anOlderDay_isNamedByItsDate() {
    val older = today.minusDays(3)
    show(circle.copy(days = listOf(CircleDay(older, circle.days[0].cards))))
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
    compose
        .onNodeWithText(older.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault())))
        .assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun landscape_everyActionCanBeReachedWhole() {
    show(circle)
    compose.onNodeWithTag(C.Tag.circle_sheet_manage).assertIsFullyVisible()
    compose.onNodeWithTag(C.Tag.circleSheetOpen(id("p1"))).performScrollTo().assertIsFullyVisible()
    show(CircleSheetUiState.NobodyYet)
    compose.onNodeWithTag(C.Tag.circle_sheet_add_someone).performScrollTo().assertIsFullyVisible()
  }

  @Test
  @Config(qualifiers = "w320dp-h640dp", fontScale = 2f)
  fun largestFont_onASmallPhone_keepsEveryActionReachable() {
    show(circle)
    compose.onNodeWithTag(C.Tag.circle_sheet_manage).performScrollTo().assertIsFullyVisible()
    compose.onNodeWithTag(C.Tag.circleSheetOpen(id("p1"))).performScrollTo().assertIsFullyVisible()
  }

  /**
   * A ViewModel reading Marc's circle, with one prescription he added a minute before [now], on a
   * [clock] reading [now] unless given.
   */
  private fun seededViewModel(
      now: Instant = today.atTime(9, 30).atZone(ZoneId.systemDefault()).toInstant(),
      clock: Clock = Clock.fixed(now, ZoneId.systemDefault()),
      medications: FakeMedicationRepository = FakeMedicationRepository(),
  ): CircleSheetViewModel {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = it) })
    runBlocking { auth.signInWithGoogle("alex") }
    val circleRepo = FakeCareCircleRepository()
    circleRepo.setMembers("alex", listOf(CareCircleMember("marc", "Marc")))
    val notifications = FakeNotificationRepository { now }
    notifications.deliver(
        "alex",
        card("p1", LocalTime.NOON, null).notification.copy(createdAt = now.minusSeconds(60)),
    )
    medications.seed("alex", prescription("p1"))
    return CircleSheetViewModel(
        notifications,
        circleRepo,
        FakeCareCircleInviteRepository(),
        medications,
        auth,
        clock,
    )
  }

  @Test
  fun aFailedRead_saysSo_andRetryReadsAgain() {
    val medications = FakeMedicationRepository().apply { readError = IOException("refused") }
    val vm = seededViewModel(medications = medications)
    compose.setContent {
      OncompanionTheme { CircleSheetScreen(vm, onManage = {}, onAddSomeone = {}, onOpen = {}) }
    }
    compose.onNodeWithTag(C.Tag.circle_sheet_error).assertIsDisplayed()

    medications.readError = null
    compose.onNodeWithTag(C.Tag.circle_sheet_retry).performClick()
    compose.onNodeWithText(string(R.string.circle_sheet_to_confirm)).assertIsDisplayed()
  }

  @Test
  fun shownAgainAfterMidnight_todaysCardsMoveToYesterday() {
    val evening = today.atTime(23, 0).atZone(ZoneId.systemDefault()).toInstant()
    var time = evening
    val movingClock =
        object : Clock() {
          override fun getZone(): ZoneId = ZoneId.systemDefault()

          override fun withZone(zone: ZoneId?) = this

          override fun instant() = time
        }
    val vm = seededViewModel(now = evening, clock = movingClock)
    // The phone slept past midnight before the sheet is shown again
    time = today.plusDays(1).atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant()

    compose.setContent {
      OncompanionTheme { CircleSheetScreen(vm, onManage = {}, onAddSomeone = {}, onOpen = {}) }
    }

    compose.onNodeWithText(string(R.string.circle_sheet_yesterday)).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.circle_sheet_nothing_today)).assertIsDisplayed()
  }

  @Test
  fun sheet_opensOverTheScreen_withWhatTheViewModelReads_andBackClosesIt() {
    var dismissed = 0
    val vm = seededViewModel()
    compose.setContent {
      OncompanionTheme {
        CircleSheet(
            vm,
            onDismiss = { dismissed++ },
            onManage = {},
            onAddSomeone = {},
            onOpen = {},
        )
      }
    }
    compose.onNodeWithTag(C.Tag.circle_sheet).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.circle_sheet_to_confirm)).assertIsDisplayed()

    Espresso.pressBack()
    compose.waitForIdle()
    assertEquals(1, dismissed)
  }

  @Test
  fun screen_readsTheViewModelAgain_afterRotation() {
    val vm = seededViewModel()
    val content = {
      compose.activity.setContent {
        OncompanionTheme { CircleSheetScreen(vm, onManage = {}, onAddSomeone = {}, onOpen = {}) }
      }
    }
    content()
    compose.onNodeWithText(string(R.string.circle_sheet_to_confirm)).assertIsDisplayed()

    compose.activityRule.scenario.recreate()
    content()
    compose.onNodeWithText("Marc").assertIsDisplayed()
    compose.onNodeWithText("Dr. Martin").assertIsDisplayed()
  }
}
