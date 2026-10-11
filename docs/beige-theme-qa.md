# Beige palette review — 2026-10-05

## Evidence

The owner supplied seven original phone screenshots and three screenshots of
the installed update. Every supplied image was reviewed. These are visual
evidence of those exact captured states, not interaction, TalkBack or arbitrary
cover/wallpaper verification. Numeric contrast ratios are calculated from the
inspected code colors. The English display name is **Beige**, stored as `SEPIA`.

## Original screenshot review

1. **Library grid — clear hierarchy, excessive yellow layering.**
   `Screenshot_20261005-091817.png`: the current-reading card and action are clear,
   and varied cover artwork anchors the collection. Yellow header, canvas,
   badges and dock create too many similarly tinted layers. The dock looks
   heavier than content cards. Changed to restrained linen/ivory surfaces with
   walnut actions and stronger inactive navigation. Preserve all cover art,
   including the vivid blue Python cover: its color belongs to the book.
2. **Reader settings — coherent grouping, enabled controls look disabled.**
   `Screenshot_20261005-091841.png`: selected options and animation outlines
   communicate state well. The muddy panel and translucent groups merge;
   inset steppers look disabled and captions are faint. Old `onSurfaceVariant`
   (`#7C6B51`) on chrome (`#E9DBC0`) is approximately **3.77:1**; on the old canvas
   (`#F3E7D2`), **4.21:1**. Both fall below the 4.5:1 normal-text threshold.
   Changed to ivory panels, opaque insets, ivory stepper faces and stronger text.
3. **Library list — understandable progress, overly dominant wash.**
   `Screenshot_20261005-091828.png`: thumbnail/title/author placement is stable,
   and explicit percentages supplement fill widths. The 40% ochre shelf wash
   doubles as list progress and cuts across metadata. Format/status text also
   uses 60% opacity. Changed the wash to primary at 9%, with full-opacity
   secondary foreground for enabled metadata and shelf counts.
4. **Reading page — good paper and artwork, heavy floating controls.**
   `Screenshot_20261005-091837.png`: serif text and black line art suit the warm
   paper. Muddy chrome and subdued captions weaken control clarity. Preserved
   `#F3E7D2` reading paper and the authored illustration; chrome now uses ivory
   and readable captions. The top overlay covers part of the illustration in
   this captured state; that is separate overlay geometry, unchanged here.
5. **Open add menu — readable actions, inconsistent surface weight.**
   `Screenshot_20261005-091824.png`: icons, labels and the FAB close symbol are
   clear. Menu cards look cleaner than the dark beige dock, while scrimmed
   yellow layers become muddy. Menus and dock now share the ivory family;
   walnut icons/FAB remain the action accents. Placement and behavior are unchanged.
6. **Profile — cohesive dunes, dense yellow-peach action layers.**
   `Screenshot_20261005-091820.png`: identity, activity and saved destinations
   are grouped clearly. The scenery, translucent beige actions and brown icon
   backgrounds compete with content; secondary labels are subdued. Softened
   sky/dune hues and changed landscape actions to 96% ivory, walnut icons on
   neutral insets and a readable dedicated caption role. The avatar is preserved.
7. **Quotes & Bookmarks — comfortable passages, inconsistent selected tab.**
   `Screenshot_20261005-091851.png`: serif quotes, attribution and a thoughts inset
   distinguish passage from personal note. The selected Quotes tab uses nearly
   black `inverseSurface`, unlike selection elsewhere. Changed it to the standard
   `primary` / `onPrimary` pair; strengthened captions and separated ivory cards
   from the quiet `surfaceContainerLow` thoughts inset.

## Review of the installed update

1. **Library grid — retain.** `Screenshot_20261005-093655.png` confirms a cleaner
   linen canvas, lighter search/hero/dock surfaces and consistent walnut actions.
   Cover badges are readable. The selected dock segment remains distinct without
   competing with the main button. Cover imagery stays intact; no additional
   palette change is justified by this image.
2. **Reader settings — retain palette.** `Screenshot_20261005-093706.png` confirms
   the major correction: opaque neutral groups with clearly separate light
   stepper faces, readable section captions and consistent selected controls.
   Animation states use fill plus outline. The partially visible next section
   above the fixed footer is the clipped edge of the scroll viewport; source
   inspection confirms the footer follows a separately height-limited scrolling
   settings area. It does not indicate text drawn underneath the footer. No
   geometry or palette change is needed for this captured scroll position.
3. **Profile — retain.** `Screenshot_20261005-093658.png` confirms quieter artwork,
   ivory landscape actions, clear activity/section captions and consistent dock.
   A subdued search placeholder is intentional: `ProfileDashboard` currently
   sets `searchEnabled = false`; it is not an active field with an accidental
   disabled-looking color. The actual avatar and all other artwork are preserved.

The three updated captures substantiate the palette improvement for the grid,
settings and profile. Updated list, open import menu, reading page and saved
passages have source/contrast coverage but no updated owner-phone captures in
this review. The palette was retained after reviewing the installed update.

## Maintained implementation

- `Theme.kt`: complete Beige Material scheme and fixed roles; derived app chrome,
  scoped landscape tokens and independent reading-page background.
- `ReaderColors.kt`: opaque Beige control groups and distinct stepper faces.
- `QuotesAndBookmarksScreen.kt`: selected tabs use the standard action pair.
- `LibraryScreen.kt`: enabled metadata/counts no longer use a faded foreground.
- `docs/beige-theme.md` plus `AGENTS.md`: role, component and verification rules
  for future screens. No new hex palette should be declared in a screen.
- Debug previews cover grid/list/open menu, profile, reader chrome/settings and
  saved passages, including narrow/large-text states. Fixtures are memory-only.

## Verification

- JBR `./gradlew test :app:assembleDebug` passed after production and preview
  changes: **1068 tests, 0 failures, 0 errors, 5 skipped**.
- `ThemePaletteTest`: **19 tests**, all passed. Text pairs are tested at 4.5:1,
  significant control outlines/active tracks at 3:1; composited backgrounds,
  landscape actions and overlapping selections are included.
- The owner supplied installed-update screenshots for the three states above.
  They were visually reviewed, but the owner has not explicitly confirmed
  interaction behavior or acceptance of every updated screen.
- Android Studio was opened after the owner's permission, but the new native
  previews were not successfully rendered/inspected before the owner explicitly
  stopped preview use. No native-preview verification is claimed. Further
  preview use for this request is waived by the owner's instruction; visual
  evidence is the supplied phone screenshots, with CLI checks kept separate.
- `git diff --check` passed. No additional production palette changes were made
  after the three updated screenshots; only the palette contract and review
  documentation were completed.
