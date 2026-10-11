# Beige — linen, ivory and walnut

**Beige** is the English display name. The persisted enum remains `AppTheme.SEPIA`
for existing settings and backups. Beige always uses its fixed palette, including
when Material You is enabled.

The design uses a restrained linen canvas, lighter ivory content and floating
surfaces, espresso text and walnut actions. Insets are neutral warm taupe.
Warmth must not become a yellow wash on every layer. Dunes belong only to the
profile identity card. Book covers and publisher artwork remain authored content.

The single palette source is `app/src/main/java/com/example/frogreader/ui/theme/Theme.kt`.
Screens consume `MaterialTheme.colorScheme` and `LocalFrogColors`; reader controls
consume `ReaderColors`. Never copy the hex values below into screen composables.

## Palette contract

| Role | Value | Purpose |
| --- | --- | --- |
| `background` / `surface` | `#F2EBE0` | Linen canvas and app window |
| `surfaceContainerLowest` / `floatingSurface` | `#FFFAF2` | Ivory content cards, dock, menus and dialogs |
| `surfaceContainerLow` | `#FAF4EB` | Quiet nested content |
| `surfaceContainer` | `#EEE5D8` | Inset groups, fields, day cells and list rows |
| `surfaceContainerHigh` | `#E6DACA` | Neutral icon controls and stronger insets |
| `surfaceContainerHighest` / `surfaceDim` | `#DCCDB9` | Deepest neutral layer and placeholders |
| `onSurface` / `onBackground` | `#30291F` | Espresso body text and titles |
| `onSurfaceVariant` | `#645443` | Full-opacity captions, metadata and inactive icons |
| `primary` / `onPrimary` | `#745031` / `#FFFFFF` | Walnut actions, selected tabs, switches and active progress |
| `primaryContainer` / `onPrimaryContainer` | `#EDDDC9` / `#49321F` | Tonal selection and selected navigation |
| `secondary` / `onSecondary` | `#665746` / `#FFFFFF` | Supporting filled actions |
| `secondaryContainer` / `onSecondaryContainer` | `#EBE0D1` / `#574736` | Settings and profile icon tiles |
| `tertiary` / `onTertiary` | `#805854` / `#FFFFFF` | Optional muted rose semantic category |
| `tertiaryContainer` / `onTertiaryContainer` | `#F3DED8` / `#563530` | Supporting tertiary category; use sparingly |
| `outline` | `#887661` | Boundaries that communicate a control |
| `outlineVariant` | `#D3C3AF` | Decorative dividers and inactive tracks |
| `inverseSurface` / `inverseOnSurface` | `#382D23` / `#FAF4EB` | Inverse content |
| `error` / `onError` | `#98392F` / `#FFFFFF` | Errors and destructive actions |
| `errorContainer` / `onErrorContainer` | `#FBE2DC` / `#742B24` | Destructive confirmation surfaces |
| `scrim` | `#241C14` | Overlay dimming at the component's opacity |
| `AppTheme.SEPIA.readingPageBackground` | `#F3E7D2` | Stable reading paper and matching reader panels |

Material fixed roles are explicitly assigned in `BeigeColorScheme`. Their
foreground/fill pairs belong to the walnut, taupe and rose families; new Material
components must not inherit default purple fixed roles.

## Component rules

1. Pair a fill with its corresponding `on…` role. Enabled secondary text uses
   `onSurfaceVariant` at full opacity. Alpha is for artwork, decorative washes,
   overlays and actual disabled states, not faint enabled metadata.
2. Screens use `surface`; content cards use `surfaceContainerLowest`. Insets use
   `surfaceContainerLow` or `surfaceContainer`. Maintain the descending brightness
   order: lowest, low, container, high, highest. List progress intentionally uses
   a subtle full-row wash behind metadata and an explicit percentage label.
3. Menus/dialogs use `colorScheme.floatingSurface`; reader panels use
   `ReaderColors.chrome`. Use zero tonal elevation on ivory floating surfaces
   to prevent an additional accent tint. Normal card shadows are modest (0–2 dp);
   the dock uses 3 dp. Preserve the established shared geometry.
4. Main actions and filled selected tabs use `primary` / `onPrimary`.
   Selected navigation uses `primaryContainer` / `onPrimaryContainer`.
   Secondary and unselected controls remain neutral. `inverseSurface` belongs
   to inverse content, not selected tabs.
5. Reader controls use the same shapes, group backgrounds and filled-selection
   treatment as Light. Only their palette changes. Chip rows (theme, side margins,
   reading mode, font and similar choices) and steppers retain their rounded
   `control` backdrop in warm taupe. Nested steppers use distinct paper-beige
   `controlButton` faces. Values use `onChrome`, captions use `onChromeVariant`,
   actions use `accent` / `onAccent`.
   Selected settings use a solid walnut `selectedControl` face with the light
   `onSelectedControl` foreground, matching `accent` / `onAccent`. They have no
   additional outline. Apply the same fill to Auto, selected animation icons
   and selected Floating bar buttons. Unselected buttons retain neutral faces.
   Sliders use `progress` / `onProgress` and `track`: muted secondary walnut
   over the light `surfaceContainerHighest` rail. Do not derive independent
   translucent brown settings surfaces.
   Classic and Floating panels match the reading paper whether the drawer is
   open or closed. Inset groups use `surfaceContainerHigh`, stronger insets use
   `surfaceContainerHighest`; stepper/return buttons retain reading-paper beige
   faces. Floating bar icons retain circular faces and 48 dp touch targets.
   Classic bar icons retain their established bare appearance.
   Espresso icons and captions pair with walnut actions and taupe progress tracks.
   Classic panels have one soft 6 dp shadow falloff toward the reading page,
   without stacking a divider and platform elevation. Return buttons use a
   lighter 5 dp blur with a 1 dp offset. Both draw from the theme's `scrim` ink;
   tonal elevation remains zero.
6. Library search, cover badges, shelves and dock use `LocalFrogColors`.
   Beige's extra control roles derive from the Material scheme. The header fades
   from restrained `#F0E3D0` to the canvas; search glass is 90% ivory, cover badges
   96% ivory. The shelf/list-progress wash is primary at 9% opacity so it stays
   behind titles, authors and format labels. Completed books use the dedicated
   `completionFill` / `completionInk` gold pair for their full list-row background
   and percentage, with the same gold ink on cover badges.
7. Profile decoration uses `ProfileLandscapeColors`. Identity text uses
   `onProfile`; action cards use `action` / `onAction`; their captions use
   `actionSecondary`; icon tiles use `actionIconBackground` / `actionIcon`.
   Foregrounds must remain readable over every dune layer. Profile search and
   settings use the same shared glass controls as the library. The profile search
   placeholder remains disabled until profile-wide search is available.
8. Reading paper remains `#F3E7D2`, independently of redesigns to app chrome or
   Material You. Book illustrations, covers, publisher colors and image exports
   are content, not app palette. Cover captions retain their white-text/dark-scrim
   pair so they remain appropriate for arbitrary cover artwork.
9. New features reuse shared controls, spacing, shapes and typography. Palette
   work does not require moving navigation, changing gestures, cropping covers
   or replacing the profile illustration.

Example of a themed content card:

```kotlin
val colors = MaterialTheme.colorScheme
Surface(
    color = colors.surfaceContainerLowest,
    contentColor = colors.onSurface,
    tonalElevation = 0.dp,
) {
    Text(text = caption, color = colors.onSurfaceVariant)
}
```

## Verification for future changes

- Run `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`.
- `ThemePaletteTest` requires tested normal-text contrast ≥ 4.5:1 and tested
  control/progress contrast ≥ 3:1. It checks supporting surfaces, foreground/fill
  pairs, composited search/badges/shelf/list washes, navigation, actions over the
  landscape, overlapping quote/selection highlights, warm neutral ordering and
  separation of stepper faces. Never weaken thresholds for a new palette.
- Inspect native debug Compose previews for library grid/list/open menu, profile,
  reader chrome/settings and saved quotes/bookmarks. Include 360 dp and large text.
  Fixtures must be memory-only. Compiling a preview is not visual inspection.
- Check selected, unselected, disabled, error and open-overlay states. Color
  supplements labels, shapes and semantics; it must not be the only state cue.
- Phone rendering, touch, brightness, system bars and TalkBack need their own
  runtime checks when those behaviors change. Screenshots cannot prove them.

Evidence and verification status: [beige-theme-qa.md](beige-theme-qa.md).
