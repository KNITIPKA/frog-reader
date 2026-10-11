# Licensing and third-party notices

FrogReader BETA 1 and subsequent project releases are licensed under the
GNU General Public License, version 3 only (`GPL-3.0-only`).
Copyright (c) 2026 KNITIPKA. The full text is in [LICENSE](LICENSE).
FrogReader is distributed without any warranty. Earlier releases retain the
licence under which they were originally distributed.

Original FrogReader source, resources, build configuration and documentation
are covered by the project licence unless a file or the notices below identify
different terms. Third-party works keep their original licences and copyrights.

The application includes the GPL and third-party licence texts in
`app/src/main/assets/licenses/`. Settings → About provides offline access:
tap the GPLv3 label or the credits at the bottom of the popup.

## Bundled fonts and icons

| Resource | Upstream and copyright | Licence and local copy |
| --- | --- | --- |
| Literata regular and italic | [Literata](https://github.com/googlefonts/literata), Copyright 2017 The Literata Project Authors; designed by TypeTogether | SIL Open Font License 1.1, [literata-OFL.txt](app/src/main/assets/licenses/literata-OFL.txt) |
| IBM Plex Sans regular and italic | [IBM Plex](https://github.com/IBM/plex), Copyright © 2017 IBM Corp.; Reserved Font Name “Plex” | SIL Open Font License 1.1, [ibm-plex-OFL.txt](app/src/main/assets/licenses/ibm-plex-OFL.txt) |
| GitHub, Telegram and Signal icons | [Tabler Icons](https://github.com/tabler/tabler-icons), Copyright (c) 2020–2026 Paweł Kuna | MIT, [tabler-icons-MIT.txt](app/src/main/assets/licenses/tabler-icons-MIT.txt); originals are in `assets/icons/about/` |

The fonts remain under the OFL. The project GPL licence does not replace the
OFL, reserve additional font names, or alter the third-party icon licence.

## Runtime dependencies

| Component | Licence | Upstream |
| --- | --- | --- |
| AndroidX / Jetpack libraries, Compose and Material icons | Apache-2.0 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/) |
| Kotlin standard library and kotlinx serialization/coroutines | Apache-2.0 | [Kotlin](https://github.com/JetBrains/kotlin), [kotlinx serialization](https://github.com/Kotlin/kotlinx.serialization), [kotlinx coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| Coil | Apache-2.0 | [Coil](https://github.com/coil-kt/coil) |
| AndroidSVG (used by Coil for SVG images) | Apache-2.0 | [AndroidSVG](https://github.com/BigBadaboom/androidsvg) |
| Okio (Coil dependency) | Apache-2.0 | [Okio](https://github.com/square/okio) |
| JetBrains annotations | Apache-2.0 | [Java annotations](https://github.com/JetBrains/java-annotations) |
| Guava and failureaccess (AndroidX dependencies) | Apache-2.0 | [Guava](https://github.com/google/guava) |
| AtomicFU | Apache-2.0 | [AtomicFU](https://github.com/Kotlin/kotlinx-atomicfu) |
| Dagger, AutoValue, Error Prone and J2ObjC annotations | Apache-2.0 | [Dagger](https://github.com/google/dagger), [AutoValue](https://github.com/google/auto), [Error Prone](https://github.com/google/error-prone), [J2ObjC](https://github.com/google/j2objc) |
| JSpecify annotations | Apache-2.0 | [JSpecify](https://github.com/jspecify/jspecify) |
| javax.inject and Jakarta Dependency Injection | Apache-2.0 | [javax.inject](https://github.com/javax-inject/javax-inject), [Jakarta Inject](https://github.com/eclipse-ee4j/injection-api) |
| Protocol Buffers embedded in AndroidX DataStore / Glance | BSD-3-Clause | [Protocol Buffers](https://github.com/protocolbuffers/protobuf) |
| libyuv embedded in CameraX native image processing | BSD-3-Clause | [libyuv](https://chromium.googlesource.com/libyuv/libyuv/) |
| Checker Framework qualifiers | MIT | [Checker Framework](https://github.com/typetools/checker-framework) |
| JSR305 annotations (Guava dependency) | New BSD upstream notice; Apache-2.0 in Maven metadata | [Original FindBugs notice](https://github.com/findbugsproject/findbugs/blob/master/findbugs/licenses/LICENSE-jsr305.txt) |
| jsoup | MIT | [jsoup](https://jsoup.org/), Copyright (c) 2009–2026 Jonathan Hedley; [full notice](app/src/main/assets/licenses/jsoup-MIT.txt) |
| Brotli Java decoder | MIT | [Brotli](https://github.com/google/brotli), Copyright (c) 2009, 2010, 2013–2016 by the Brotli Authors; [full notice](app/src/main/assets/licenses/brotli-MIT.txt) |
| commonmark-java and its GFM table/strikethrough extensions | BSD-2-Clause | [commonmark-java](https://github.com/commonmark/commonmark-java), Copyright (c) 2015 Atlassian Pty Ltd; [full notice](app/src/main/assets/licenses/commonmark-BSD.txt) |

The [Apache 2.0 text](app/src/main/assets/licenses/Apache-2.0.txt) is included
for Apache-licensed dependencies. Declared versions are in
`gradle/libs.versions.toml`; Gradle's `releaseRuntimeClasspath` dependency report
provides the resolved transitive graph for a build. This table describes the
runtime components rather than changing their licences. Additional original
BSD/MIT texts and the Jakarta NOTICE are retained in
[third-party-notices.txt](app/src/main/assets/licenses/third-party-notices.txt),
which is packaged in the APK and available in the offline viewer.

## Development and test materials

Gradle and its wrapper scripts retain their Apache-2.0 notices. JVM and
instrumentation test dependencies retain their upstream licences: JUnit 4
(EPL-1.0), Mockito (MIT), mockito-kotlin (MIT), AndroidX Test / Espresso
(Apache-2.0), kXML2 (BSD-3-Clause), XML Pull API (public domain), and the Kotlin
coroutines test library (Apache-2.0). These test libraries are not packaged in
the release APK.

Debug reader fixtures contain excerpts, matching CSS, artwork and a font from
the following Standard Ebooks editions. Standard Ebooks dedicates its contributions to these editions under
[CC0 1.0](https://standardebooks.org/about/standard-ebooks-and-the-public-domain).
Its repositories identify original text and artwork as public domain in the
United States; copyright status of the original works can differ elsewhere.
These sample assets belong to the debug source set and are not packaged in the
release APK.

- [Dracula — Bram Stoker](https://github.com/standardebooks/bram-stoker_dracula).
- [Flatland — Edwin A. Abbott](https://github.com/standardebooks/edwin-a-abbott_flatland).
- [A Tangled Tale — Lewis Carroll](https://github.com/standardebooks/lewis-carroll_a-tangled-tale).
- [Through the Looking-Glass — Lewis Carroll / John Tenniel](https://github.com/standardebooks/lewis-carroll_through-the-looking-glass_john-tenniel).
- [The Life and Opinions of Tristram Shandy, Gentleman — Laurence Sterne](https://github.com/standardebooks/laurence-sterne_the-life-and-opinions-of-tristram-shandy-gentleman).
- [Exiles — James Joyce](https://github.com/standardebooks/james-joyce_exiles).

`app/src/debug/assets/epub-audit/flatland-illustration-1.svg` and
`looking-glass-illustration-10.svg` are unmodified copies of the corresponding
edition's `epub/images/illustration-1.svg` and `illustration-10.svg`.
`app/src/debug/res/font/epub_audit_blackletter.otf` is the unmodified
`epub/fonts/standard-blackletter.otf` from the Shandy edition.
[Standard Blackletter](https://github.com/standardebooks/standard-blackletter)
was created by David Grigg and dedicated to the public domain under CC0 1.0;
its [original notice](app/src/debug/assets/licenses/standard-blackletter-CC0.md)
is retained. Source fragments in `AdvancedEpubFixtures.kt`, the poetry
fixtures, and the Shandy ruby regression fixture follow the same edition
provenance. Full user-supplied ebook files and their private local paths are
not part of this repository.

## Distributing a modified build

Keep these notices and the licence texts with a redistributed build. For a
GPL release APK, provide the corresponding source for that exact release,
including the build scripts. FrogReader's GitHub release links to its tagged
source archives next to the APK download. New third-party resources must have
their provenance and licence recorded before they are added.
