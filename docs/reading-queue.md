# Reading Queue

Profile → Reading Queue is a manually ordered queue. New entries go at the end;
the first card has highest priority. The profile counter observes this queue,
not the WANT_TO_READ status of library books.

## Interface

- The shared dock FAB expands into the bottom-right Add a book action. Its
  opacity animates for 140 ms when appearing and 100 ms when hiding; scale uses
  180 ms and 120 ms respectively. Closing an editor starts its reveal immediately
  alongside the panel's exit. Shadow and content share that reveal layer, with
  no later shadow step. Appearance recovers at dismissal; interaction resumes
  when the modal leaves. A completely faded-out FAB has no clickable node.
- The bottom-right Add a book FAB and tapping a card open a compact bottom
  sheet. New entries focus the title as soon as the form enters composition,
  so the keyboard starts opening alongside the sheet. Editing an existing entry
  does not summon the keyboard until a field is tapped. The sheet applies animated
  IME padding outside its surface and anchors, with a separate safe top boundary;
  keyboard padding does not grow the content or feed back into the top inset.
  New entries draw at full opacity from the first moving IME frame. The draw gate
  does not create a compositing layer or clip the Surface shifted by Material's
  anchors. There is no fade or extra entrance translation: the expanded anchor
  and live IME padding own vertical placement, including the footer and note.
  The Material sheet starts at its expanded anchor, so a separate show spring does
  not chase the changing IME height. Existing entries rise in 240 ms; modal exits
  take 180 ms, without an invisible spring tail. An already-open or missing
  software keyboard reveals a new entry without waiting indefinitely.
  Subsequent focus changes do not hide it. Fields
  scroll within the available height; the header and both action buttons stay
  outside that scroll area, above the actual keyboard insets. Add a file is a
  compact outlined button alongside Save/Add; narrow widths and large text stack
  the two buttons. After attaching, it shows the file format and opens a menu to
  replace the file or remove a new draft attachment. A single quiet hint explains
  that only the title is required; field labels do not repeat “optional”.
  Field labels stay in a fixed position above the input text inside each field.
  Focus changes only the border, label and icon colors, leaving typography,
  padding and field height unchanged so the sheet anchor cannot bounce with a
  floating label. The note counter stays outside the field's border.
  Successful new additions close the sheet and scroll to the appended card.
  Edits do not trigger this scroll; its pending target survives recreation.
- Title is required (200 UTF-16 units); author is optional (160), note optional
  (100). Existing longer notes remain intact, including when editing title or
  author; changing a legacy note requires shortening it to the new limit.
  New notes appear in full on cards, which grow with text size; display-only
  whitespace folding prevents blank lines from stretching them. Legacy notes
  have a three-line preview and remain fully accessible in the editor.
  Draft text and attachment metadata survive activity recreation.
- Cards reuse the library's BookCover and theme roles. A small Up next chip
  marks the first book. There are no printed priority numbers. Every card has a
  64 × 96 dp cover in a consistent metadata row. Notes sit below this row across
  the full card width, so note length does not unbalance the cover area. The menu and drag handle form a compact group at the top-right, menu on the
  left and handle on the right. Both retain separate 48 dp touch targets; their
  position does not depend on title or note length. There are no per-card action
  buttons below the note.
  A paperclip badge on the cover identifies an attached book file.
- Drag the 48 dp handle to reorder; holding at an edge scrolls. Headers are not
  reorder targets. The held card reads its current slot in the graphics layer,
  keeping its viewport position stable across swaps. Only its release is
  animated, in viewport coordinates; starting another drag preserves the painted
  position. Only the released order is written to disk.
- Swipe toward the start edge (left in English) to remove. Undo restores the
  entry, its attachment, and its priority, including while an editor is open.
  Swipe toward the end edge (right in English) to start reading. Without an
  attachment it offers Add a file, which keeps the entry in the queue.
  The menu and accessibility actions provide alternatives to swiping.
- Add a file copies a supported book into private storage. Existing manually
  entered text is retained. Picking a file in a blank draft can fill title/author.
- Start reading publishes the file-backed book to the library, removes the queue
  entry, and opens the reader. Exact file duplicates reuse the existing library
  book and its progress. The queue note transfers to Book.readingQueueNote and is
  visible in Book details.
- Unsaved changes require confirmation before discarding. Failed saves retain
  the draft; failed reorder/deletion saves return to durable state.

## Storage and backups

ReadingQueueEntry is serialized in UserDataStore.readingQueue through AtomicJsonFile.
The existing JSON keys (`readingList`, `readingListUpdatedAtMillis`,
`readingListNote`, and backup `readingListCount`) remain unchanged via `@SerialName`.
Saved-state keys, attachment ID prefixes, and navigation serialization names also
stay compatible with the previous feature names.
Its optional attachment is a Book metadata snapshot whose file is private to the
queue until promotion. Preparing it does not publish a library record. Defaults
keep old stores and archives readable. Entry timestamps, order timestamps, and
deletion tombstones are preserved.

DATA and FULL preserve the ordered entries and attachment metadata. FULL also
includes private attachment files/covers. On a fresh device DATA restores entries
with no ready file; Add a file is available again. Restore cleanup includes pending
attachments in its retained-file set. The archive version remains 2 (additive
fields). Restore replaces the queue; older archives restore an empty queue.

## Validation boundary

JVM tests cover restart persistence, limits, errors, corruption protection, queue
isolation, imports, promotion, exact duplicates, note transfer, delete/undo, draft
restoration, both archive modes, restore cleanup, drag geometry, and header offsets.
Debug Compose previews cover Light, Midnight, a 360 dp screen, large text, and the
bottom-sheet editor, including short/narrow viewports and an attached-file state.
Their source compiles; they have not been rendered/visually checked.

Owner phone checks:

1. Add from the sheet; attach a file and verify it stays out of Library. Saving
   should close the sheet and scroll to the new final card.
2. Swipe right with and without a file. Start reading: Library gains the book, Reading Queue/profile count decreases,
   and Book details contains the original note.
3. Swipe left/Undo. Drag through both edges with the rightmost handle.
4. Check long titles, keyboard, rotation, large text, and both light/dark themes.
5. Open the FAB: the title focuses and the sheet/keyboard begin appearing together.
   During the opening, the whole form, including its footer, should remain above
   the keyboard and follow its upper edge at full opacity, without a blank gap;
   there should be no second upward movement after the keyboard has appeared.
   Switch repeatedly between title, author, and note with the keyboard open;
   both empty and filled fields should keep the same label position and height,
   with only the active field's border and colors changing. The sheet should stay
   stable. Edit an existing card: no keyboard until a field is tapped.
   With the keyboard open,
   verify both Add a file and Add/Save stay visible with different keyboard heights.
   Attach, replace, remove a new draft file, and check the large-text stacked footer.
6. Navigate Profile → Reading Queue and back: the FAB should expand/contract
   in place. Open/close the editor by its close button, Back, and a downward
   swipe; check the FAB reveal and immediate restoration of card controls.
   Verify the main-screen import menu still opens and closes normally.
7. Export/restore FULL and DATA; verify hidden files, notes, covers, and order.

No Android Studio, emulator, or phone automation was used for the redesign.
