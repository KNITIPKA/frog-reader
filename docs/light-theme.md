# Light theme — clean neutrals and forest green

The fixed Light theme is a quiet frame for books. Use clean, slightly cool neutral surfaces,
white content cards, dark ink, and forest green for actions. Reserve fresh mint for
selection; do not tint every surface green. The implementation is in
`app/src/main/java/com/example/frogreader/ui/theme/Theme.kt`.

The separate fixed Beige palette follows [beige-theme.md](beige-theme.md).

## Palette and roles

| Role | Light value | Use |
| --- | --- | --- |
| `background` / `surface` | `#F4F6F7` | Screen canvas and system-window background |
| `surfaceContainerLowest` | `#FFFFFF` | Content cards, floating navigation, light popups |
| `surfaceContainerLow` | `#FAFBFC` | Subtle supporting surfaces |
| `surfaceContainer` | `#F1F3F5` | Inset groups, inactive day cells, fields |
| `surfaceContainerHigh` | `#E9EDEF` | Secondary controls and neutral icon buttons |
| `surfaceContainerHighest` | `#E1E6E9` | Strongest neutral fill, placeholders |
| `onSurface` | `#222B27` | Titles and body text |
| `onSurfaceVariant` | `#58635E` | Captions, metadata and inactive icons |
| `primary` / `onPrimary` | `#246B50` / `#FFFFFF` | Main action, FAB, selected reader option |
| `primaryContainer` / `onPrimaryContainer` | `#DDF0E6` / `#20513C` | Selected navigation and tonal selection |
| `secondaryContainer` / `onSecondaryContainer` | `#EEF2F4` / `#3B5549` | Supporting icon tiles in settings and profile |
| `tertiary` / `tertiaryContainer` | `#786039` / `#F0E4CC` | Optional warm accent for an additional semantic category |
| `outline` | `#78847E` | Visible control boundaries |
| `outlineVariant` | `#D8DFE2` | Dividers and inactive progress tracks |
| `error` / `errorContainer` | `#AC4138` / `#FFE5DF` | Destructive actions and errors only |

## Component rules

1. Read `MaterialTheme.colorScheme` in app composables. Pair a fill with its
   matching `on…` role. Never use a page background as button text, hardcode
   a hex in a screen, or use alpha to make secondary text faint.
2. Screens use `surface`; content cards use `surfaceContainerLowest`.
   Keep white cards distinct through the canvas, spacing, and modest shadows.
   Regular cards need 0–2 dp shadow; the floating dock uses 3 dp. Tonal elevation
   can recolor a surface, so light popups explicitly use `tonalElevation = 0.dp`.
3. Use `colorScheme.floatingSurface` for menus and dialogs. It selects a light
   surface for light schemes and a raised dark surface for dark schemes.
   Import-menu options use this neutral surface, normal text, and primary icons;
   the main add button carries the solid accent.
4. Primary actions use `primary` / `onPrimary`. Secondary actions use neutral
   containers or text buttons. Selected navigation and backup choices use the softer
   `primaryContainer` pair. Unselected segmented groups use `surfaceContainer`. Do not give every action a colored fill.
5. Settings and profile icon tiles use the same secondary-container pair.
   Backup is a normal destination, not a selected or promoted action.
6. Use `LocalFrogColors` for the library header, cover badges, shelves, dock,
   and profile identity card. The header fades from a restrained mint to the
   canvas. The profile uses a vector landscape: cool mist and forest in Light,
   warm hills in Beige, and mountains with a crescent in Midnight.
   Its sky, art, identity foreground and translucent action pairs live in
   `FrogExtraColors` / `ProfileLandscapeColors`; Material You derives them from
   the selected system scheme. Keep this decoration scoped to the profile.
   Light profile action tiles are opaque white with neutral borders and captions;
   only their icons carry the forest accent, on a neutral grey icon background.
7. Reader page colors and reader control colors are separate. The Light page
   stays pure white so white-backed book illustrations blend into the page.
   Use `ReaderColors.chrome`, `control`, `controlStrong`, `controlButton`, `track`, `onChrome`,
   `onChromeVariant`, `accent`, and `onAccent` for the reader UI. Publisher
   styling, cover art, and image-export backgrounds are content, not app chrome.
   Stepper buttons use a white `controlButton` face inside the neutral group,
   so active controls do not look disabled or merge into their container.
   Both reader styles use the same floating `ReaderColors` palette for bars and
   expanded settings. Opening or closing a drawer must not change that palette.
   Classic panels have a soft 6 dp shadow falloff toward the page instead of
   stacking a divider and platform elevation. Tonal elevation remains zero.
8. Fixed Light neutrals must not drift toward yellow, cream, or olive. Avoid
   mixing warm canvas colors with green-grey controls: next to white cards, the
   combined cast looked dirty in the device screenshots. Neutral surfaces have
   almost equal RGB channels, with blue at least as high as red and green.
   Keep warmth for the separate Beige theme and optional tertiary accents.
   Completed library books use the dedicated `completionFill` / `completionInk`
   gold pair: a soft full-row background and readable gold percentages, also
   on cover badges. This semantic accent remains gold with Material You.
9. Theme-picker swatches come from `colorSchemeFor(theme)`. Material You remains
   opt-in and owns the complete system scheme. Derive its extra roles from that
   scheme; never place fixed forest/sage colors in dynamic UI. Beige retains its
   fixed palette; Light and Midnight can use Material You.

## Verification for future changes

- Run `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`.
- `ThemePaletteTest` checks text contrast ≥ 4.5:1, important control contrast
  ≥ 3:1, translucent header/profile/selection combinations, neutral surface
  ordering, neutral hue, distinct stepper faces, and the separation of fixed reading pages from dynamic controls.
  Do not weaken thresholds to accommodate a new palette.
- Inspect the native debug previews in `ui/profile/LibraryChromePreviews.kt`,
  `ui/profile/ProfilePreviews.kt`, `ui/theme/LightThemePreviews.kt`, and
  `ui/theme/LightSettingsControlsPreviews.kt` after refreshing the build. Include Light, Midnight, 360 dp, large text, and open menus.
- Check selected, unselected, disabled, error, and open-overlay states. Color
  supplements icons, text, and semantics; it must not be the only state cue.
- Contrast checks validate the chosen pairs, not full accessibility compliance.
  Device rendering, touch, TalkBack, and wallpaper-derived palettes still need
  their own checks when those behaviors change.
