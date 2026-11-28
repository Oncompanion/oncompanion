<!-- Co-authored-by: ChatGPT <noreply@openai.com> -->
<!-- Co-authored-by: Claude Code <noreply@anthropic.com> -->

# Reminder permission UI

Fictional appointment data, rendered from `ReminderPermissionScreenTest` using Robolectric
native graphics. `planning-portrait.png` shows the quiet invitation in existing Planning;
`planning-landscape.png` shows the blocked-app notice and reachable add action on a short window.
`large-text.png` shows the exact-alarm explanation at 200% text size on a small landscape phone.
These show app-owned UI only, not an actual Android system permission dialog or reminder delivery.

Run `bash gradlew testDebugUnitTest --tests '*ReminderPermissionScreenTest'` with Android SDK
configured locally. Captures are written to `app/build/outputs/screenshots/reminder-permissions`.

The existing Planning layout is retained; this PR does not implement its unrelated Figma restyle.
The exact-alarm notice is a labelled team-review proposal in the reminder permissions Figma section:
https://www.figma.com/design/LoQ2CmgkxNQhODegJmo7wd/OnCompanion?node-id=2209-180
