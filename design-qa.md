# Profile and shared navigation — visual QA

Date: 2026-09-20

Final result: passed for the reviewed native Compose Preview states. Physical-device acceptance remains unverified.

## Latest reference and implementation

The latest two user images supersede the earlier navigation variants:

- `/var/folders/f6/p25h1v7s0j1g0w7qr6mgkrhw0000gn/T/codex-clipboard-ee2c945d-88da-4178-82df-df7f0302b9b5.png` — light, 1044 × 698 pixels.
- `/var/folders/f6/p25h1v7s0j1g0w7qr6mgkrhw0000gn/T/codex-clipboard-a832042e-117b-4aff-beba-eb519c870e8e.png` — dark, 1122 × 382 pixels.

Implemented a tinted floating capsule with icons above uppercase labels and a full selected-tab pill. A separate 60 dp add button has 20 dp corners and a 12 dp gap from navigation. The navigation surface is 72 dp tall at default text size, grows with larger text, and shares a centered row capped at 400 dp with the add button. Horizontal outer padding is 20 dp. Theme colors provide sage/mint in light mode and a near-black surface with green accents in OLED mode.

The add button remains available on both Library and Profile. The shared Scaffold reserves bottom clearance; expanded import actions overlay content without increasing the navigation layout height. The scrim covers content and navigation while actions remain above it. Cancelling the picker preserves Profile; selecting files, creating a shelf, or finishing a scan with results opens Library through the existing callbacks.

## Native visual evidence

Android Studio Compose Preview was refreshed and showed **Up-to-date**. Screenshots were inspected inline through the computer-use tool; no standalone screenshot files were exported. The final IDE captures are 1346 × 768 pixels, including IDE chrome.

- `ProfilePreviews.kt`: 393 × 852 dp light, 360 × 800 dp light with a three-word Cyrillic name, and 393 × 852 dp OLED with a long English name. All profile cards and the new navigation/add button fit in the first viewport at default text size.
- `LibraryChromePreviews.kt`: the actual Library screen in 360 dp light, 393 dp OLED, and with the import menu expanded. All three import actions are visible above the add button. The latest source images and rendered Library previews were emitted together for direct comparison.
- `NavigationPreviews.kt`: focused light, OLED, sepia, and 150% text previews with the shared add button. Labels remain on one line. IDE layout-bound overlays in these focused captures are tooling diagnostics, not application borders.
- Library list mode and a full Library preview at 150% text were inspected during the preceding iteration; the final stacked-label version was checked at 150% in the focused preview.

Comparison covers control proportions, typography, alignment, spacing, and theme treatment. The references are cropped phone mockups, while the implementation is native Android at IDE fit zoom; system chrome, sample book data, and screenshot density are not expected to match pixel for pixel. Debug previews use in-memory data and do not modify the user's library.

## Revision history and resolved findings

1. The first oversized floating navigation surface had excessive empty space and visual weight. It was replaced during the earlier iteration.
2. An independently floating add button competed with Library content. Navigation and add now occupy a shared horizontal row with reserved content clearance.
3. Narrow layouts with enlarged text exposed wrapping in the earlier horizontal labels. The final reference-driven design consistently stacks icons over single-line labels.
4. The latest reference replaces the earlier horizontal icon/title capsule: larger selected area, uppercase captions, inactive outline Profile icon, rounded-square add button, and adjusted light/OLED surfaces.
5. Existing profile density, adaptive names, aligned card dividers, MON–SUN ordering, and removal of visible “Coming soon” labels remain intact. The online badge is omitted. Search and achievements are still unfinished functionality; fabricated achievement values were not added.

## Validation and limits

- Android Studio JBR: `./gradlew test :app:assembleDebug` — successful after the final changes (14 seconds).
- Unit suite: 931 tests, 0 failures, 0 errors, 5 skipped.
- `git diff --check` — passed.
- No emulator was started and no physical device was automated.
- Device touch behavior, TalkBack traversal, animation timing, landscape, and unusual display densities were not manually validated. The static previews demonstrate layout only; compilation and unit tests are separate evidence.
- No remaining actionable visual issue was identified in the final reviewed states. Scrolling remains available on constrained profile layouts.

## Follow-up — equal navigation and FAB height

The user requested matching heights and radius changes limited to the bottom navigation. Its outer radius is now fixed at 30 dp and the selected tab radius at 24 dp. The add button retains its 20 dp corners, but its size follows the measured navigation height (72 dp at default text size). Reserved horizontal space uses that same size, and both controls share the same bottom inset. Profile card radii are unchanged.

`./gradlew :app:assembleDebug` and `git diff --check` passed. Refreshed native Library previews were inspected in light, OLED, expanded-menu, 150% text, and list states: navigation and FAB top/bottom edges align. This supersedes the earlier 60 dp FAB description. No new device interaction or unit-test run was performed for this layout-only correction.

## Follow-up — compact 60 dp dock

The user found the equal-height 72 dp FAB visually oversized. Both navigation and FAB now default to 60 dp. The navigation keeps 6 dp inset padding around 48 dp minimum-height tabs, uses 22 dp icons, a 2 dp icon/caption gap, and 4 dp vertical item padding. Caption text stays at 11 sp; the outer/selected radii remain 30/24 dp and FAB corners remain 20 dp. Navigation elevation is reduced to 4 dp. The shared measured-height sizing still allows the controls to grow together for larger text.

Android Studio JBR `:app:assembleDebug` succeeded in 6 seconds; whitespace validation passed. This is a layout-only change; profile cards and import actions are unchanged.

## Correction — reduce only the add button

The user clarified that the navigation itself should retain its prior proportions. Restored its 72 dp default height, 60 dp tabs, 24 dp icons, 4 dp icon/caption gap, 8 dp vertical padding, and 6 dp elevation. Kept the requested 30/24 dp navigation radii. Only the FAB stays reduced to 60 dp, with 20 dp corners; it is vertically centered against the measured panel height and reserves its own 60 dp width plus the 12 dp gap. This supersedes the compact 60 dp dock iteration above. No new visual capture was made for this correction.

## Follow-up — aligned Library/Profile headers (2026-09-21)

Both screens now use `FrogHeaderContent`: stable top/horizontal system insets, 16 dp side margins, 10 dp top spacing, the same 44 dp search field and 42 dp settings button, and 16 dp between toolbar and hero. Profile retains its surface-container control color. Profile search and achievements retain their existing disabled semantics.

The profile hero is redesigned as an 84 dp avatar/name/menu row above a full-width achievements row with a separate statistics button. Both hero cards share 28 dp corners, 14 dp inner padding, and a 175 dp default height; their height grows together with system font scaling. Long profile names can occupy three lines. Profile retains scrolling and the shared add button.

Native Android Studio Compose Preview was inspected after refresh: Profile at 393 dp light/OLED, 360 dp with a long Cyrillic name, and 360 dp at 150% text; Library in light/OLED, expanded-add-menu, and 150% text states. Profile controls and names fit the hero. At 360 × 800 dp, the lower dashboard requires scrolling; the 393 × 852 dp default-text profile fits. No emulator or physical device was controlled.

Validation: JBR `./gradlew test :app:assembleDebug` passed (940 tests, 0 failures/errors, 5 skipped); after final name-sizing and search-color polish, `:app:assembleDebug` passed again. `git diff --check` passed. These are native preview/build results, not physical-device validation.


## 2026-10-05 — Reader progress: rectangular segments

Scope: the user selected a new shape for the existing native Android progress control. This pass covers segment and focus-outline shape, spacing, and cursor containment; it retains the existing book-coordinate map, gestures, palette, typography, and compact reader panel. No web prototype or raster assets are needed for this native control.

Source visual truth: `/Users/frog/Downloads/Screenshot_20261005-231044.png` (1080 × 171 px).
Implementation screenshot: `/Users/frog/AndroidStudioProjects/FrogReader/.artifacts/reader-progress-segments/native/com/example/frogreader/ui/reader/ReaderProgressPreviewsKt/rectangular_reference_0.png` (1032 × 168 px).
Full-view comparison evidence: `/Users/frog/AndroidStudioProjects/FrogReader/.artifacts/reader-progress-segments/rectangular-shape-comparison.png`.
Viewport/state: native Compose, 393 × 64 dp, OLED, page 56/147, four pages remaining, selected chapter, 17 segments. Source density is inferred from 1080 px / 393 dp; renderer density is approximately 2.625. The implementation is scaled uniformly to 1080 × 176 px for comparison. CSS/browser sizing is inapplicable. The supplied source is already a focused component crop; its corners, outline and cursor remain readable at normalized pixel scale, so a second detail crop was unnecessary.

Findings: no actionable P0/P1/P2 shape differences in the requested scope.

- Fonts/typography: the preview uses the existing reader's Material typography. Both counters are legible and fit without wrapping. The source's exact font rendering/weight is outside this shape-only request; production typography is retained.
- Spacing/layout rhythm: 9 dp rectangular segment bodies, 1.5 dp corner radii, 2 dp preferred gaps, and a 4 dp focus-outline radius match the reference's shallow segment corners and softer rectangular focus. Focus height remains 17–19 dp; the panel's dimensions remain stable.
- Colors/tokens: existing ReaderColors foreground, accent, track, and chrome roles are retained. The reference's more saturated mint and textured background are intentional differences outside the requested shape change; no palette values are introduced.
- Image/asset quality: the visual consists of native standard UI controls. No new icons, illustrations, photos, generated assets or placeholders are used. Native rendering remains sharp at the inspected density.
- Copy/content: the reference comparison fixture shows the same `4 pages left in chapter` and `56/147` labels. Real production text and counters retain their existing source.
- Dynamic geometry: the reference does not provide the book's chapter-start map, so the fixture uses plausible chapter spans. Exact segment widths and cursor position within the selected chapter remain governed by real pagination rather than being forced to match a screenshot.

Comparison history: the previously accepted control used capsule corners. The user's new reference requests rectangles. After changing segment, outline, and interior clipping radii, the combined source/native comparison passes on its first review; no QA-driven corrective iteration was required.

Additional native states: OLED, Light with enlarged text, Beige, landscape, dense TOC, short-chapter expansion, first/last-page cursor positions, terminal one-page chapter, and RTL render successfully (15 previews, no reported render errors). Selected OLED, Beige and both edge-direction previews were inspected. The cursor remains inside the selected outline at the inspected edge positions. The existing seek mapping, interrupted-drag, cancellation, page-turn and short-chapter tests pass: 1088 passed, 5 skipped, no failures/errors. No emulator or physical device was controlled; touch feel and phone acceptance remain owner checks.

Implementation checklist: segment corners updated; focus and cursor clipping corners synchronized; gaps and body height matched; native comparison captured; regression suite and APK build passed.

final result: passed
