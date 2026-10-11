# Frog Reader BETA 1

**2026-10-11 · Android 8.0+ · Version code 4 · GPL-3.0-only**

FrogReader's first public beta brings a personal reading hub, a reading queue,
separate reading histories, new page-turn renderers and a substantial publisher
rendering update. This release covers the changes since **ALPHA 3**.

## Install and upgrade

Download **`FrogReader-BETA-1.apk`** from the
[Beta 1 release assets](https://github.com/KNITIPKA/frog-reader/releases/tag/beta-1).
The accompanying **`SHA256SUMS.txt`** records the APK checksum.

Export a backup in **Settings → Backup** before upgrading. Beta 1 keeps the
application ID and signing certificate used by the published Alpha 3 APK, so
it can update that installation while retaining app data. Existing per-book
settings and appearance choices are preserved; Welcome runs on new release
installations rather than replaying after an update. Backup format remains **2**.

This APK retains the original Android Debug certificate for update compatibility.
The release build requires an explicit legacy-signing opt-in; source builds can
instead use an external signing key or produce an unsigned release. A differently
signed installed copy cannot be updated in place with this APK.

## What's new

- **A personal reading hub.** Editable profile name/avatar, last-read time,
  weekly activity, streak and daily reading goal, with direct access to the queue,
  log and saved passages.
- **Reading Queue.** Prioritize books with drag ordering, notes and optional
  file attachments. Start reading directly from the queue, or remove a book
  with Undo. Attached files enter the library when you start them.
- **Reading Log.** Keep one record per reading, including paper books and books
  read in another app. Record status, exact or partial dates, half-star ratings,
  reviews and covers. Filter/sort history, reorder eligible same-date entries,
  and add/edit through full-screen forms.
- **Rereads and safe history.** Continue an abandoned reading or start a fresh
  one without losing the old record. Browse completed books without altering
  history. Completion saves on exit, even without a review. Active progress and
  abandoned stopping percentages are shown in the log; deletion and Undo respect
  newer readings and annotation edits.
- **Quotes & Bookmarks.** Search and manage saved passages, edit notes, copy/share,
  delete with Undo and return to the text. Quote colors support nested and
  overlapping highlights.
- **New reading controls.** Floating/Classic layouts and Slide, Stack, Page curl
  or None page turns. Paper curl follows the finger, supports cancellation and
  rapid consecutive gestures, and mirrors for RTL books. A page-counter toggle
  and per-chapter remaining-page choice give control over the footer.
- **Chapter-aware progress.** Expanded active-chapter segments, live captions,
  haptics and a readable linear fallback for densely divided books.
- **Typography fixes.** Independent settings for each book, bundled IBM Plex Sans
  alongside Literata, and publisher heading alignment without the retired global
  centering override.
- **More faithful publisher rendering.** Structural CSS, whitespace, hidden text,
  embedded inline fonts, poetry, image bounds and dark artwork handling. Long
  dialogue tables retain their rows; supported narrow table layouts stack cleanly.
  Native MathML fractions/roots/scripts and above-base ruby readings share layout
  geometry with pagination and selection.
- **App polish.** Welcome setup, refined Light/Beige themes, unified Book Info/Edit,
  library/reader motion, dock/Add transitions, route-aware predictive Back and
  themed notifications with accessible timeouts.
- **GPL 3.** Application code now uses **GPL-3.0-only**, with offline in-app license
  access. Bundled fonts, libraries and other external resources keep their own
  licenses and notices.

The [full changelog](https://github.com/KNITIPKA/frog-reader/blob/beta-1/CHANGELOG.md) includes the storage, parser,
performance and compatibility changes.

## Known limits

Beta 1 remains a prerelease. Dedicated **Statistics**, **Achievements** and
profile-wide search are disabled **Coming soon** controls; profile activity,
goals, the queue, log and saved passages work now.

PDF, KFX, DRM-protected books, full fixed-layout EPUB/KF8 rendering, vertical
writing and embedded audio/video are unsupported. CSS, MathML and ruby support
are bounded native subsets with readable fallbacks. Paragraphs containing native
inline objects have documented justification/line-breaking compromises. See the
[EPUB audit](https://github.com/KNITIPKA/frog-reader/blob/beta-1/docs/epub-standard-ebooks-audit.md) for rendering details.

JVM checks, release assembly and native desktop previews do not establish
physical-device gesture smoothness, full-book visual fidelity, TalkBack behavior
or performance across Android vendors. No new device interaction testing is
claimed for this release.

## Verification

- Local JVM suite: **1,579 passed, 5 skipped**, no failures or errors.
- Debug and optimized release assembly succeeded; R8 code optimization and
  resource shrinking ran for the release APK.
- Debug and release Android lint completed with **0 errors**. Existing warnings
  remain (dependency advisories, unused resources and API/style suggestions).
- Seven native About previews were checked, including narrow and large-text
  layouts. Licence viewer headers were checked; full asynchronous text loading
  and physical-device interactions were not visually verified.
- The release APK's version, non-debuggable manifest, alignment, signature and
  packaged licence texts are checked before upload. Its certificate matches
  the published Alpha 3 APK.

## Source and license

The application is licensed under [GPL-3.0-only](https://github.com/KNITIPKA/frog-reader/blob/beta-1/LICENSE). See
[third-party notices](https://github.com/KNITIPKA/frog-reader/blob/beta-1/THIRD_PARTY_NOTICES.md) for bundled external material.
Source for this binary is available at the exact
[`beta-1` tag](https://github.com/KNITIPKA/frog-reader/tree/beta-1), including build
instructions and pinned dependencies. GitHub provides the matching
[source ZIP](https://github.com/KNITIPKA/frog-reader/archive/refs/tags/beta-1.zip)
and [source tarball](https://github.com/KNITIPKA/frog-reader/archive/refs/tags/beta-1.tar.gz).

Please report bugs through [GitHub Issues](https://github.com/KNITIPKA/frog-reader/issues)
with the app version, device/Android version and reproduction steps. Share sample
books only when you have permission to redistribute them.
