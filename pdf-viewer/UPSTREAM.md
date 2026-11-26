# AndroidX PDF viewer source fork

Upstream: AndroidX `pdf-viewer:1.0.0-beta01`, Apache-2.0.
Source: https://dl.google.com/dl/android/maven2/androidx/pdf/pdf-viewer/1.0.0-beta01/

The source JAR supplies the unmodified starting Kotlin/Java sources; the AAR supplies
resources (this AAR has no consumer rules). The published stub `R.java` is omitted;
AGP generates it. The manifest package is supplied by Gradle namespace.
Core, isolated document service and Compose wrapper remain the published beta01 artifacts.
Gradle substitutes this module for the viewer so exactly one implementation is packaged.

SHA-256:
- sources JAR: `f9ba77058924c2f191653c958e0156a180b1c32527c9b58536b1ce9dfd6cc629`
- AAR: `2c94f047f9c2e9bd453180404a102447572c757b09309d69d40482db2caa9c03`

FrogReader modifications are documented in `docs/pdf-support.md`. Preserve upstream
headers and this provenance when updating. Diff against the pinned source JAR before
rebasing; verify Compose binary compatibility, selection, links, forms, accessibility,
render cancellation, memory bounds, and real-device scroll coverage after upgrades.
