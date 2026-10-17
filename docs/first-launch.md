# First launch

The Welcome screen lets a new reader choose Floating or Classic reading controls,
Library or Last book on opening the app, and Light, Beige or Midnight. It uses the
same native specimens and palettes as Settings. Selecting a theme previews it
immediately. Continue saves these choices. The same automatic theme controls as
Settings let the reader enable Follow the system, select Light or Beige for the
system light appearance, and enable Material You on Android 12 or later. Selecting
a fixed theme turns off system following. All choices remain editable in Settings.

New installations start with Floating controls, Library on opening the app,
Follow the system enabled, Beige for the system light appearance and Material You
disabled. Keep screen on, volume-key page turning, automatic return-button hiding
and vibration are enabled. Brightness follows the system (Auto).

The initial reading layout uses Literata at 18 sp, line spacing 1.5, 34 dp side
margins, Pages mode and the Stack animation. Publisher formatting is enabled;
explicit styles in the book take priority, while unspecified properties use the
reader defaults. Justification and hyphenation are disabled, decorated initials
are enabled, image inversion and automatic image inversion are disabled, chapters
start on a new page, footnotes remain visible and the page counter shows the page
number.

A one-time DataStore migration preserves the previous values for existing
installations, including settings that were never explicitly saved. Its internal
marker does not count as prior use when deciding whether to show Welcome.

The welcome release badge comes from `version.properties` through the generated
`welcome_release_label` string. The current label is BETA 1. Changing the release
name updates Welcome, Settings and About together; it does not replay onboarding.

`WelcomeHeader` reserves an empty trailing area for future SVG artwork. Replace
the empty Box at that point when the artwork is ready. Large text gives the title
the full width. The whole screen scrolls so all choices and Continue remain
reachable in landscape, on short screens and with enlarged text.

Completion is an atomic, device-local `welcome_state.json` under
`Context.noBackupFilesDir`, separate from serialized preferences and backup data.
A new installation records pending before any choices are persisted. Pending
onboarding resumes after interruption. Installations that already have settings,
reader files, saved appearance hints or Android package update history are marked
complete without showing Welcome. Updating the version never resets that state.

The activity reads preferences and checks the marker before resolving startup
navigation. It waits for Continue before handling file-manager or widget
intents, and keeps pending intent state across activity recreation. App lock stays
ahead of Welcome. Continue writes only the six welcome-owned preference fields
in one DataStore edit and then completes the marker. Failed persistence leaves
the screen and draft available for retry.

Debug builds show Welcome on every new app launch, starting from saved preferences,
including automatic appearance choices. Opening the app through its launcher icon
also replays it when its task is still in memory. Continue dismisses it for that
launch; rotation, restoring the current task and ordinary background resume retain
navigation. Release builds keep the first-launch marker behavior described above.
The Continue button has no arrow and Welcome has no Skip action.

Native debug previews live in `ui/onboarding/WelcomePreviews.kt`: all three
palettes, full content, narrow, large text, landscape, saving and error states.
`FirstLaunchRepositoryTest` covers migration, interruption, completion,
merge behavior and persistence failure recovery.

Profile and Library use the same shared search/settings glass controls. Profile
renders `Search in profile (coming soon)` as static text with disabled semantics;
the Settings button remains available.

Continue keeps Welcome visible until startup navigation is resolved. Library covers
for the hero, the first twelve grid entries and their shelf mosaics are warmed in
the shared Coil memory cache while Welcome is shown (at most 32 images, three
decodes at once). Library navigation is laid out underneath the opaque Welcome
surface before the handoff; file/widget intents remain deferred and its semantics
are hidden until Welcome is dismissed. The app splash animation runs only once,
so completing Welcome does not fade/scale the whole app a second time.
