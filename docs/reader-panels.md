# Reader panels and numeric side margins

The Settings and Contents panels open from the reader footer. App Settings →
Reading → Reading behavior contains two independent options, both off by default
for new installs and existing settings without the new keys:

- **Show handle in collapsed menu** keeps the three-line grip above the footer
  while the reader menu is visible and its panels are closed. An open panel
  always shows its handle. Hiding the menu for reading always hides the grip.
- **Expand panels on swipe** lets upward drags inside settings or chapter lists
  expand the panel before scrolling. At full height the content keeps its scroll
  and fling. Downward drags reach the content top before reducing panel height.
  With the option off, content scrolls normally at compact height and does not
  resize the panel.

The visible grip is 20 × 7.5 dp in a 24 dp strip, with a wider centred tap target.
Dragging it changes panel height regardless of the auto-expansion preference.
Tapping opens the last panel, expands a compact panel, or returns an expanded
panel to compact height. On a short window where compact fills the available
space, collapse closes it. Android Back dismisses the panel before leaving the
book. Fast pointer deltas accumulate before the next animation frame.

Both preferences are global AppSettings persisted with DataStore and included
in settings backups. They are not stored as per-book reader overrides.

Settings have a slim, rounded 2 dp scrollbar whenever they overflow. A dedicated
12 dp gutter in the measured content width separates it from buttons and cards.
Its size and position follow the visible fraction; it brightens softly during
scrolling and has no background rail. The handle remains outside scrolling
content. Paired controls use equal columns; narrow windows and enlarged text
stack the pairs before their values become cramped.

Pill choices such as reading mode, theme, font and reader chrome use the shared
[animated segmented control](segmented-controls.md). Taps animate the capsule;
dragging the selected capsule previews another choice and commits on release.

The page counter has its own settings group with a switch and three selectable
mini-page cards: **Page only**, **Page / Total**, and **Chapter**. Cards preview
the open book's current page, complete page count, and pages remaining in the
current chapter. Until complete pagination is available, they show dashes and a
loading explanation. Chapter ownership matches the printed footer, including
pages shared by two chapters. The existing per-book counter preferences still
apply immediately and retain the selected style while the counter is off.
Scrolling mode shows a disabled switch with a Pages-mode explanation.

The selectable preview tiles and the miniature pages inside them are square,
with centered labels below each tile and no visible radio buttons. Selection
uses the accent outline while retaining single-choice accessibility semantics.
Numeric counters stay on one line and size
their text to fit, including `30000/30000`. At narrow widths, larger font scales,
or when the complete number needs more room, the tiles wrap into two or one
centered columns. Labels always stay below their square previews.
The miniature paper and counter ink use the fixed
reading-page colors; borders, radio controls and selection fills follow the
reader control palette, including Material You. Debug previews in
`PageCounterSettingsPreviews.kt` cover all three fixed themes, dynamic Light and
Midnight, narrow/large text, loading, off, scrolling and the full settings panel.

Line spacing, font size, and side margins all support press-and-hold. A held
button takes its first step after 200 ms, then repeats every 100 ms, accelerating
to every 60 ms after five repeats. Ordinary taps still take one step on release.
Side margins range from 8 to 48 dp in 2 dp steps. Outward arrows widen the text
by reducing margins; inward arrows narrow the text by increasing margins.
Each repeat still changes only one normal step. Releasing, starting a scroll,
hitting a bound, or removing the control stops repetition; release does not add
an extra tap after a long press. Reading, measurement, pagination, linked documents
and reader diagnostics all use the
same bounded value. The setting belongs to the book and survives reopening and
backup. Old Narrow, Normal and Wide values retain 10, 20 and 34 dp respectively.
The old serialized preset is retained; the new optional field defaults to null.
The existing backup format version is unchanged.

Validation covers nested-scroll handoff, repeat acceleration and cancellation,
frame-coalesced input, interrupted animation, per-book saving, legacy and numeric
serialization, backup restoration and pagination cache widths. Native preview
fixtures cover closed, compact, scrolled and expanded panels, Light/Beige/Midnight,
narrow large text and numeric bounds.
Device gesture feel and TalkBack require runtime checks in addition to those
logic and layout checks.
