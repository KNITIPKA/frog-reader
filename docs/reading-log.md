# Reading Log

Reading Log holds one record per reading, including paper books and books read
elsewhere. It lives independently of the library and Reading Queue. Removing
a book file or library book does not remove the history or its retained cover.

## Behavior

- Opening a new library book creates an **In progress** record. Its endpoint
  is the last opening date, not a claim that the book was finished.
- Passing the final page opens the completion page. In scroll mode, completion
  is detected at the completion item after the text. Review on the last paged
  page is also an intentional completion action.
- The current active reading time is saved before completion. Time on the
  completion page or review screen does not count as reading.
- Leaving the completion page with Back, **Back to library** or **Save and exit**
  saves any rating and review changes automatically, without confirmation.
  Completion is saved in statistics and Reading Log even when both are empty.
- Reopening a finished reading offers **Just browse** or **Start a new reading**.
  Only explicit completion (including a manual **Finished** label) triggers
  this choice; a last-page/100% position alone does not.
  Opening a saved quote or bookmark goes directly to that passage.
- Reopening an **Abandoned** reading offers **Continue reading** or
  **Start a new reading**. Continuing retains its entry, position, start date,
  date precision, time, rating and review, clears the old ending date and
  changes the library and Log status to **In progress**. Starting over keeps
  the abandoned entry and creates a separate reading from page 1.
- **Just browse**, Back and closing the dialog keep the current position and
  leave the previous Log entry unchanged. Browsing does not add time or
  invent dates, even when manual completion has unknown or partial dates.
- **Start a new reading** opens page 1 and starts a separate **In progress**
  record with new dates and a fresh timer. The previous reading, including its
  dates, time, rating and review, stays intact. Reopening the ongoing reread
  resumes it without another prompt. The choice appears after the book opens,
  with animated entrance/exit, press feedback and a visible saving state.
- Each record can be marked **Finished**, **In progress** or **Abandoned**,
  with a visible status label and icon. Editing the active record updates its library
  status; editing an older record leaves the active reading untouched.
- Removing the latest reading of a library book resets its position, timer,
  start/end dates and status. Quotes, bookmarks, notes, ratings and reviews
  remain saved. The next ordinary opening creates a fresh **In progress**
  entry. Removing an older entry does not reset the current reading; manual
  ordering does not change which entry owns library progress.
- Undo restores the removed entry and its former reading position and time,
  preserving annotation edits made since deletion. If another reading has
  already started, Undo restores history without replacing that new reading.
- Dates can be an exact day, a month and year, a year, or unknown. The precision
  is retained. Partial dates use their earliest possible day only for sorting;
  the interface does not display an invented day.
  Exact dates use a calendar with separate month-name and year selectors.
  Both open direct choices, then return to the days. Month/year uses scrolling lists.
  Date, month and weekday names follow the app's English resource language, even
  on a phone with another system language; the first weekday retains the regional
  preference. Year-only uses one scrolling column, from the current year down to
  1900. Month/year and the exact calendar reuse the same year list.
  Horizontal swipes and arrows change the calendar month; exact dates stop at
  today, and the year list ends at the current year. Choosing a year clamps a
  later month to the current month when needed. Days, month choices and year
  choices reserve the same height, so the dialog does not jump between panels.
  Calendar cells use `java.time.LocalDate` and `YearMonth`, including Gregorian
  leap-year rules and actual weekdays; no network data or random days are used.
  Switching from a changed month/year to an exact date requires selecting a
  day. Calendar selections use UTC midnight to avoid time-zone shifts.
- Ratings are optional, from 1 to 5 stars in half-star steps. Reviews belong to
  the chosen reading. Editing an earlier reading does not replace the current
  library review.
- The compact editor shares the book-details and review card components.
  Reading details use a smaller cover and title/author block.
  Details show the book format beneath the dates; retained FrogReader entries keep their format
  after library deletion. Manual readings do not invent a digital format.
  **Other app** is used consistently in the editor, selector and badges.
  The Log uses compact cards with a cover, title, author and unfilled status label.
  Completed readings and ratings use the existing gold completion role. Paper
  and external sources are quiet text; the default FrogReader source and series
  remain available in details. The footer pairs a compact date with five stars, using filled, half-filled
  and outlined icons for the rating; screen readers receive the rating out of five.
  Equal start/end dates display once at their original precision, exact ranges
  share matching months/years, and mixed precisions remain distinct. Ongoing
  readings explicitly label their last opening. Reviews have a two-line excerpt.
  Calendar duration and active reading time share a row, with active time on
  the right; narrow screens and large text stack these fields.
  FrogReader entries show a read-only title, author and retained cover; source
  and identity changes are also rejected by the editor state, including stale
  camera/gallery callbacks and restored drafts from the former form. Manual
  paper/elsewhere entries place labelled **Title** and **Author** fields beside
  a 2:3 cover thumbnail in the same compact identity card. Tapping the thumbnail's
  **+** or pencil opens **Gallery**, **Camera**, and **Remove cover** when present.
  The entire thumbnail is a labelled button; menu actions retain 48 dp targets.
  Large text stacks the cover and identity fields. Fields do not request focus when the editor opens;
  IME Next moves from Title to Author, and Done clears focus and hides the keyboard.
  The remaining fields are source (manual entries), status, dates, stars and
  review. Series and technical metadata are not part of this form.
  Source/status appear first, followed by started/ending. All four detail
  controls share the same inset surface, padding and typography in view and edit modes.
  Add, edit and details are full-page views with the same back-arrow header,
  system-bar insets and canvas. Add/Edit replace the Log content while a draft
  is open; no bottom-sheet handle, scrim or swipe-to-dismiss is involved. Back
  still asks before discarding changes, and saving blocks duplicate actions.
  The shared add FAB is hidden while editing.
  All three views share 20 dp cards on the screen canvas, with 12 dp
  internal padding and 10 dp gaps. Repeated section headings are omitted.
  The standard editor fits at 360 × 740 dp with the keyboard hidden and default
  text size. The form can still scroll for the keyboard, validation messages,
  large text and shorter windows; Save stays outside its scrolling content.
  Review editing has a two-line viewport without shortening stored text.
  Details show up to three review lines and open the complete review separately.
  The ending is always editable, including FrogReader entries in progress.
  Choosing an ending date completes an ongoing entry. Finished entries can
  correct or forget their ending; abandoned entries retain abandoned status.
  The last opening stays a separate automatic event and is never substituted
  for a manually chosen finish date. Returning to In progress clears the ending.
- Ratings use five star buttons: tap to choose a whole star, tap that selected
  star again for a half star, or drag across the row. Clearing restores an
  unrated entry. Log forms use 32 dp stars inside 48 dp touch targets; the
  clear action sits beside the stars when space permits, or in the rating header.
  Accessibility actions retain half-star selection.
- The default order is newest ending date first. Start and ending date orders
  support both directions, with unknown dates at the end. Manual order retains
  its own list positions, right-side drag handles, edge scrolling and accessible
  move actions. The selected mode is saved in DataStore and settings backups.
  All, Reading, Finished and Abandoned filters show counts of readings, including
  separate rereadings. Filtering preserves the chosen order and does not write
  history. Adding a reading outside the active filter returns to All and reveals it.
  In-progress and abandoned ebook cards show a compact percentage badge beside
  their status. In progress uses the current position of that active reading;
  abandoned readings retain the percentage where they stopped, including after
  a reread or library-file deletion. Missing progress is omitted for paper,
  external and historical entries without a recorded position. The badge wraps
  alongside metadata on narrow screens and with large text. Its accessible label
  distinguishes read progress from the saved abandoned stopping point.
  The shared add FAB expands from the bottom dock when entering the Log; the list
  and snackbar reserve clearance below it. Its appearance scales from its
  anchored bottom/end corner and it hides for the editor, sheets, deletion and dragging.
  FAB opacity uses 140 ms for appearing and 100 ms for hiding; scale uses 180 ms
  and 120 ms. Full-page editor entrances take 240 ms and exits 180 ms with a bounded
  fade and 0.97→1 scale. A new reading grows subtly from the FAB's bottom/end
  corner (mirrored in RTL); editing and details use the center. The list stays
  mounted, and the last draft remains visible until its exit ends. Closing can
  reverse an entrance without resetting its progress.
  The shared FAB keeps its own fade/scale timings. It begins returning after
  the outgoing page drops to 25% opacity, but cannot start another draft until
  the page is gone. Hidden details/camera routes preserve its expanded width
  and bottom anchor instead of contracting it. Unknown FAB state stays hidden.
  Details → Edit uses the navigation transition alone, so the editor does not
  replay a second entrance or flash the FAB. Details Back, including predictive
  Back, uses the same bounded exit policy. A fully invisible FAB has no clickable
  node. The live draft remains owned by the Log view model while opening media.
  The sort row opens a sheet containing all sort choices and ordering help.
  Eligible handles are always visible, including under status filters. Drag and
  accessible moves map visible neighbours back to the full sorted list, retaining
  date-group restrictions. Manual order exposes handles for every movable row.
  Swiping a card towards the start (left in English) removes its reading with
  the same Undo as menu removal. Swipe removal is off during an active drag.
  Reading details also offers **Remove reading** from its overflow menu,
  including manual entries and entries without a book file. Confirmed removal
  returns to the Log only after durable persistence succeeds, with Undo there.
  Failed writes retain the entry and offer retry; a failed swipe returns the
  card to place. Undo is a transient UI action, not a persistent recovery log.
- Readings with the same date are ordered by opening/completion time, or start
  time for start-date sorting. Legacy or manually dated records without an event
  time use creation order as the tie breaker. Editing a review does not change
  the chronology. Manual order and the precision of displayed dates are retained.
  In date sorts, right-side handles are always shown for two or more visible readings
  with the same month/year or the same year-only date. Dragging is restricted
  to that group. The saved sequence is chronological: reversing the date sort
  reverses the group too. Start-date and ending-date sequences are saved
  independently, alongside the separate unrestricted Manual order. Exact and
  unknown dates do not gain a drag handle in date sorts. Changing a date ignores
  the old group order; editing a rating or review preserves it.

## Cover capture

Manual paper/elsewhere records offer an in-app camera and gallery picker through
the editor's cover menu: **Camera** and **Gallery**. **Remove cover** clears an optional
cover. FrogReader identities do not offer cover editing. The camera viewfinder
has a 2:3 frame. CameraX's controller shares the preview viewport with capture;
import normalizes orientation, limits decoding size and saves an upright 2:3
JPEG. Camera permission is requested only when opening capture, and the camera
is released when leaving the screen. Gallery selection remains available if
camera access is denied or the device has no camera.

The implementation follows the official [CameraX controller documentation](https://developer.android.com/media/camera/camerax/architecture).

## Storage and backup

`ReadingLogEntry` and its partial date/source/status models belong to
`data.model`. The ordered log is in `UserDataStore`, written through
`BookRepository` and `AtomicJsonFile`. New serialized fields have defaults.
Existing started, finished and abandoned library books are migrated into log
records. Legacy deletion suppression with an empty linked history is repaired
by resetting stale library progress, so the next opening can log again.

Legacy whole-star ratings remain decodable; an additive half-star field takes
precedence when present. The current library book references its active record.
An unlogged reread suppresses automatic log updates, retaining old records.
Only the active entry represents current library reading data. A fresh entry
cannot reuse a deleted or historical entry's id. Reader position, time and
completion writes carry the reading id, so delayed saves cannot resurrect a
deleted reading or overwrite a newer one.
Completion timestamps are retained in the entry for same-day sorting, including
after a reread or library deletion. The nullable field keeps existing saves and
backups decodable; changing an ending date clears a mismatched timestamp.
The nullable `progressFraction` snapshot also keeps older saves and archives
decodable. Live positions are observed separately from log entries, so page
turns continue to write only `progress.json`. The repository captures progress
when abandoning a reading, ending its ownership of the library position or
removing its ebook. A later progress save or stale editor cannot move an
abandoned snapshot. Resuming the same reading restores live progress; abandoning
it again captures the new stopping point. Older active abandoned entries are
backfilled from their own library position when available. Backup format stays
unchanged, and DATA/FULL archives retain the percentage with the log entry.

`BackupDocument` includes the log and manual order, and the manifest reports its
entry count. **DATA and FULL** in-app backups both include log covers, including
paper photos. FULL additionally includes book files and other library covers.
Restore resolves asset availability and publishes the complete history.

Android's separate quota-limited cloud Auto Backup rules continue to cover the
JSON stores; the in-app archive is the complete backup for log photographs.

## Validation

Repository tests cover persistence, date precision and ranges, half stars,
automatic completion, active last opening, page-turn write isolation, rereads
with and without logging, historical-review isolation, status changes, undo,
library deletion, legacy ratings, failed writes and DATA/FULL cover restoration.
UI state tests cover ordering and date ranges; reader timer tests cover active
time accounting. Synchronization tests also cover latest/older-entry deletion,
all-entry deletion, new ids after deletion, legacy suppression repair,
abandoned continuation, scoped late writes and Undo after a new reading starts.
Sorting tests cover a new opening after completion on the same day, reopening
an older reading, completion-time ordering, both directions, date-only fallbacks
and review edits. Persistence/backup tests cover completion timestamps and
decoding legacy entries without them.

CLI verification: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test :app:assembleDebug --no-configuration-cache --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false`.
The Log synchronization and sorting updates were verified on 2026-10-06: 1,149 tests passed, 5 were skipped,
with no failures or errors, and debug assembly succeeded. `ReaderRereadTest`
covers finished/abandoned prompts, unchanged history while browsing, manual
completion without dates, progress reset, repeated taps, reopening a reread,
abandoned continuation, retry after a failed save, delayed writes after deletion
and the first position save while a fresh reading id is being created.
`ReadingLogSynchronizationTest` covers deletion, Undo and current-reading
ownership with real durable stores. Persistence tests also cover unknown and
partial completion dates. Native preview fixtures include all three themes,
narrow/large-text, landscape, abandoned, saving, resuming and retry states;
they were compiled, not visually rendered.

Camera preview/capture, keyboard layout, drag gestures, animations and TalkBack
must also be checked on an actual phone. Compiling debug previews is not visual
or hardware verification.

The unified details/compact editor redesign and date-picker refinements were
verified on 2026-10-09:
`./gradlew test :app:assembleDebug :app:assembleRelease` passed, with 1,328 tests
passing and 5 skipped; release shrinking with R8 succeeded. New regression tests
cover protected FrogReader identity, stale cover callbacks, draft restoration,
long imported identities, review-only saves without ebook rewrites, and retry
after a partial metadata/review save. Native Compose renders were visually
inspected for Light, Beige, Midnight, narrow/large-text, short-height and invalid
title states, plus calendar, month/year, year-only and unknown-date pickers.
Date-picker tests cover UTC, leap days, localized calendar alignment, year bounds
and preserving partial dates. Ending-date tests verify that FrogReader entries
can be completed and corrected durably, with the library date updated as well.
This does not imply phone gesture, IME or camera verification.

The partial-date ordering, language, year list and compact Log refinements were
verified on 2026-10-09 with `./gradlew test :app:assembleDebug :app:assembleRelease`:
1,347 tests passed, 5 skipped, no failures; release R8 and vital lint succeeded.
New regressions cover direction-aware scoped drag order, independent start/end
axes, unequal-height drag boundaries, cancellation, durable/failed writes,
concurrent additions, stale editor ranks, format retention and backup round trips.
Calendar checks cover every month in 1900–2100, all seven possible week starts,
known weekdays and century leap-year exceptions. Thirteen native Compose renders
were inspected across Light, Beige and Midnight, Ukrainian device locale, and
narrow/large-text states. Phone drag gestures and TalkBack remain unverified.

The follow-up card, calendar and deletion refinements were verified on
2026-10-09 with `./gradlew test :app:assembleDebug :app:assembleRelease`:
1,361 tests passed, 5 skipped, no failures or errors; release R8 and vital lint
succeeded. New regression tests cover date-summary precision, current-date
calendar boundaries, month swipes, durable details deletion, failed-write retry
and the details-to-Log Undo handoff. Nineteen native Compose renders were
visually inspected across Beige, Midnight, Ukrainian device locale, narrow,
large-text and short-height states. Exact-date day, month and year panels had
the same measured dialog height. On short screens the calendar remains
vertically scrollable. Installation does not verify physical swipe/drag
interaction or TalkBack on the phone.

The Reading Log screen redesign was verified on 2026-10-09 with
`./gradlew test :app:assembleDebug :app:assembleRelease`: 1,365 tests passed,
5 skipped, no failures or errors; release R8 and vital lint succeeded. New
presentation regressions cover exact and partial date ranges, missing dates
and preservation of separately sorted rereadings under status filters.
Eleven native Compose fixtures were visually inspected for Light, Beige,
Midnight, 320 dp / 1.5× text, finished readings, reorder mode, sort controls,
empty history and empty filters. Device swipe/drag, editor Back/keyboard behavior and TalkBack
remain unverified.

The follow-up restores always-visible eligible drag handles, five filled/half/
outlined rating stars and the shared dock FAB. The 2026-10-09 verification
(`test :app:assembleDebug :app:assembleRelease`) passed with 1,367 tests passing
and 5 skipped; R8 and vital lint succeeded. New tests exercise both directions
of filtered reordering, hidden neighbours and date-group boundaries. Native
Light, Beige, Midnight and narrow/large-text renders were inspected. Physical
drag gestures and FAB transition timing still need device interaction checks.
