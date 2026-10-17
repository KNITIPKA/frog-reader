# Bundled reader fonts

Official GitHub releases checked on 2026-10-05. Fonts are bundled locally;
reading and font selection never download anything.

## Literata

- Author: TypeTogether / The Literata Project Authors.
- Latest release: [3.103](https://github.com/googlefonts/literata/releases/tag/3.103), published 2023-05-19.
- Archive: [3.103.zip](https://github.com/googlefonts/literata/releases/download/3.103/3.103.zip).
- Complete upstream variable TTF files, copied without modification:
  - `fonts/variable/Literata[opsz,wght].ttf` → `app/src/main/res/font/literata.ttf`.
  - `fonts/variable/Literata-Italic[opsz,wght].ttf` → `app/src/main/res/font/literata_italic.ttf`.
- Internal font version: 3.103. Weights: 200–900. Optical size supports 7–72 (font default 12); the reader adapts it to the effective text size.
- License: SIL OFL 1.1, included verbatim in `app/src/main/assets/licenses/literata-OFL.txt`.

The existing Literata binaries already matched the official release byte for byte.

## IBM Plex Sans

- Author: IBM.
- Latest variable release: [@ibm/plex-sans-variable@0.2.0](https://github.com/IBM/plex/releases/tag/%40ibm%2Fplex-sans-variable%400.2.0), published 2025-06-25.
- Archive: [plex-sans-variable.zip](https://github.com/IBM/plex/releases/download/%40ibm/plex-sans-variable%400.2.0/plex-sans-variable.zip).
- Complete upstream TTF files, copied without modification (not Latin-only web subsets):
  - `fonts/complete/ttf/IBM Plex Sans Var-Roman.ttf` → `app/src/main/res/font/ibm_plex_sans.ttf`.
  - `fonts/complete/ttf/IBM Plex Sans Var-Italic.ttf` → `app/src/main/res/font/ibm_plex_sans_italic.ttf`.
- Internal font version: 3.000 (distinct from the package release number). Weights: 100–700. Width supports 85–100% (font default 100) and follows explicit publisher CSS or selected instance coordinates.
- License: SIL OFL 1.1, included verbatim in `app/src/main/assets/licenses/ibm-plex-OFL.txt`.

## Reader behavior and compatibility

Literata remains the initial reading default, suitable for fiction and essays.
IBM Plex Sans replaces the system Sans choice and is available for technical reading.
Both chips preview their own fonts; regular and true italic styles are registered
at every supported integral weight. Paragraphs, headings, table cells and
pagination use the same existing `fontFamilyFor` resolution.

The saved identifiers remain `LITERATA`, `SERIF`, `SANS`, and `CUSTOM`.
`SANS` now resolves to bundled IBM Plex Sans; no stored data or backup format changes.
Publisher formatting continues to resolve explicit CSS generic families and
embedded fonts as before. On leaving publisher formatting, its generic Sans
category adopts IBM Plex Sans, the reader's new Sans default.
Custom font import and its Literata fallback remain available.

All four font files were checked for Latin, Russian, Ukrainian and Belarusian
letters, including `ЁёІіЇїЄєҐґЎў`, and their OpenType tables were parsed successfully.
Device rendering and the narrow font selector still require phone verification.

## Custom font library

The file picker accepts multiple TTF, OTF or TTC files in one operation. The
library shows family names first; tapping a family opens Auto followed by its
imported styles (Regular, Medium, Bold, Italic, and so on). Choosing Auto or a
style applies it to the reader. Adding files or opening a family keeps the base
selection; a newly imported matching face can extend its available formatting.
Family names and style labels preview their own real font face, including weight
and italic. Compact tonal rows have spacing instead of separators, with an
explicit checkmark and selection semantics. Auto has a slightly larger card
with an Auto badge, the subtitle “Use entire font family”, and the separate
explanation “Automatically matches font styles to the book.” The dialog uses
the theme's floating surface with an outline and shadow, independently of the
reading page and reader panels; native previews use real bundled fonts over a
dimmed reading page.

Family and style labels come from the OpenType naming table, preferring the
typographic family/subfamily (name IDs 16/17) over legacy names (1/2). Fonts
without usable names fall back to their filenames. Existing imported files are
grouped automatically, and saved per-book font paths remain unchanged. The
chosen file continues to supply the reader's base face in manual mode. TTC files retain the
existing first-face behavior. Variable files expose their named instances from
the OpenType `fvar` table, including their exact weight, width, optical size and
other coordinates. A file without named instances offers standard hundred-weight
steps when its visible `wght` axis supports them, plus its default instance.
The selected coordinates are saved with the book, applied to previews and reader
text, and included in pagination cache identity and settings backups. Older
settings without coordinates keep the file's default instance. Already imported
variable fonts gain their styles on the next library load without reimporting.

Long-press a family to delete all its imported files, or a style to delete just
that file. Variable instances share one file; remove it through the family menu.
Deleting a manual base file returns the reader to Literata; Auto keeps the
remaining family files when possible and falls back when the family is empty. Files
inside a book use the same family navigation and remain available for adoption.
Font copies are validated and moved into place through a temporary file;
imports and family discovery run off the main thread. The backup format and
settings identifiers are unchanged.

## Auto, manual styles and numeric weights

Auto registers the genuine imported files at their actual weight and style.
Variable files retain their supported `fvar` ranges, separately from named
instances. Each supported integral `wght` value has a lightweight descriptor;
only the weights text actually requests load native typefaces. `ital` and `slnt`
axes provide genuine styles when supported, with coordinates clamped to their
ranges. Separate italic files are preferred over synthetic styling. Auto uses
the designer's default width and other non-style coordinates, while optical size adapts to text size when supported.

Manual selection fixes the normal reading face: Thin supplies physical weight
100 for a normal (logical 400) request. Explicit numeric formatting and bold
resolve the family's other real weights, while italic base text uses the closest
available genuine italic at the chosen base weight. Selected width and explicitly chosen nondefault optical size remain consistent
across those styles; a named instance at the default optical size can adapt automatically. If a style or weight is missing,
Compose selects the nearest available face within the same family using its CSS
matching rules; synthetic bold/italic remains the final style fallback.

CSS weights 1–1000 survive inheritance, inline overrides, normal resets,
headings, tables, generated content and drop caps. `bolder`/`lighter` follow the
CSS relative-weight table. Fractional weights round to Compose's integral scale.
Static and variable-range `@font-face` declarations preserve their numeric
weights through EPUB/KF8 extraction and publisher font loading. Publisher font
selection still follows `bookStyles`; semantic strong/emphasis remains available
with that setting off. Measurement and both reading modes use the same styles.

`customFontAuto` defaults to false and `customFontFamilyPaths` to an empty list,
so existing global and per-book settings retain their manual file and variable
coordinates. DataStore and existing settings serialization carry these optional
fields; no backup format version change is required. Import/deletion updates the
active family snapshot and invalidates font and pagination caches. The layout
engine version also invalidates pages measured by the old normal/bold pipeline.

Actual deferred file loading uses an `AndroidFont` loader with
`Typeface.Builder` inside `runCatching`, compatible with API 26. Missing or
malformed metadata is ignored; an unusable custom selection falls back to
Literata, and a deferred native-load failure safely permits platform fallback.
Family plans, previews and native typefaces have bounded caches. No font
resolution downloads data or preloads every variable instance.

The supplied Noto Sans 2.015 example has `wght` 100–900 and `wdth` 62.5–100,
nine named weights and Latin/Cyrillic coverage. It has no `ital`/`slnt` axis or
italic face; a separate Noto Sans italic file is needed for genuine italics.
The example remains a local QA input rather than a bundled application asset.

## Automatic typography and publisher CSS

Typography works automatically without a reader settings entry or separate dialog.
Optical sizing follows the effective text size when the font supports it; normal
width is the reader default. Explicit publisher CSS and manually selected font
instance coordinates retain their meanings.

The retired `fontOpticalSizing` / `fontStretchPercent` fields remain decodable for
old books and backups. Reader/global settings normalize them to true / 100%,
and the renderer ignores stale manual values, so an old disabled switch cannot
leave invisible restrictions. CSS can still explicitly disable optical sizing or
request another width while publisher formatting is enabled. A deliberately
selected nondefault optical size or width in an imported instance is preserved.
The backup format version is unchanged.

The engine resolves these inherited CSS properties through paragraphs, headings,
inline runs, tables, generated content, ruby and decorated initials:

- `font-variation-settings`: arbitrary four-character axis tags, with case retained,
  signed/fractional values, list replacement and explicit `normal` resets.
- `font-optical-sizing`: `auto` follows effective text size; `none` uses the font's
  default optical size. Explicit `opsz` coordinates take precedence over both.
- `font-stretch` and its `font-width` alias: percentage/keyword widths; `@font-face`
  width ranges survive extraction, selection and font cache signatures. Static
  imported Condensed/Expanded files also use their actual OS/2 width class.
- `font-kerning` and `font-variant-ligatures`: OpenType features, composed with caps,
  numeric variants and explicit `font-feature-settings`, which has final precedence.

All supported axes are read from the actual font's `fvar` ranges, clamped
individually and applied after high-level weight/style/width selection. Unsupported
axes are ignored independently. Font shorthand resets the inherited settings.
Publications can explicitly name Literata or IBM Plex Sans without embedding
another copy; their own embedded face with the same name still takes priority.
Missing Times New Roman, Arial and Courier New resolve to the bundled Liberation
equivalents described below.

A shared reader plan resolves the final family after block and nested inline size
changes, including system font scaling. The same plan drives text measurement and
painting in paginated/scrolling modes, ruby, inline text boxes and native math
sizes. Source characters, links and selection offsets stay unchanged. Lazy native
loading and bounded family/typeface caches avoid preloading variable matrices.
Pagination uses engine version 23; retired manual width/optical values no longer
affect layout identity.

Existing responsive-table admission is conservative: a narrow rule requiring a
different inline axis tree keeps its source table layout instead of combining
wide text annotations with narrow cell axes.

## Validation — 2026-10-14

`./gradlew test assembleDebug` passed: 1,713 tests, zero failures/errors and
five skipped (1,708 passed). Coverage includes actual face-path selection,
Compose's weight matching, Auto/manual switching, missing styles, arbitrary
variable weights, axis preservation, safe lazy descriptors, numeric EPUB/KF8
styles, publisher selection, old settings and pagination cache identities.
Additional coverage verifies the real EPUB extraction path, automatic optical size,
CSS width ranges, OS/2 static width classes, nested and reset axes, system font
scaling, legacy settings compatibility and bundled family names without embedding.
`git diff --check` passed.

Twenty native API 36 previews rendered cleanly, including Light, Beige and
Midnight, 320 dp / 150% text, Noto's numeric weights, Latin/Cyrillic, and manual
Thin versus Auto at the same logical normal weight. Two additional API 26
previews produced images with runtime API 26 confirmed, but layoutlib reported
`GraphicsViewLayer` / `PlaceholderCanvas` draw errors; they are partial evidence,
not clean Android 8 validation. Physical-device rendering, interactions and
TalkBack were not tested. Family-selector render evidence is under the local
ignored artifact directory `.artifacts/20261014-font-families/`.

Before removing the manual typography dialog, ten native API 36 typography previews passed and were visually inspected
in `.artifacts/20261014-typography-controls/`: six themed dialogs, two 320 dp /
150% text dialogs, same-size optical/width comparisons, and an end-to-end
HTML/CSS-to-reader specimen. Literata changes glyph shapes and spacing at the
same 32 sp; IBM widths 100/90/85 visibly change line advances at the same 24 sp.
`typography-demo.epub` provides a local practical example using bundled-family
CSS names without font copies; turn publisher formatting on when reading it.

The final Auto-card refresh has eight clean native API 36 previews in
`.artifacts/20261014-auto-card-refresh/`: Auto in Light, Beige and Midnight,
the same themes at 320 dp / 150% text, manual selection, and the supplied Noto
Sans family. The badge, two-line Auto content, separate explanation, selection
contrast and scrolling list were visually inspected. Tests also cover retiring
saved disabled optical sizing/manual width, preserving per-book font choices,
reopening the book, backup round-trips and pagination identity. The Typography
dialog and its reader-settings entry have been removed.

## EPUB subset families — 2026-10-14

The supplied Ukrainian *Anne of Green Gables* EPUB contains three valid
style-specific Times New Roman subsets. Regular has 114 Unicode characters,
Italic 70 and Bold 63. Regular does not contain uppercase И/Ь used by its
chapter heading; Bold does. None contains the complete Latin family name.

Embedded families now expose every shipped face and offer Auto as well as
individual styles. Adopting either choice copies the entire family, so headings
and emphasis keep their genuine Bold/Italic files after publisher formatting is
disabled. Existing regular-only adoptions are repaired on reopening that book
when their bytes exactly match its embedded font, including legacy saved
lists containing one or several copies of only Regular; independent imports with the
same name retain their selection.

Font names and style labels use their actual face and weight, without synthetic
selection bold. The initial covered-text specimen and UI-font substitution were
removed at the user's request: there is no extra “Український текст” line.
Incomplete labels now use one entire Liberation face rather than mixing fonts
within a word. The technical `Subset-` prefix is hidden in display names only;
family identities, files and saved paths are unchanged.
Exact saved copies share the book's family row rather than appearing twice;
their active style and saved-copy deletion remain available. The face's
OS/2/PANOSE generic category selects Liberation Serif, Sans or Mono for missing
glyphs. Platform fallback remains the final route for glyphs Liberation lacks.

Reader items and cached page fragments retain source family annotations.
Measurement and painting resolve those annotations from the current publisher
toggle, avoiding a race that cached old Times spans under newly selected Sans
settings. Authored generic CSS families follow the toggle; semantic code/pre
monospace remains in ordinary, generated and ruby text. Pagination engine version
23 invalidates old affected pages.

`./gradlew test assembleDebug` passed with 1,746 tests, no failures/errors and
five skipped. Before the follow-up fixes, five native API 36 previews using the
actual extracted fonts passed and were inspected, including Beige and 320 dp /
150% text. The subsequent saved-family, duplicate-row and missing-glyph fallback
fixes and the simplified name previews were verified with code tests and
assembly; the user requested no emulator.
The supplied book and font binaries stay in local QA artifacts, outside
application resources and tests. Physical-device behavior remains unverified.

## Liberation fallback — 2026-10-14

- Official release: [Liberation 2.1.5](https://github.com/liberationfonts/liberation-fonts/releases/tag/2.1.5).
- Original binary archive: [liberation-fonts-ttf-2.1.5.tar.gz](https://github.com/liberationfonts/liberation-fonts/files/7261482/liberation-fonts-ttf-2.1.5.tar.gz).
- Twelve upstream TTF files are copied unchanged into `app/src/main/res/font/`:
  Serif, Sans and Mono each have Regular, Italic, Bold and Bold Italic.
- Full upstream SIL OFL 1.1 notice is in
  `app/src/main/assets/licenses/liberation-OFL.txt` and the offline About viewer.
- Upstream identifies document-layout compatibility with Times New Roman,
  Arial and Courier New. These are substitute fonts, not copies of those fonts.

The original embedded/imported face remains primary. The shared typography plan
matches its logical style using Compose's CSS rules, then checks that actual
file's Unicode cmap. Only missing characters move into the appropriate bundled
Liberation family. Manual Bold/Italic and explicit variation coordinates retain
their physical style when falling back. Surrogate pairs, combining sequences and
joined characters stay together; inline replacement objects keep their own
metrics. Text, annotations, links and reading positions are preserved.

The same plan drives measurement and painting on every supported API, including
26. No API 29-only native fallback builder or runtime downloads are required.
Bounded face/coverage caches avoid repeated font decoding. A font-name or style
preview whose whole label is unsupported uses Liberation for the entire label,
with its correct weight/style. Supported labels still use the original file.

`./gradlew test assembleDebug` passed: 1,754 tests, zero failures/errors, five
skipped. Regressions verify all twelve actual binaries' Latin/Ukrainian coverage
and styles, whole-label fallback, selective text fallback, manual styles, genuine
heading Bold, source annotation/color preservation, clusters, CSS name aliases,
and agreement with Compose's own font matcher. No emulator or device validation
was performed for this change.

## SHA-256

| Bundled file | SHA-256 |
| --- | --- |
| `literata.ttf` | `b41138c9373112f32abb589cc22e8674b06ed4048b0c513be922bdd26f274440` |
| `literata_italic.ttf` | `d483dfaeba9cbf4ce71d32a52ee65df82f7e35b15fff8d1011cdb242d1fcd465` |
| `ibm_plex_sans.ttf` | `3ed3ea3b1b3854afdf3306e9ab60773ec03cd7466bf0017131c166d943408713` |
| `ibm_plex_sans_italic.ttf` | `ad691c8be9352a5c024e3d158856b3a073841280347c25bfba2ce75cc349b7ad` |


## Publisher face selection and platform fallback — 2026-10-15

The earlier explicit Liberation missing-glyph policy is superseded for reader text.
The reader now separates CSS face matching, style synthesis, subset coverage and
platform fallback. This is an independent Compose implementation informed by
[crengine's face registration and matching](https://github.com/koreader/crengine/blob/ab1981c22e4e67cf9d98be3c301736941d2d271c/crengine/src/lvfntman.cpp#L6498-L6559)
and [Readium's separate family/face/source model](https://github.com/readium/kotlin-toolkit/blob/1b1f6b308a7b6f968b2bf1e66c84912879466f75/readium/navigators/web/common/src/main/kotlin/org/readium/navigator/web/common/FontFamilyDeclarations.kt#L112-L178).
No upstream engine source or proprietary book fonts are included in the app.

- Real publisher weight/style files remain primary. Every complementary file at
  the same CSS width/weight/style remains available; later CSS declarations take
  precedence where their permitted coverage overlaps.
- `unicode-range` is normalized and retained through extraction, file reuse,
  matching and font signatures. Actual coverage is its intersection with cmap.
- A selected file is loaded independently rather than relying on a native
  multi-file family's default-face coverage. Missing clusters can use another
  compatible face in the same family. Synthetic bold/slant only add styling;
  a genuinely heavier or italic face is not stripped to fill ordinary text.
- Missing styles retain both requested weight and italic, including the legacy
  Android synthesis path. Adding slant to an existing Bold does not add another
  300 units of weight. Nested ruby/math/style changes retain access to the
  original family's genuine siblings.
- For an ordinary missing glyph the preferred Typeface remains in place and
  Android's configured serif/sans/mono fallback handles shaping. There is no
  reading-time forced Liberation replacement and no whole-word replacement rule.
  If CSS explicitly excludes a cluster, a permitted sibling is used first;
  otherwise a generic platform family supplies that cluster with its effective
  style. A physical cmap glyph cannot bypass its CSS range restriction.
- The same plan drives measuring and painting. Source text, authored color,
  links, annotations and UTF-16 offsets survive. Coverage and lazy face plans are
  cached with bounds; font cache invalidation and layout engine version 24 retire
  pages measured using the earlier policy.

Liberation remains available for explicit CSS aliases and font-label previews;
those do not override a usable embedded publisher family. User settings, stored
font selections and the backup format are unchanged. There are no repair prompts,
repair settings or missing-font notices in the reading flow.

The remaining first-family/first-source CSS simplification and the Compose
adapter's cluster approximation mean this is not full browser/crengine parity.
Do not claim broader EPUB fidelity or performance superiority from the focused
font regressions. Physical-device validation remains separate from unit and
Layoutlib evidence.
