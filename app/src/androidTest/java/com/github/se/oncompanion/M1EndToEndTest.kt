// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.se.oncompanion.model.settings.AppearancePreference
import com.github.se.oncompanion.model.settings.DateFormatPreference
import com.github.se.oncompanion.model.settings.SettingsRepositoryProvider
import com.github.se.oncompanion.model.settings.TimeFormatPreference
import com.github.se.oncompanion.model.speech.SpeechRecognizer
import com.github.se.oncompanion.model.speech.SpeechRecognizerProvider
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.Tab
import com.github.se.oncompanion.utils.EmulatorGoogleSignIn
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.EndToEndTestDriver
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.github.se.oncompanion.utils.ScriptedSpeechRecognizer
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The M1 end-to-end test: a new patient goes through the whole app like a real user, against the
 * Firebase emulators. It signs in, goes through onboarding, then uses every main feature, and
 * checks the links between them: an appointment added in Planning shows on the Overview, a Ligue
 * event the patient confirms in Events shows in Planning, the first name given at onboarding shows
 * on the Overview and the profile, and signing in again finds everything where it was.
 *
 * Only Google's account picker is replaced, as a test can't drive it: [EmulatorGoogleSignIn]
 * returns a token the Auth emulator accepts, for a new account on every run.
 */
@RunWith(AndroidJUnit4::class)
class M1EndToEndTest {

  // Shows the app like MainActivity does, so it survives Android relaunching the screen
  @get:Rule val compose = createAndroidComposeRule<AppHostActivity>()

  private val ui = EndToEndTestDriver(compose)

  // A new account each run, so the test always starts with onboarding and empty data
  private val run = UUID.randomUUID().toString().take(8)
  private val signIn =
      EmulatorGoogleSignIn(
          FirebaseEmulator.fakeGoogleIdToken(
              sub = "m1-$run",
              email = "m1-$run@example.com",
              name = "Sam Rivera",
              givenName = "Sam",
          )
      )

  // A Ligue event for tomorrow. Events are added from the Firebase console, never by the app, and
  // the collection is shared by every test run: its own id, deleted after the test
  private val ligueEvent = "m1-event-$run"

  // The phone's recognizer the activity installed on launch, put back after a test sets a fake.
  // Read in setUp: the activity isn't launched yet when the test class is built
  private var phoneRecognizer: SpeechRecognizer? = null

  // The phone's language, put back after a test that sets its own
  private val deviceLocale = Locale.getDefault()

  @Before
  fun setUp(): Unit = runBlocking {
    phoneRecognizer = SpeechRecognizerProvider.installed
    SettingsRepositoryProvider.install(compose.activity.applicationContext)
    SettingsRepositoryProvider.repository.setAppearance(AppearancePreference.FOLLOW_DEVICE)
    SettingsRepositoryProvider.repository.setDateFormat(DateFormatPreference.FOLLOW_DEVICE)
    SettingsRepositoryProvider.repository.setTimeFormat(TimeFormatPreference.FOLLOW_DEVICE)
    EmulatorTestData.signOut()
  }

  @After
  fun tearDown(): Unit = runBlocking {
    AppHostActivity.content.value = {}
    phoneRecognizer?.let { SpeechRecognizerProvider.recognizer = it }
    Locale.setDefault(deviceLocale)
    SettingsRepositoryProvider.repository.setAppearance(AppearancePreference.FOLLOW_DEVICE)
    SettingsRepositoryProvider.repository.setDateFormat(DateFormatPreference.FOLLOW_DEVICE)
    SettingsRepositoryProvider.repository.setTimeFormat(TimeFormatPreference.FOLLOW_DEVICE)
    EmulatorTestData.signOut()
    runCatching { EmulatorTestData.deleteRawDocument("events/$ligueEvent") }
  }

  @Test
  fun newPatient_usesEveryMainFeature() {
    addLigueEventTomorrow(title = "Gentle yoga")
    ui.startApp(signIn)

    signInAndOnboard(firstName = "Sam")
    addPrescription(doctor = "Dr Martin", medication = "Tamoxifen")
    addAppointmentTomorrow(title = "Oncology check-up")
    checkPlanningLandscape(title = "Oncology check-up")
    openMedicationAndItsPrescription("Tamoxifen")
    seeNextAppointmentOnOverview(title = "Oncology check-up")
    confirmRegistrationForLigueEvent(title = "Gentle yoga", place = "Ligue, Geneva")
    seeConfirmedEventInPlanning(title = "Gentle yoga", appointment = "Oncology check-up")
    logSymptom()
    deleteSymptom()
    seeProfileAndAddHospital(firstName = "Sam", hospital = "HUG Geneva")
    checkProfileLandscape()
    openSettingsAndReturnToProfile()
    signOut()
    signInAgain(firstName = "Sam", nextAppointment = "Oncology check-up")
    ui.open(C.Tag.overview_shortcut_profile, hasTestTag(C.Tag.profile_screen))
    ui.open(C.Tag.profile_settings, hasTestTag(C.Tag.settings_appearance))
    ui.waitFor(
        hasTestTag(C.Tag.settings_appearance) and
            (hasText(string(R.string.appearance_dark), substring = true) or
                hasAnyDescendant(hasText(string(R.string.appearance_dark))))
    )
  }

  @Test
  fun newPatient_invitesSomeoneToTheirCareCircle() {
    ui.startApp(signIn)

    signInAndOnboard(firstName = "Sam")
    inviteToCareCircle(firstName = "Julie")
  }

  /**
   * US-44 and US-73: the patient dictates a symptom from the journal's Add symptom, then from the
   * Overview's "Speak how you feel", checks each draft and saves it; both are in the journal.
   */
  @Test
  fun newPatient_logsSymptomsByVoice() {
    // The phrases are English: the app parses what it hears in the phone's language, so a French
    // emulator would draft nothing from them
    Locale.setDefault(Locale.US)
    // The emulator can't hear anyone: the fake recognizer "hears" one phrase per dictation
    SpeechRecognizerProvider.recognizer =
        ScriptedSpeechRecognizer("I feel nauseous", "I'm really tired, about 7 out of 10")
    // Allowed beforehand: Android's own microphone dialog is outside the app
    InstrumentationRegistry.getInstrumentation()
        .uiAutomation
        .grantRuntimePermission(compose.activity.packageName, Manifest.permission.RECORD_AUDIO)
    ui.startApp(signIn)
    signInAndOnboard(firstName = "Sam")

    dictateSymptomFromJournal()
    speakHowYouFeelFromOverview()

    // Both dictated symptoms are in the journal
    ui.open(C.Tag.overview_journal_link, hasTestTag(C.Tag.symptom_journal_list))
    for (type in listOf(R.string.symptom_type_nausea, R.string.symptom_type_fatigue)) {
      ui.waitFor(hasText(string(type), substring = true) and hasClickAction())
    }
  }

  /** Every editable Profile field stays saved after visiting Settings and returning. */
  @Test
  fun editedProfile_survivesSettingsRoundTrip() {
    ui.startApp(signIn)
    signInAndOnboard(firstName = "Sam")
    ui.open(C.Tag.overview_shortcut_profile, hasTestTag(C.Tag.profile_edit))
    ui.open(C.Tag.profile_edit, hasTestTag(C.Tag.edit_profile_first_name))
    val fields =
        listOf(
            C.Tag.edit_profile_first_name to "Jamie",
            C.Tag.edit_profile_family_name to "Taylor",
            C.Tag.cancer_type_field to "Example type",
            C.Tag.hospital_field to "Example hospital",
        )
    for ((tag, value) in fields) ui.tag(tag).reveal().replaceText(value)
    ui.open(C.Tag.edit_profile_save, hasTestTag(C.Tag.profile_screen))
    ui.waitFor(hasTestTag(C.Tag.profile_name) and hasText("Jamie Taylor"))
    ui.open(C.Tag.profile_settings, hasTestTag(C.Tag.settings_screen))
    ui.open(C.Tag.settings_back, hasTestTag(C.Tag.profile_screen))

    // Check both the displayed profile and a fresh edit destination's persisted values.
    for ((tag, value) in
        listOf(
            C.Tag.profile_first_name to "Jamie",
            C.Tag.profile_family_name to "Taylor",
            C.Tag.profile_cancer_type to "Example type",
            C.Tag.profile_hospital to "Example hospital",
        )) {
      ui.waitFor(hasTestTag(tag) and hasAnyDescendant(hasText(value))).reveal().assertIsDisplayed()
    }
    ui.open(C.Tag.profile_edit, hasTestTag(C.Tag.edit_profile_first_name))
    for ((tag, value) in fields) ui.tag(tag).reveal().assertTextContains(value)
  }

  /** Settings survives a real rotation; toolbar and Android Back retain the same Profile. */
  @Test
  fun settingsRotation_backReturnsToTheSameProfile() {
    ui.startApp(signIn)
    signInAndOnboard(firstName = "Sam")
    ui.open(C.Tag.overview_shortcut_profile, hasTestTag(C.Tag.profile_edit))
    ui.tag(C.Tag.profile_settings).reveal().fullyVisible()
    captureSettings("settings-profile-portrait.png")
    try {
      for (systemBack in listOf(false, true)) {
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        ui.open(C.Tag.profile_settings, hasTestTag(C.Tag.settings_screen))
        ui.tag(C.Tag.settings_appearance).reveal().assertIsDisplayed()
        captureSettings("settings-portrait.png")
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        ui.tag(C.Tag.settings_screen).assertIsDisplayed()
        ui.tag(C.Tag.settings_appearance).reveal().fullyVisible()
        ui.tag(C.Tag.settings_back).reveal().fullyVisible()
        captureSettings("settings-landscape.png")
        if (systemBack) {
          compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
          ui.waitFor(hasTestTag(C.Tag.profile_screen))
        } else {
          ui.open(C.Tag.settings_back, hasTestTag(C.Tag.profile_screen))
        }
        ui.waitFor(hasTestTag(C.Tag.profile_name) and hasText("Sam")).reveal().assertIsDisplayed()
        ui.waitFor(
                hasTestTag(C.Tag.profile_email) and hasAnyDescendant(hasText("m1-$run@example.com"))
            )
            .reveal()
            .assertIsDisplayed()
        ui.tag(C.Tag.profile_settings).reveal().fullyVisible()
        captureSettings("settings-profile-landscape.png")
      }
    } finally {
      rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
    }
  }

  // ---- steps ----

  /** Medication details and their source prescription return to the same selected Planning day. */
  private fun openMedicationAndItsPrescription(name: String) {
    val selectedDay =
        ui.tag(C.Tag.planning_heading).fetchSemanticsNode().config[SemanticsProperties.Text]
    ui.open(hasText(name), hasTestTag(C.Tag.medication_detail_prescription))
    ui.waitFor(hasText("20 mg")).assertIsDisplayed()
    ui.open(C.Tag.medication_detail_prescription, hasTestTag(C.Tag.prescription_detail_screen))
    // The prescription heading may repeat its only medication's name; target the medication row.
    ui.waitFor(
            hasText(name) and
                SemanticsMatcher("is a prescription medication row") {
                  it.config
                      .getOrNull(SemanticsProperties.TestTag)
                      ?.startsWith(C.Tag.prescriptionDetailMedication("")) == true
                }
        )
        .reveal()
        .assertIsDisplayed()
    ui.open(C.Tag.prescription_detail_back_button, hasTestTag(C.Tag.medication_detail_screen))
    ui.open(C.Tag.medication_detail_back, hasTestTag(C.Tag.planning_screen))
    org.junit.Assert.assertEquals(
        selectedDay,
        ui.tag(C.Tag.planning_heading).fetchSemanticsNode().config[SemanticsProperties.Text],
    )
  }

  /** Rotation gives the agenda a full viewport without losing the selected appointment day. */
  private fun checkPlanningLandscape(title: String) {
    rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
    try {
      ui.tag(C.Tag.planning_list).performScrollToNode(hasText(title))
      ui.waitFor(hasText(title)).fullyVisible()
      compose.onNodeWithTag(C.Tag.planning_week).assertIsNotDisplayed()
      ui.tag(C.Tag.bottom_navigation_tab_overview).fullyVisible()
    } finally {
      rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
    }
  }

  /** Profile actions remain reachable after rotating the same signed-in patient's screen. */
  private fun checkProfileLandscape() {
    rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
    try {
      ui.tag(C.Tag.profile_sign_out).reveal().fullyVisible()
      compose.onNodeWithTag(C.Tag.profile_back).assertIsNotDisplayed()
      ui.tag(C.Tag.profile_edit).reveal().fullyVisible()
    } finally {
      rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
    }
  }

  private fun rotate(requested: Int, expected: Int) {
    compose.runOnUiThread { compose.activity.requestedOrientation = requested }
    compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == expected }
    compose.waitForIdle()
  }

  private fun SemanticsNodeInteraction.fullyVisible() {
    val node = fetchSemanticsNode()
    val size = node.size
    assertTrue("The control must have drawable area", size.width > 0 && size.height > 0)
    assertTrue(
        "The whole control must fit in the viewport",
        node.boundsInRoot.width >= size.width && node.boundsInRoot.height >= size.height,
    )
  }

  /** Google sign-in, then onboarding as a patient: the Overview greets them by first name. */
  private fun signInAndOnboard(firstName: String) {
    ui.signInAndOnboard(
        firstName,
        C.Tag.onboarding_role_patient,
        hasTestTag(C.Tag.overview_greeting) and hasText(firstName, substring = true),
    )
  }

  /** From the Prescriptions tab: a prescription entered manually appears in its list. */
  private fun addPrescription(doctor: String, medication: String) {
    ui.open(Tab.PRESCRIPTIONS.testTag, hasTestTag(C.Tag.prescriptions_enter_manually_button))
    ui.open(
            C.Tag.prescriptions_enter_manually_button,
            hasTestTag(C.Tag.prescription_form_prescribed_by_field),
        )
        .typeText(doctor)
    ui.tag(C.Tag.prescription_form_medication_name_field).reveal().typeText(medication)
    ui.tag(C.Tag.prescription_form_dosage_field).reveal().typeText("20 mg")
    ui.tag(C.Tag.prescription_form_frequency_field).reveal().typeText("Once a day")
    // Saving returns to the list, which shows the new prescription
    ui.open(
        C.Tag.prescription_form_save_button,
        hasTestTag(C.Tag.prescriptions_list) and hasAnyDescendant(hasText(medication)),
    )
    ui.open(Tab.OVERVIEW.testTag, hasTestTag(C.Tag.overview_greeting))
  }

  /** From the Planning tab: an appointment added for tomorrow appears on that day. */
  private fun addAppointmentTomorrow(title: String) {
    ui.open(C.Tag.bottom_navigation_tab_planning, hasTestTag(C.Tag.planning_screen))

    selectTomorrowInPlanning()

    ui.open(C.Tag.planning_add, hasTestTag(C.Tag.appointment_title)).typeText(title)
    ui.tag(C.Tag.appointment_time).performClick()
    ui.tag(C.Tag.appointment_confirm).performClick()
    ui.tag(C.Tag.appointment_location).reveal().typeText("HUG, oncology")
    ui.open(C.Tag.appointment_reminder, hasTestTag(C.Tag.appointment_reminder_dialog))
    ui.tag(C.Tag.appointmentReminder("ONE_DAY")).performScrollTo().performClick()
    ui.tag(C.Tag.appointment_reminder).assertTextContains("1 day before")
    // Saving returns to the calendar, on the appointment's day
    ui.open(
        C.Tag.appointment_save,
        hasTestTag(C.Tag.planning_screen) and hasAnyDescendant(hasText(title)),
    )
  }

  /** The link between Planning and the Overview: the new appointment is the next one there. */
  private fun seeNextAppointmentOnOverview(title: String) {
    ui.open(
        C.Tag.bottom_navigation_tab_overview,
        hasTestTag(C.Tag.overview_next_up) and
            (hasText(title, substring = true) or hasAnyDescendant(hasText(title))),
    )
  }

  /**
   * The Events tab lists the Ligue's event, whose detail says where it takes place; the patient
   * confirms there that they registered with the organizer (US-25).
   */
  private fun confirmRegistrationForLigueEvent(title: String, place: String) {
    // The list only composes the cards on screen: scroll to ours first, in case earlier events
    // (e.g. left by a crashed run) push it down
    ui.open(C.Tag.bottom_navigation_tab_events, hasTestTag(C.Tag.events_list))
        .performScrollToKey(ligueEvent)
    ui.open(
        hasTestTag(C.Tag.eventCard(ligueEvent)),
        hasTestTag(C.Tag.event_detail_event_title) and hasText(title, substring = true),
    )
    ui.waitFor(hasTestTag(C.Tag.event_detail_location) and hasText(place, substring = true))
    ui.waitFor(hasTestTag(C.Tag.event_detail_confirm)).reveal().performClick()
    ui.waitFor(hasTestTag(C.Tag.event_detail_registration_confirmed))
    ui.open(C.Tag.event_detail_back, hasTestTag(C.Tag.events_list))
  }

  /**
   * The link between Events and Planning (US-30): the confirmed event shows on its day, next to the
   * patient's appointment.
   */
  private fun seeConfirmedEventInPlanning(title: String, appointment: String) {
    ui.open(C.Tag.bottom_navigation_tab_planning, hasTestTag(C.Tag.planning_screen))
    selectTomorrowInPlanning()

    ui.waitFor(
        hasTestTag(C.Tag.planning_screen) and
            hasAnyDescendant(hasText(title)) and
            hasAnyDescendant(hasText(appointment))
    )
    ui.open(C.Tag.bottom_navigation_tab_overview, hasTestTag(C.Tag.overview_greeting))
  }

  /** From the Overview: a symptom logged in a few taps appears in the journal. */
  private fun logSymptom() {
    ui.open(C.Tag.overview_journal_link, hasTestTag(C.Tag.symptom_journal_add))
    ui.open(C.Tag.symptom_journal_add, hasTestTag(C.Tag.symptomAddType("FATIGUE"))).performClick()

    // Saving returns to the journal, with the new entry
    ui.open(
        C.Tag.symptom_add_save,
        hasTestTag(C.Tag.symptom_journal_list) and
            hasAnyDescendant(hasText(string(R.string.symptom_type_fatigue))),
    )
    ui.open(C.Tag.symptom_back_button, hasTestTag(C.Tag.overview_greeting))
  }

  /** From the journal: the symptom just logged is deleted after confirming, and is gone. */
  private fun deleteSymptom() {
    // The entry's row: the "This week" card above it also says "Most often: Fatigue", but can't be
    // tapped
    val entry =
        hasText(string(R.string.symptom_type_fatigue), substring = true) and hasClickAction()
    ui.open(C.Tag.overview_journal_link, entry)
    ui.open(entry, hasTestTag(C.Tag.symptom_detail_delete)).reveal().performClick()
    // Back in the journal, which is empty again
    ui.open(C.Tag.symptom_delete_confirm, hasTestTag(C.Tag.symptom_journal_empty))
    ui.open(C.Tag.symptom_back_button, hasTestTag(C.Tag.overview_greeting))
  }

  /**
   * From the journal's Add symptom (US-44): the patient speaks, sees what was heard, and the form
   * comes back drafted with the symptom picked; saving returns to the journal with the entry.
   */
  private fun dictateSymptomFromJournal() {
    ui.open(C.Tag.overview_journal_link, hasTestTag(C.Tag.symptom_journal_add))
    ui.open(C.Tag.symptom_journal_add, hasTestTag(C.Tag.symptom_add_speak))
    ui.open(C.Tag.symptom_add_speak, hasTestTag(C.Tag.symptom_voice_screen))
    reviewWhatWasHeard("I feel nauseous", type = "NAUSEA", pickedUp = R.string.symptom_type_nausea)
    ui.open(
        C.Tag.symptom_add_save,
        hasTestTag(C.Tag.symptom_journal_list) and
            hasAnyDescendant(hasText(string(R.string.symptom_type_nausea))),
    )
    ui.open(C.Tag.symptom_back_button, hasTestTag(C.Tag.overview_greeting))
  }

  /**
   * From the Overview's "Speak how you feel" (US-73): the form opens already listening; once the
   * drafted symptom is saved, the patient is back on the Overview.
   */
  private fun speakHowYouFeelFromOverview() {
    ui.open(C.Tag.overview_speak, hasTestTag(C.Tag.symptom_voice_screen))
    val tired = "I'm really tired, about 7 out of 10"
    // The intensity said is picked up too
    ui.waitFor(
        hasTestTag(C.Tag.symptom_voice_picked_up) and
            hasAnyDescendant(hasText(string(R.string.symptom_voice_intensity, 7)))
    )
    reviewWhatWasHeard(tired, type = "FATIGUE", pickedUp = R.string.symptom_type_fatigue)
    ui.open(C.Tag.symptom_add_save, hasTestTag(C.Tag.overview_greeting))
  }

  /**
   * On the voice screen, once the patient stopped speaking: the [transcript] is shown with the
   * symptom picked up from it ([pickedUp]); Done fills the form, which says it was drafted and has
   * [type] picked, for the patient to check before saving.
   */
  private fun reviewWhatWasHeard(transcript: String, type: String, pickedUp: Int) {
    // Heard, not still listening: the phone sent the whole text
    ui.waitFor(
        hasTestTag(C.Tag.symptom_voice_headline) and hasText(string(R.string.symptom_voice_heard))
    )
    // The card's tag is on its Surface, the text in a child
    ui.waitFor(
        hasTestTag(C.Tag.symptom_voice_transcript) and
            hasAnyDescendant(hasText(transcript, substring = true))
    )
    ui.waitFor(
        hasTestTag(C.Tag.symptom_voice_picked_up) and hasAnyDescendant(hasText(string(pickedUp)))
    )
    ui.open(C.Tag.symptom_voice_done, hasTestTag(C.Tag.symptom_add_drafted))
    ui.tag(C.Tag.symptomAddType(type)).reveal().assertIsSelected()
  }

  /** The profile shows the onboarding first name; a hospital entered there is saved. */
  private fun seeProfileAndAddHospital(firstName: String, hospital: String) {
    ui.open(
        C.Tag.overview_shortcut_profile,
        hasTestTag(C.Tag.profile_name) and hasText(firstName, substring = true),
    )

    ui.open(C.Tag.profile_edit, hasTestTag(C.Tag.hospital_field)).reveal().typeText(hospital)
    // Typing remains manual: only an explicit Search may start a lookup, and Save stays usable.
    compose.onNodeWithTag(C.Tag.hospital_loading).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.hospital_results).assertDoesNotExist()
    ui.tag(C.Tag.edit_profile_save).reveal().performClick()

    ui.waitFor(
        hasTestTag(C.Tag.profile_hospital) and
            (hasText(hospital, substring = true) or hasAnyDescendant(hasText(hospital)))
    )
  }

  /** Settings is reachable from the real patient Profile and Back retains that profile. */
  private fun openSettingsAndReturnToProfile() {
    captureSettings("settings-profile-entry.png")
    ui.open(C.Tag.profile_settings, hasTestTag(C.Tag.settings_screen))
    captureSettings("settings-appearance-category.png")
    ui.open(C.Tag.settings_appearance, hasTestTag(C.Tag.appearance_dark))
    ui.tag(C.Tag.appearance_light).reveal().performClick()
    ui.tag(C.Tag.appearance_light).assertIsSelected()
    captureSettings("settings-appearance-light.png")
    ui.open(C.Tag.settings_back, hasTestTag(C.Tag.settings_appearance))
    captureSettings("settings-light.png")
    ui.open(C.Tag.settings_appearance, hasTestTag(C.Tag.appearance_dark))
    ui.tag(C.Tag.appearance_dark).reveal().performClick()
    ui.tag(C.Tag.appearance_dark).assertIsSelected()
    captureSettings("settings-appearance-dark.png")
    ui.open(C.Tag.settings_back, hasTestTag(C.Tag.settings_appearance))
    ui.waitFor(
        hasTestTag(C.Tag.settings_appearance) and
            (hasText(string(R.string.appearance_dark), substring = true) or
                hasAnyDescendant(hasText(string(R.string.appearance_dark))))
    )
    captureSettings("settings-dark.png")
    saveDateAndTimeFormatsAndReopen()
    ui.open(C.Tag.settings_back, hasTestTag(C.Tag.profile_screen))
  }

  /** Exercises both format destinations through the Activity and the real device settings store. */
  private fun saveDateAndTimeFormatsAndReopen() {
    val twelveHour = C.Tag.settingsTimeChoice(TimeFormatPreference.TWELVE_HOUR.name)
    val isoDate = C.Tag.settingsDateChoice(DateFormatPreference.YEAR_MONTH_DAY.name)

    ui.open(C.Tag.settings_date_time, hasTestTag(C.Tag.settings_date_time_screen))
    ui.open(C.Tag.settings_time_format, hasTestTag(C.Tag.settings_time_format_screen))
    ui.tag(twelveHour).reveal().performClick()
    ui.waitFor(hasTestTag(twelveHour) and isSelected())
    ui.open(C.Tag.settings_back, hasTestTag(C.Tag.settings_date_time_screen))
    ui.open(C.Tag.settings_date_format, hasTestTag(C.Tag.settings_date_format_screen))
    ui.tag(isoDate).reveal().performClick()
    ui.waitFor(hasTestTag(isoDate) and isSelected())
    ui.open(C.Tag.settings_back, hasTestTag(C.Tag.settings_date_time_screen))
    ui.open(C.Tag.settings_back, hasTestTag(C.Tag.settings_screen))

    ui.open(C.Tag.settings_date_time, hasTestTag(C.Tag.settings_date_time_screen))
    ui.open(C.Tag.settings_time_format, hasTestTag(C.Tag.settings_time_format_screen))
    ui.tag(twelveHour).reveal().assertIsSelected()
    ui.open(C.Tag.settings_back, hasTestTag(C.Tag.settings_date_time_screen))
    ui.open(C.Tag.settings_date_format, hasTestTag(C.Tag.settings_date_format_screen))
    ui.tag(isoDate).reveal().assertIsSelected()
    ui.open(C.Tag.settings_back, hasTestTag(C.Tag.settings_date_time_screen))
    ui.tag(C.Tag.settings_format_preview)
        .reveal()
        .assertTextContains("2026-11-18", substring = true)
    ui.open(C.Tag.settings_back, hasTestTag(C.Tag.settings_screen))
  }

  private fun captureSettings(name: String) {
    File(compose.activity.cacheDir, name).outputStream().use {
      compose
          .onRoot()
          .captureToImage()
          .asAndroidBitmap()
          .compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }

  /**
   * From the Overview: the patient invites someone to their care circle, who then appears in it as
   * waiting to join. Joining with the invite code on the caregiver's side has no screen yet (#137).
   */
  private fun inviteToCareCircle(firstName: String) {
    // A new patient's circle is empty: its add button is in the page
    ui.open(C.Tag.overview_shortcut_care_circle, hasTestTag(C.Tag.care_circle_empty_add_button))
    ui.open(C.Tag.care_circle_empty_add_button, hasTestTag(C.Tag.care_circle_form_first_name))
        .typeText(firstName)
    ui.tag(C.Tag.care_circle_form_relationship).reveal().performClick()
    // On a short screen the menu scrolls, and an option below its visible part can't be tapped
    ui.waitFor(hasTestTag(C.Tag.careCircleFormRelationshipOption("DAUGHTER")))
        .reveal()
        .performClick()
    // Without a relationship Save does nothing, so a missed pick fails here, not at the invite code
    ui.waitFor(
        hasTestTag(C.Tag.care_circle_form_relationship) and
            hasText(string(R.string.relationship_daughter))
    )
    // Saving shows the invite code to share, in place of the form
    ui.open(C.Tag.care_circle_form_save_button, hasTestTag(C.Tag.care_circle_invite_code))

    // Back returns to the circle, where the invitation is pending
    ui.open(
        C.Tag.care_circle_invite_back_button,
        hasTestTag(C.Tag.care_circle_member_list) and
            hasAnyDescendant(hasTestTag(C.Tag.care_circle_pending_section)) and
            hasAnyDescendant(hasText(firstName, substring = true)),
    )
  }

  /** Signing out, after confirming, goes back to the sign-in screen. */
  private fun signOut() {
    ui.open(C.Tag.profile_sign_out, hasTestTag(C.Tag.profile_sign_out_confirm))
    ui.open(C.Tag.profile_sign_out_confirm, hasTestTag(C.Tag.sign_in_screen))
  }

  /**
   * A returning user: signing in again with the same account goes straight to the Overview, without
   * onboarding, and their data is still there.
   */
  private fun signInAgain(firstName: String, nextAppointment: String) {
    ui.open(
        C.Tag.google_sign_in_button,
        hasTestTag(C.Tag.overview_greeting) and hasText(firstName, substring = true),
    )
    ui.waitFor(
        hasTestTag(C.Tag.overview_next_up) and
            (hasText(nextAppointment, substring = true) or
                hasAnyDescendant(hasText(nextAppointment)))
    )
    // The waitFor above is the check: nothing clicks through onboarding, so reaching the greeting
    // means it was skipped
  }

  // ---- helpers ----

  /** In Planning, selects tomorrow, which can be in next week's calendar. */
  private fun selectTomorrowInPlanning() {
    val tomorrow = C.Tag.planningDay(LocalDate.now().plusDays(1).toString())
    if (!ui.exists(hasTestTag(tomorrow))) ui.tag(C.Tag.planning_next).performClick()
    ui.waitFor(hasTestTag(tomorrow)).performClick()
  }

  /** Writes a Ligue event for tomorrow at 15:00, as the Firebase console would. */
  private fun addLigueEventTomorrow(title: String) = runBlocking {
    val startAt =
        LocalDate.now().plusDays(1).atTime(15, 0).atZone(ZoneId.systemDefault()).toInstant()
    EmulatorTestData.createRawDocument(
        "events",
        ligueEvent,
        """{"title": {"stringValue": "$title"}, "place": {"stringValue": "Ligue, Geneva"},
           "summary": {"stringValue": "A gentle class"},
           "description": {"stringValue": "Gentle yoga for patients."},
           "startAt": {"timestampValue": "$startAt"}}""",
    )
  }

  // Keep fluent journey steps while sharing all interaction behavior with M2.
  private fun SemanticsNodeInteraction.typeText(value: String) = ui.typeText(this, value)

  private fun SemanticsNodeInteraction.replaceText(value: String) = ui.replaceText(this, value)

  private fun SemanticsNodeInteraction.reveal() = ui.reveal(this)

  private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
}
