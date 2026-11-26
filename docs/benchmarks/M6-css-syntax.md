# Native CSS syntax and values

FrogReader uses Rust `cssparser` to read stylesheets and inline declarations.
It returns ordered rules and declaration values through JNI. The reader
reuses the parsed stylesheet across its viewport variants and caches frequently
repeated inline declarations. Rust also evaluates literal CSS colors and
`calc()`, `min()`, `max()` and `clamp()`. The initial measurements below are
from the version that still matched selectors on the JVM.

The new parser keeps punctuation inside a quoted string distinct from CSS
syntax. A regression test checks that a quoted `}` and `/* literal */` in
generated content survive and the following rule still applies. The parsed
content cache generation is 6 so books pick up such parser corrections on
their next open.

On the Mac, the previous and new parsers produced the same normalized model
digest for each of the two EPUBs. The digest covers chapters, formatting,
navigation, notes and linked documents. The benchmark also checked that
chapters loaded directly from 0% and 85% match the full parse.

| Book | Chapters | Elements | Model digest prefix in both implementations |
| --- | ---: | ---: | --- |
| Re:Zero | 677 | 190,851 | `0f2931a3dc57` |
| Hebrew Bible | 122 | 44,446 | `2b3ef7b8f6e4` |

The suite passed 41 Rust tests, including CSS parsing limits, and 2,242 JVM
tests with eight skipped. Debug, AndroidTest and signed Release APKs built.
The Release package passed signature and 16 KiB alignment checks. Its four
CSS JNI exports were present for every ABI.

## Cold opening on Pixel 9a

The isolated Debug build opened both books in scroll mode with neither FRB
nor a retained source index. Contents and a seek to approximately 85% worked
before FRB publication, and the visible text stayed at the same screen height
when exact coordinates were published.

| Book | First window and TOC | Text at approximately 85% after both gestures | Exact source index and stable text |
| --- | ---: | ---: | ---: |
| Hebrew Bible | 0.964 s | 4.299 s | 22.196 s |
| Re:Zero | 1.120 s | 3.531 s | 27.501 s |

Times start at activity launch and each row is one successful trial. An
earlier Re:Zero run showed first text and finished indexing, then timed out
waiting for the visible anchor. The display was dozing when inspected.
These runs verify the change in the reader. A matched timing series is needed
to isolate the effect of the new CSS parser on full desktop or device parsing.

## Parse profile

Two Re:Zero parses with JFR on the Mac took 1,261 and 722 ms. Across 65 Java
samples containing `EpubParser`, 50 included `HtmlMapper` and seven included
`CssResolver`. None captured a selector matcher, cascade or CSS JNI frame.
The sample counts support investigating XHTML mapping next. Native CPU
attribution requires a separate native profile.

## Native selector matching

The reader now transfers a chapter's Jsoup element tree and compiled author
selectors to Rust. Rust matches element relationships, attributes and
structural pseudo-classes including `:has()`. It returns matched rule IDs in
specificity and source order. Kotlin reuses the original declaration objects
when merging inline and stylesheet rules and calculating inherited styles.
Selector syntax still uses the existing Kotlin parser for this transfer. The
reference matcher remains reproducible from `CssResolver.kt` at commit
`a6c2cc8`.

The Rust chapter session is released in the mapper's `finally` block. The
work budget still spans every chapter that shares a resolver. A JVM test
checks that changing the namespace binding and base URI between chapters
changes attribute matching. The CSS, Standard Ebooks and author-fidelity
tests passed in the full app suite. It recorded 2,243 passes and eight
skips. The Rust suite passed 49 tests. Clippy, signed Release
assembly, 16 KiB alignment and all three matcher JNI exports across four
ABIs passed.

The full publication digests above matched the version with JVM matching on
both books. Chapters loaded directly at 0% and 85% also matched their
full-parse counterparts.

On Pixel 9a, a fresh isolated build opened both books before a full source
index and FRB existed. Tests used Contents and the slider before the full
index was ready. They checked that the exact index kept the same passage at
the same screen height.

| Book | Mode | First window and TOC | Text near 85% after both gestures | Exact source index and stable text |
| --- | --- | ---: | ---: | ---: |
| Hebrew Bible | Scroll | 0.919 s | 4.057 s | 22.811 s |
| Re:Zero | Scroll | 1.131 s | 3.484 s | 28.382 s |
| Hebrew Bible | Pages | 1.171 s | 4.555 s | 25.310 s |
| Re:Zero | Pages | 0.868 s | 4.372 s | 29.791 s |

Each timing is one trial measured from launch. The last column covers
publication of exact book coordinates and, in Pages mode, presentation of
the active page range. The full page total requires its own pagination
measurement. Full publication of the source index remains above the 5–8 s
budget.

## Native cascade

Rust now resolves duplicate declarations within a block and selects winners
across matching rules and inline styles. It returns declaration IDs in
application order. A winning declaration takes its position in the cascade
from the rule that supplied it, which matters when logical and physical
properties address the same side. Shared selector lists reuse their
declaration blocks. Kotlin still expands shorthands and computes inherited
styles from the winning values.

The normalized publication digests for both private EPUBs matched the
previous implementation. The host benchmark also matched direct reads
at 0% and 85% against the full parse. All 52 Rust tests and the JVM suite
passed, including a new inline-only case covering duplicate properties,
`!important` and computed-style reuse. The signed Release APK passed its
signature and 16 KiB alignment checks. All four ABIs export the Rust
cascade, chapter open and chapter close methods.

The isolated APK passed the cold Pages-mode test on Pixel 9a for both
EPUBs. Contents and a seek near 85% worked before the full index was ready.
Both tests verified the screen position of the visible text across exact
index publication.

| Book | First window and TOC | Text near 85% after both gestures | Exact source index and stable text |
| --- | ---: | ---: | ---: |
| Hebrew Bible | 1.541 s | 5.924 s | 30.508 s |
| Re:Zero | 0.997 s | 5.255 s | 39.950 s |

Each row is one run measured from launch and includes opening the active
page range. A matched timing series would isolate the effect of the cascade
migration.

Separate Pixel runs measured full source parsing and FRB writing with heap
sampling. The test now includes EPUB source ranges in the expected index
digest. Both books passed the full index, navigation, global metadata, and
target chapter checks.

| Book | Full source parse | FRB write |
| --- | ---: | ---: |
| Hebrew Bible | 34.748 s | 3.997 s |
| Re:Zero | 38.732 s | 4.227 s |

Each row is one standalone run. These durations are independent of the
cold-open UI measurement above. Full source parsing, including XHTML
mapping and resource extraction, accounted for most of the measured time.

## Native selector compilation

Servo's `selectors` 0.41.0 uses the engine's existing `cssparser` to parse
each member of an authored selector list. Rust converts supported selectors
into the reader's bounded matcher format and sends their encoded chains and
candidate keys to the chapter session. The renderer's selector subset,
specificity ordering, structural work budget, and separate pseudo-element
pools still apply. Unsupported members are skipped individually.

The compiler understands escaped class and ID names. Derived content
caches use model generation 7 so existing books pick up the parser when
they next open.

Both publication digests match the reference models above. On the Mac,
chapters opened directly at 0% and 85% matched the same chapters from the
full parse. All 60 Rust tests passed. The app JVM suite recorded 2,245
passes and eight skips, including passes for Standard Ebooks and EPUB
author-fidelity fixtures. Debug, AndroidTest, and signed Release APKs built.
The Release APK passed signature and 16 KiB alignment checks. Its selector
compiler JNI export is present in all four ABIs.

On Pixel 9a, both books passed the cold Pages-mode test. The reader offered
Contents and a seek near 85% before exact coordinates were available. It
kept the same visible text at the same screen height when it published
the exact index.

| Book | First window and TOC | Text near 85% after both gestures | Exact source index and stable text |
| --- | ---: | ---: | ---: |
| Hebrew Bible | 1.049 s | 4.694 s | 28.091 s |
| Re:Zero | 1.056 s | 5.012 s | 40.554 s |

Each row is one run measured from launch. The first Hebrew Bible attempt
timed out waiting for a Contents row. The recorded run succeeded after
waking the display. These timings cover the active page range and exact
source index. The overall cold-open target still requires a faster full
EPUB parse.

## Native computed CSS values

Rust prepares declaration blocks, including the atomic longhand admission
limit and duplicate selection. It expands `margin`, `padding`, `font`,
`border`, and `background`, accounting for values that depend on
`var()`. The declaration transfer shares each source string across its
longhands. Rust inherits custom properties, evaluates variable references
in a batch, and computes font sizes, box lengths, and line heights. The
computed line height preserves its unitless or absolute form for descendants.
Kotlin assembles the reader style and layout model from these values.

The normalized publication digests for Re:Zero and Hebrew Bible matched
the previous stage. Chapters opened at 0% and 85% matched the corresponding
chapters from each full parse. The Rust suite passed 75 tests and Clippy.
The app JVM suite recorded 2,246 passes and eight skips. Its new integration
case covers an inherited variable used in a border, logical padding and
font-relative line height. Debug, AndroidTest and signed Release APKs built.
The signed APK passed its signature, 16 KiB alignment and six CSS-value
JNI exports on each ABI.

Both cold Pages-mode tests on Pixel 9a passed. Contents and the seek near
85% worked before the full index appeared. The visible passage kept its
screen position when exact coordinates were published.

| Book | First window and TOC | Text near 85% after both gestures | Exact source index and stable text |
| --- | ---: | ---: | ---: |
| Hebrew Bible | 0.909 s | 4.336 s | 25.021 s |
| Re:Zero | 0.924 s | 4.281 s | 29.859 s |

Each row is one run measured from launch. A separate generated EPUB test
compared the parsed model and its FRB restore on the same build. The model
digest, pagination signature and native page boundaries matched.

## Native computed style model

Rust now assembles the complete computed style consumed by the reader.
This includes inherited font and text settings, tag defaults, author
presentation attributes, colors, OpenType features, dimensions, physical
and logical box sides, borders, flow visibility, and break behavior.
Kotlin receives the typed result for its block and inline model adapters.

The native chapter context retains parent style states. Children with
unchanged custom properties share the same scope in Rust and Kotlin.
The chapter mapper closes both matching and computed-style contexts.
The FB2 table adapter also closes its short-lived context after binding
the border model.

The Rust suite passed 100 tests and Clippy. The app JVM suite recorded
2,249 passes and eight skips. The new JVM case checks shared custom scopes
and reusing a resolver after the previous chapter's context was closed.

The complete normalized publication digests matched the previous build for
both books. The host benchmark also verified all 1,028 EPUB source ranges
for Hebrew Bible and 677 for Re:Zero. Chapters parsed directly at 0% and
85% matched the corresponding chapters in each full parse. The Debug,
AndroidTest, and signed Release builds passed. The Release APK passed its
signature and 16 KiB alignment checks and contains all three computed CSS
JNI exports in each of its four ABIs.

Both cold Pages-mode tests passed on Pixel 9a. Contents and the slider
worked before the exact source index was ready. The passage at the slider
destination stayed at the same screen height when the index was published.

| Book | First window and TOC | Text near 85% after both gestures | Exact source index and stable text |
| --- | ---: | ---: | ---: |
| Hebrew Bible | 0.906 s | 4.452 s | 32.674 s |
| Re:Zero | 0.786 s | 4.404 s | 35.519 s |

Each row is one successful run timed from launch. Android rejected the
first Hebrew Bible attempt's tap because the test window did not own its
target. The repeat passed. A generated EPUB also matched its FRB restore
for the full model, pagination signature, and opening page cuts on the
same build. The first host timing on this slice was slower than the prior
host capture (4.257 s vs 1.736 s for Re:Zero, 5.713 s vs 1.440 s for Hebrew
Bible). A paired rerun using the saved Kotlin resolver in a separate build
and the same Rust library did not reproduce the slowdown. Full parse times
were 2.094 s with Kotlin and 1.940 s with Rust for Re:Zero, and 1.977 s
with Kotlin and 1.998 s with Rust for Hebrew Bible. Each result is one run
on the Mac. Both builds again produced the same normalized models.

## Whole-book pagination comparison on Pixel 9a

The same Android test exported every page from the Kotlin reference and
the computed Rust build. Both used a 360 × 600 px page, 18 sp serif text,
publisher styles and chapter breaks. The export records page anchors,
text ranges, table fragments and side-box geometry. It uses the same
pagination implementation as `paginateBook` and streams completed pages.

All 60,626 pages matched byte for byte across the nine inputs, including
the complete Re:Zero (47,221 pages), Hebrew Bible (8,645), six local
Standard Ebooks and a generated book with a split table and a drop cap.
The full model digests and layout settings matched as well.

The first comparison also identified seven differences in the separate
cache signature field. The reader includes modification times of
extracted images in that key. Fresh extraction changes those times even
when the files and page geometry match. The export now gives its private
test resources a fixed timestamp before calculating the production key.
It can compare only signatures without repeating the pagination pass.
All seven signatures matched on retest. The retest compared the same
Kotlin reference with the Rust build that also reuses the chapter tree
for matching. The generated book and Re:Zero already matched without
timestamp normalization.
