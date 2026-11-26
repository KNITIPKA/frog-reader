# Releasing Frog Reader

The current milestone is **BETA 3**, tag `beta-3`, `versionName = BETA 3`,
`versionCode = 7`, unified application ID `knitipka.frogreader`, display name
**Frog Reader**, requiring Android 9.0+ (`minSdk = 28`, `targetSdk = 36`,
`compileSdk = 37`). The source is licensed under GPL-3.0-only.

## Prepare and verify

1. Compare the previous release tag with the complete source tree. Update
   `CHANGELOG.md`, `README.md`, and release notes without rewriting history.
2. Set `version.properties` deliberately (`code=7`, `name=BETA 3`). Android
   version codes must monotonically increase (BETA 2 used `code = 6`).
   Rebuilding must not mutate tracked files.
3. Review every staged path. Keep signing material (`*.p12`, `*.jks`,
   `*.keystore`, `signing.properties`), local books, generated reports, and
   APKs/AABs out of Git. Check `git diff --cached --check`.
4. Run all Rust and Android verification checks matching CI:
   ```sh
   cargo fmt --manifest-path rust/Cargo.toml --all -- --check
   cargo clippy --manifest-path rust/Cargo.toml --workspace --all-targets --all-features --locked -- -D warnings
   cargo test --workspace --manifest-path rust/Cargo.toml --locked
   JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
     ./gradlew test :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease :app:compileDebugAndroidTestKotlin --max-workers=2
   ```
   (On Linux, use the installed JDK 21 instead of the macOS `JAVA_HOME` above.)
5. To build signed release artifacts (APK and Play Store AAB), supply signing
   credentials outside Git via environment variables, `-PsigningProperties=<file>`,
   or a gitignored root `signing.properties` file, then run
   `:app:assembleRelease :app:bundleRelease`.
6. Verify the signed release APK with `apksigner verify --verbose --print-certs`
   and `zipalign -c -P 16 -v 4`. Confirm `applicationId = knitipka.frogreader`,
   display label `Frog Reader`, `versionCode = 7`, `versionName = BETA 3`,
   `minSdk = 28`, `debuggable = false`, and that `dependenciesInfo` is absent.
   Record the APK and AAB SHA-256 digests.
7. Ensure the signing certificate matches the permanent production release key
   before distributing updates. Keep the private keystore backed up safely
   outside the repository.
8. Push reviewed commits to `main`, wait for CI, tag the verified commit
   (`beta-3`), and upload the signed APK plus `SHA256SUMS.txt`. Mark beta
   releases as prereleases and link directly to their tag; GitHub's
   `/releases/latest` normally points to a stable release.

## Signing and F-Droid / Play Store readiness

A normal `:app:assembleRelease` or `:app:bundleRelease` without credentials
produces an **unsigned**, R8-optimized APK/AAB (`signingConfig = null`).
`dependenciesInfo` is disabled for both APKs and bundles (`includeInApk = false`,
`includeInBundle = false`) so Google's encrypted dependency metadata block is
omitted, supporting reproducible builds and F-Droid inclusion.

To produce a signed release build, provide these four credentials outside Git:

- `FROGREADER_KEYSTORE_PATH` (absolute path or path relative to the repo root)
- `FROGREADER_KEYSTORE_PASSWORD`
- `FROGREADER_KEY_ALIAS`
- `FROGREADER_KEY_PASSWORD`

Credentials can be supplied via:

1. Environment variables (`FROGREADER_KEYSTORE_*`),
2. `-PsigningProperties=/path/to/signing.properties`, or
3. A gitignored `signing.properties` file in the repository root.

No signing password or keystore belongs in source control. An incomplete
signing configuration fails the build loudly rather than silently emitting an
unsigned artifact.

### Production key transition (BETA 3)

Earlier alpha and beta builds (Alpha 3 through BETA 2) were signed with a
temporary local debug certificate (SHA-256
`a93ff3d583a37246449213a267e9e4f4f4d78adb878e0202fb5b3abdf5458603`). Starting
with **BETA 3**, Frog Reader uses a permanent external **PKCS12 RSA-4096**
production release keystore (`CN=KNITIPKA`) compatible with Google Play and
future in-place updates. Because Android enforces certificate continuity,
upgrading from BETA 2 or earlier to BETA 3 requires exporting a backup in-app,
uninstalling the older debug-signed beta, installing BETA 3, and restoring the
backup. See [Android app signing](https://developer.android.com/studio/publish/app-signing).

## Distribution

Use the release title **Frog Reader BETA 3** and asset `FrogReader-BETA-3.apk`
(alongside `SHA256SUMS.txt`). Include installation requirements (Android 9.0+,
clean reinstall notice for pre-BETA 3 users), notable changes, known
limitations, verification actually performed, GPL-3.0-only licensing, and a
link to the exact source tag. Keep old release tags and notes as historical
records; they retain their original versions and licenses.
