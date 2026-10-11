# In-app notifications

All app-owned transient messages use `ui/components/FrogSnackbar.kt`, including
library imports, settings/backup results, Reading Queue, Reading Log (list and
editor), saved quotes/bookmarks, profile-save errors and reader/translation errors.
Screen owners retain `SnackbarHostState`, event handling and Undo results.

- The floating surface follows the active Material palette: white in Light,
  ivory in Beige, raised dark in Midnight, and system roles with Material You.
  Text uses `onSurface`, actions use `primary`, dismissal uses `onSurfaceVariant`.
- Cards have a 24 dp corner radius, a subtle `outlineVariant` boundary, and
  20 dp horizontal margins. Width is capped at 560 dp on wider windows.
- Messages wrap without truncation. Optional actions occupy a second row to
  retain space for long titles and large fonts. Screen owners place the host
  above their dock, FAB, save button or reader controls.
- Entrance combines a 24 dp rise, fade and restrained 0.98-to-1 scale over
  280 ms. Exit fades over 180 ms and lowers 12 dp over 200 ms. Replacement
  messages delay entrance by 120 ms; outgoing messages lose actions/semantics.
  Compose applies the system animation-duration scale.
- Durations retain Material defaults (4 s / 10 s / indefinite) and extend through
  `LocalAccessibilityManager.calculateRecommendedTimeoutMillis` for text and
  controls. The current notification is a polite live region with a pane title
  and accessibility dismissal; Undo continues to call `performAction()`.

## Validation

`FrogSnackbarPreviews.kt` covers the three fixed palettes, Material You,
320 dp with 1.5x text, RTL, long messages, Undo and dismissal. The six native
previews rendered and were inspected. `./gradlew test :app:assembleDebug`
and `git diff --check` passed. Device timing, TalkBack and touch interaction
were not exercised as part of this change. Platform-owned messages (such as
Android's clipboard confirmation) remain managed by Android.
