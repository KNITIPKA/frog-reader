# Build quality maintenance — 2026-10-06

The initial debug lint report contained 11 errors and 160 warnings. Cleanup
replaced deprecated clipboard, icon and EXIF APIs, moved Compose resource
lookups to configuration-aware resources, used window dimensions instead of
device configuration dimensions, normalized modifier parameters, corrected
plural forms, and removed unused strings and stock launcher assets.

Dependency versions are recorded in `gradle/libs.versions.toml`. Kotlin compiler
plugins are aligned at 2.4.20, Compose uses the September 2026 BOM, and Material 3
Expressive remains on its existing alpha channel at 1.5.0-alpha29. CameraX,
Coil, serialization, CommonMark, jsoup and the other flagged libraries were
updated. Custom sliders now use `SliderState` and retain externally controlled
values, ranges, callbacks and reader coordinate conversion.

## Compatibility decisions

- SDK levels remain `minSdk = 26`, `targetSdk = 36`, `compileSdk = 37`, as required
  by `AGENTS.md`. Only the SDK update advisory for `app/build.gradle.kts` is
  exempted in `app/lint.xml`.
- Gradle was updated from 9.6.0 to 9.7.0. Gradle 9.8.0 was checked but emits a
  deprecation from AGP 9.4.1's
  `BasePlugin.createAndroidJdkImageConfiguration`, not application build code.
  The wrapper update advisory alone is exempted until AGP fixes that call.
- Memory-pressure signals retained for Android 8–13 use a narrowly scoped
  deprecation suppression and a version guard. Android 14 stopped delivering
  those signals; see the [platform callback documentation](https://developer.android.com/reference/android/content/ComponentCallbacks2).
- Newer manifest and widget attributes have explicit tools API annotations;
  their existing older-Android sizing and language defaults remain available.
- Camera hardware and autofocus are explicitly optional, matching the gallery
  fallback for book covers. The compiled manifest no longer implies a required
  rear camera from the CAMERA permission. See the
  [Android feature declaration guidance](https://developer.android.com/guide/topics/manifest/uses-feature-element).
- CommonMark 0.30 defaults to treating nesting beyond 100 levels as literal
  text. Its block and inline limits now allow the reader's existing bounded
  traversal to reject excessive nesting before recursive rendering, while
  retaining valid structure. Tests cover both outcomes. See the
  [CommonMark release notes](https://github.com/commonmark/commonmark-java/releases).
- JVM tests attach Mockito at startup, using a classpath-aware argument
  provider, and disable CDS because inline mocking appends bootstrap classes.
  This follows [Mockito's instrumentation guidance](https://github.com/mockito/mockito/blob/main/mockito-core/src/main/java/org/mockito/Mockito.java).
- The four known AndroidX native libraries are packaged as supplied, with their
  symbols retained. This makes the existing no-NDK packaging result explicit
  and removes the unsuccessful stripping attempt; application code does not
  require native compilation.
- Navigation 2.10 resolves predictive Back animations separately from ordinary
  pop animations. Both pairs of callbacks in `MainActivity.kt` now use the same
  route-aware policy, preserving the reader's 360 ms scale-and-fade close and
  the metadata screen's sideways return. This avoids the new default's opaque
  70% scale-out during a gesture. Navigation still owns gesture progress,
  cancellation and back-stack completion. See the
  [Navigation 2.10 release notes](https://developer.android.com/jetpack/androidx/releases/navigation#2.10.0).

No lint baseline or global warning suppression was added. Dependency update
checks, API checks, compiler warnings and test assertions remain enabled.

## Verification

Use the Android Studio JBR for CLI checks:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew test :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease \
  --max-workers=2 --warning-mode all
```

Final verification on 2026-10-06 passed:

- 1,155 JVM tests: 1,150 passed, 5 skipped, no failures or errors.
- Debug assembly and release assembly with R8 succeeded.
- Debug and release lint each reported 0 errors and 0 warnings, with the two
  documented SDK/wrapper advisory exceptions above.
- The final build log contained no compiler, Gradle or packaging warnings.
  The configuration cache was reused successfully.
- The release APK's v2 signature verified. Its compiled manifest retains SDK
  levels 26/36/37 and explicitly marks camera hardware as optional.
- Both APKs preserve all 16 vendor native binaries byte for byte. Their DEX
  method tables contain no unbackported `List.of`, `Set.of`, `Map.of` or related
  collection factories introduced by the Markdown dependency update.

Verified APK copies are in `app/build/outputs/maintenance`:

| APK | SHA-256 |
| --- | --- |
| `FrogReader-clean-debug.apk` | `02e663892f6365ac103a85d6a893f720f5d666b71b4c5f23472f64213563407a` |
| `FrogReader-clean-release.apk` | `f78f73860f862112343341051a1b1f05143606c2fcb47f1b07e3f28f18c42ed2` |

Phone checks remain owner-led: verify clipboard copying, camera/gallery photo
orientation, goal/rating/reader sliders, and reader panel sizing after the
Compose and AndroidX updates. JVM tests and lint do not verify these visuals
or gestures.

## Predictive Back follow-up, 2026-10-07

After wiring the shared Back transition policy, the final source passed:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew test :app:assembleRelease :app:lintDebug :app:lintRelease \
  --max-workers=2 --warning-mode all
```

- 1,150 JVM tests passed, 5 skipped, no failures or errors.
- Debug/release compilation and release assembly succeeded; both lint reports
  contain no issues. The build log contains no warnings.
- The new release APK's v2 signature verified; SDK levels remain 26/36/37.
- APK: `app/build/outputs/maintenance/FrogReader-back-transitions-release.apk`.
  SHA-256: `0d56d8f7ec5fc16b2da101446763870283e19c63e0158ba69dc9a9c683f18085`.

Phone verification remains pending: compare button Back and slow gestures from
both edges for Reader → Library, Reading Queue/Log → Profile, and metadata →
book information. Cancel a partial gesture and check that the current screen
returns to full size and opacity without leaving the route.
