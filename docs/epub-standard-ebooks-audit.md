# Advanced EPUB fidelity audit

Audited on 2026-10-10–11 against six Standard Ebooks `advanced.epub` files. Publisher intent here means the XHTML, stylesheets, fonts and artwork in these specific editions.

The engine previously lost some authored content and layout. The corrections below retain those cases in the native model and share their geometry between pagination and scrolling. This is not a claim of complete browser CSS or EPUB conformance; remaining differences are recorded explicitly.

## Corpus and repaired behavior

| Edition | Evidence in the supplied package | Corrections |
| --- | --- | --- |
| Dracula | Nested diary/letter headings, small caps, consecutive blockquote separators, uppercase quoted text | Structural selectors, inherited OpenType features and case transforms; empty border-only generated separators survive; quoted text follows authored empty pseudo content. |
| Flatland | 13 image leaves, four notes, diagrams constrained to `50vh`/`100vh` | Image caps use the actual reading viewport; diagrams retain the edition's dark-mode inversion. All extracted artwork and typed note targets are checked. |
| A Tangled Tale | Three ten-column symbol tables, two notes, 125 MathML expressions, including 27 fractions, 17 square roots and 10 superscripts | Rich cells and authored widths retained; CSS capability queries reach supported native rows. All 125 expression roots enter the bounded native MathML path: fractions, radical bars, scripts and token spacing retain two-dimensional geometry. The prior readable text remains the selection/search fallback. |
| Through the Looking Glass | 55 image leaves; hundreds of indented verse lines; three-row arithmetic table | Half/three-quarter image caps preserved, figure breaks retained, single-column arithmetic table keeps its final top rule, authored table centering and OLED border ink preserved. Realistic illustrations use the publisher's dark paper backdrop. |
| Tristram Shandy | 342 navigation chapter slices, 39 notes, 36 Latin ruby annotations, embedded Standard Blackletter, hidden alignment prefixes, blank drawing page, distributed stars and brace compositions, Greek epigraph page | Inline embedded-family ranges resolve before measurement/drawing; hidden text retains advance without visible paint; intentional empty boxes and forced breaks survive. Ruby readings appear above their bases with authored reading typography. Bounded native horizontal rows retain gaps and stacked cell text; short fixed-width inline initials and ornaments retain their advances. The epigraph uses the actual viewport for authored vertical centering. |
| Exiles | Three rich dialogue tables containing 682, 475 and 361 rows | Removed the destructive 400-row fallback; all 1,518 rows retain stage directions, personas, punctuation and cell relationships. Authored narrow media styling stacks admitted simple table cells without changing source row/cell coordinates. |

Body chapters use `core.css` plus `local.css`. Four metadata documents in each package use `core.css` plus `se.css`; their far-offscreen accessible labels are suppressed visually instead of duplicating SVG titlepage text. Font features depend on the selected face supporting their OpenType tables.

## Shared parser and renderer contracts

- Bounded relative `:has`, reverse structural selectors and sibling caches preserve the actual stylesheet selectors; chapter cache cleanup releases DOM references.
- Document whitespace collapses without replacing NBSP, narrow NBSP or typographic spaces. Explicit normal generated styles can reset inherited italics. Text-case conversion follows the nearest language and inline word boundaries.
- `visibility:hidden` ranges remain transparent after reader color correction, including when publisher typography is disabled. A visible child can override inherited visibility.
- Authored image maxima remain separate from requested width/height. An explicit height also respects width caps. Unknown filters are not guessed; retained dark image treatment distinguishes diagrams from realistic art.
- Empty border pseudo content and authored horizontal rules paint through the publisher box instead of adding an unrelated generic divider. Current-color borders follow effective reader ink.
- Native horizontal CSS rows preserve natural cell widths and distributed gaps; normal publisher tables paint only authored borders. Complex unsupported layouts keep readable flow.
- Responsive stacking is bounded to simple admitted media queries and non-spanning cells. Narrow cell styles retain their own alignment, padding and vertical spacing; font scale does not change the CSS breakpoint.
- Publisher font resolution uses the same text for measurement and drawing and respects the publisher-formatting switch.
- MathML, ruby and admitted inline text boxes use one placeholder/content contract in paragraphs, headings, tables and side boxes. Internal rich spans are kept for drawing the native object and masked in the containing text layout so Android does not split a single replacement across different font spans. Source text and annotation offsets remain identical for search, copying and selection.
- Pagination treats admitted native inline objects as indivisible runs. A formula wider than the complete text column scales uniformly to that column, reserving its leading indent and adjacent closing punctuation; an indivisible element taller than a page remains accessible through vertical scrolling.
- Native MathML supports presentation rows and tokens, fractions, square/indexed roots and super/subscripts. Unsupported trees retain the readable formatter instead of partially losing their structure. Native ruby retains rich reading text, inherited styles and embedded font ranges; surrounding prose whitespace remains intact. Short Latin words surrounding an inflection ruby form one native run, preventing breaks inside `furem` or `malefactorem`. The reading stays centered above its authored base, not the whole word. Joining is bounded to 64 Latin characters and stops at other native objects.
- Short, plain-text column-flex groups can retain a viewport-relative minimum height, including the audited `calc(98vh - 3em)` epigraph. Content, nested boxes, padding and border-box sizing determine the extra space. Oversized groups fall back to normal flow instead of receiving negative centering space.

Paragraphs containing multi-character native replacements use logical-start alignment when their effective alignment requests justification. Android's justified caret positions around these replacement spans can omit expanded word gaps, which places native children and surrounding text in different horizontal coordinates. Applying the same start-aligned style in pagination and drawing preserves their coordinate agreement. These paragraphs also use `LineBreak.Simple`: Android's paragraph-quality line breaking could split a long native replacement or place its rectangle beyond the right edge, despite measuring the replacement as one object. The simple line-break path keeps the original text and the native replacement geometry together. These are explicit typography compromises for these paragraphs; ordinary text paragraphs keep their effective alignment and line-breaking style.

When a native inline object's height exceeds the requested line height, its containing paragraph uses natural per-line ascent/descent. Android's fixed `LineHeightSpan`, including its minimum-height mode, can reuse first-line metrics and prevent a later fraction or ruby line from expanding. Natural metrics allow the affected line to reserve the object's full height in both measurement and drawing. Paragraphs without an oversized inline object keep their requested line-height contract.

## Optimization and compatibility

Looking Glass contains 160,945,433 decompressed SVG bytes; the largest SVG is 7,543,505 bytes. Geometry probing formerly read each complete SVG before truncating it. It now reads at most 16 KiB, extracts root dimensions or viewBox, and reuses a bounded, file-stat-aware 128-entry cache. Full vector decoding is still necessary when drawing the artwork.

Publisher box planning now sorts ranges once and sweeps half-open intervals instead of scanning and sorting every range for every leaf. A 4,000-leaf regression verifies enclosing order and boundary flags.

Pagination cache stores a measured table grid once per source table; its page fragments retain row ranges and share the same restored layout. A 600-row/60-page regression checks shared identity, one stored row-height array and a cache below 30 KB. Responsive cell heights are cached with the same source coordinates. Content signatures cover the new geometry, visibility, font and responsive contracts; the disposable cache format and layout version were advanced. Book data and backup formats are unchanged. New model fields have defaults and the new serializable cache model lives in `data.model` for R8.

MathML admission and payload decoding are bounded to 256 nodes, depth 32, 4,096 text characters and a 32 KiB payload. Ruby readings are bounded to 4,096 characters, 128 style spans and a 64 KiB payload. Repeated MathML layout/decoding and ruby decoding use bounded caches; measured publisher group heights retain at most 16 geometry/settings variants. Native viewport centering admits at most 64 paragraph/heading leaves. These limits constrain work and cache growth for malformed or unusually complex books; exceeding them retains readable flow.

## Remaining fidelity differences

These are known implementation limits, not cases covered by a full-fidelity claim:

- Native MathML is a bounded presentation subset rather than a complete MathML implementation. Matrices/tables, under/over operators, arbitrary spacing/layout nodes, bevelled or specially sized fraction rules, unsupported math variants and token-specific authored colors use the readable fallback. Formula geometry is native; it is not font-MATH-table or browser-equivalent typesetting.
- Ruby supports simple base/reading pairs on one line. Complex `rb`/`rtc` grouping, multiline bases, alternative reading positions and arbitrary `ruby-align` modes are not implemented. Native readings are centered above their bases; fallback text remains available when a pair cannot be admitted.
- Fixed-width inline text boxes admit simple text-only runs of one or two Unicode code points without their own box decoration, generated content or nested children. Longer or decorated inline blocks retain readable text. Weighted flex growth, general flex wrapping/reversal, negative margins and arbitrary positioned overlays remain approximations. The supported native row branch handles the audited star/brace arrangements within its bounds.
- Viewport centering admits short paragraph/heading groups with supported column-flex declarations and affine `vh` minimum heights. It is not a general flex layout engine for mixed-media or large multi-page groups, or arbitrary CSS `calc()` expressions.
- Width media queries are not a general runtime CSS cascade. The admitted responsive table branch preserves the source breakpoint; other unsupported conditional layout changes retain base flow. Compound `@supports` conditions are skipped safely.
- Hanging punctuation and `text-wrap:pretty` are not browser-equivalent. Small caps cannot be guaranteed for a reader-selected font lacking the corresponding OpenType features.
- Paragraphs containing multi-character native inline replacements currently use logical-start alignment for requested justification and the simple line-break strategy because of Android replacement/caret geometry. Their wrapping can differ from ordinary paragraph-quality line breaking. Expanded inline objects can also require natural line metrics, so their affected paragraphs do not reproduce a fixed authored line-height grid exactly.
- Coil/AndroidSVG owns actual SVG decoding. Geometry/cache tests and desktop native previews do not establish rendering completeness or frame timing on Android 8/vendor devices.

## Verification

Original-package tests verify all six local files, extracted image/font files, navigation, typed note resolution and edition-specific regressions. They skip when those user-owned EPUBs are absent; synthetic regressions cover the contracts in CI.

To run the optional original-package checks, place locally obtained books in the
repository's ignored `.testbooks` directory or in your Downloads directory. A
custom corpus directory can be set with `FROGREADER_TEST_BOOK_DIR`; the forked
test JVM also recognizes the `frogreader.testBookDir` system property. Full EPUB
packages are not included in the source. Debug excerpt, artwork and font
provenance is recorded in [THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md).

Native inline regressions cover all 125 original MathML roots, all 36 original ruby pairs, source-text preservation, atomic fragment boundaries, table intrinsic widths, metadata cache round trips, and shared measurement/rendering geometry.

The final full `test assembleDebug assembleRelease` run passed with the completed native wrapping, punctuation and Latin-word fixes. Both APK variants, release R8 processing and `lintVitalRelease` completed. After adding one final regression for expanded ruby placeholder/pagination coordinates, `test` passed again: 1,536 tests total, 1,531 passed, five skipped, zero failures or errors. All six original-package tests ran successfully; their files were present. `git diff --check` passed.

The broader `lintDebug` check remains blocked by two pre-existing `NonObservableLocale` errors in `ReadingLogCalendar.kt:52` and `ReadingLogDatePicker.kt:153` (48 warnings). This is separate from the successful release-vital check and is not reported as a clean lint run.

Validation commands use the Android Studio JBR:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug assembleRelease
```

Native Compose fixtures use actual public-domain excerpts and matching CSS, including narrow/large-text and OLED states. They exercise the native reader components; they are not screenshots of a full book-reading session. Review captures are local evidence and are not distributed with the source. Device reading, rotation, TalkBack, gesture interaction and real-device performance remain unperformed.

All 26 final native previews rendered successfully and were visually reviewed, including 25 feature states and one left-alignment diagnostic. The final narrow MathML preview retains the entire long expressions and their closing punctuation within the text column. Normal and large ruby previews retain complete Latin words, readings above their original bases and sufficient vertical space. The other fixtures cover dialogue stacking, symbol/arithmetic tables, verse indentation, blank-page spacing, stars/braces, embedded Blackletter, inline widths, viewport-centered Greek text and OLED artwork. The large ruby fixture has sufficient height to show its entire excerpt; it does not simulate page turning. Desktop previews establish these fixture results, not complete reading-session or device conformance.

The two actual SVG samples were also rendered with the installed macOS QuickLook renderer. Its Looking Glass poem has the same artwork/viewBox boundary appearance as the native preview, and its Flatland sample likewise includes all three diagrams with the final parenthesis meeting the right edge. This comparison shows no additional native crop in these samples; it is not a general decoder or device-performance certification.
