# Releasing FrogReader

The current milestone is **BETA 1**, tag `beta-1`, `versionName = BETA 1`,
`versionCode = 4`. The source is licensed under GPL-3.0-only.

## Prepare and verify

1. Compare the previous release tag with the complete source tree. Update
   `CHANGELOG.md`, `README.md`, and the release notes without rewriting history.
2. Set `version.properties` deliberately. Android version codes must increase;
   Alpha 3 used code 3. Rebuilding must not mutate the version.
3. Review every staged path. Keep signing material, local books, generated
   reports and APKs out of Git. Check `git diff --cached --check`.
4. Run `./gradlew test :app:assembleDebug :app:assembleRelease :app:lintDebug
   :app:lintRelease --max-workers=2`. For the maintainer's BETA 1 distribution,
   append `-PlegacyReleaseSigning=true` so the APK uses the verified legacy key.
   On macOS, set
   `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
5. Verify the release APK with `apksigner verify --verbose --print-certs` and
   `zipalign -c -P 16 -v 4`. Check application ID, version, SDK levels and that
   the APK is not debuggable. Record the APK SHA-256.
6. Ensure the signing certificate matches the previous published APK before
   distributing an update. Keep the private key backed up outside the checkout.
7. Push reviewed commits to `main`, wait for CI, tag the verified commit, and
   upload the optimized APK plus `SHA256SUMS.txt`. Mark beta releases as
   prereleases and link to their tag directly; GitHub's `/releases/latest`
   normally points to a stable release.

## Signing

A normal `assembleRelease` produces an **unsigned** optimized APK, allowing
contributors and CI to build without the maintainer's private credentials.
To sign, configure these environment variables outside Git:

- `FROGREADER_KEYSTORE_PATH`
- `FROGREADER_KEYSTORE_PASSWORD`
- `FROGREADER_KEY_ALIAS`
- `FROGREADER_KEY_PASSWORD`

No signing password or keystore belongs in source control. An incomplete
external signing configuration fails instead of silently using another key.

Alpha releases used the maintainer's local Android debug certificate. BETA 1
keeps that exact certificate to preserve in-place updates from Alpha 3. For
this legacy distribution only, the maintainer explicitly builds with
`-PlegacyReleaseSigning=true`. This does not make the release APK debuggable;
R8 optimization remains enabled. A different machine's debug key is not a
compatible replacement.

The Alpha 3 / BETA 1 certificate SHA-256 is:

```text
a93ff3d583a37246449213a267e9e4f4f4d78adb878e0202fb5b3abdf5458603
```

Before Play Store publication, plan proper production signing and migration;
Android's signing requirements mean replacing this certificate without a
migration plan breaks updates for existing installations. See
[Android app signing](https://developer.android.com/studio/publish/app-signing).

## Distribution

Use the release title **Frog Reader BETA 1** and asset `FrogReader-BETA-1.apk`.
Include installation requirements, notable changes, known limitations,
verification actually performed, GPL-3.0-only licensing and a link to the exact
source tag. GitHub provides source archives for that tag. Keep old release tags
and notes as historical records; they retain their original versions/licenses.
