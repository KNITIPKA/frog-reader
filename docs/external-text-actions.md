# External dictionary, translation, and search

FrogReader delegates selected text to installed Android activities. Reading settings contain
independent **Default translator**, **Default dictionary**, and **Default search app** choices.
The reader's selection menu exposes **Translate**, **Define**, and **Search** alongside its
existing quote, delete, and copy controls. The reader's separate bookmark controls are unchanged.

## Architecture

`data/translation/TranslationApps.kt` extends the existing translator integration rather than
adding a separate resolver for each role:

- `ExternalTextAction` identifies the three independently configured roles.
- `TextIntentMechanism` identifies the contract an activity actually advertises.
- `ExternalTextApp` retains the activity component, application label, activity label, contract,
  MIME variant, alternate advertised routes, and persistent choice ID. Different compatible
  activities in one package remain distinct.
- `queryExternalTextApps` discovers and validates available activities through `PackageManager`.
  When one activity supports several contracts for a role, the first supported contract is its
  primary choice and the remaining routes are retained, avoiding duplicate activity rows.
- `resolveExternalTextApp` restores the exact saved route, including one now listed among an
  activity's alternatives. Discovering a higher-priority contract does not overwrite or
  invalidate the user's existing choice.
- `externalTextIntent` creates an explicit Intent for the discovered component and puts the
  selected text into that contract's documented extra.
- `ExternalTextCoordinator` rechecks availability before dispatch and shares the original
  choose/save/launch flow among the three actions.

The shared Compose picker uses the existing dialog styling, application icons, activity labels,
selection indicators, **Reset choice**, and **Ask every time**. App discovery refreshes when the
screen resumes, and dispatch always queries current availability. An unavailable preference
remains visible as unavailable so the user can replace or reset it. Launch failures return to
app selection with the pending text retained. The reader selection is cleared only after a
successful handoff; dismissing the picker, a failed launch, or an oversized selection retains it.
All three actions share a reader-level `Mutex` request guard so rapid taps cannot enqueue
simultaneous external launches while discovery or preference saving is suspended.
Settings app-choice changes also await persistence before closing the picker. A failed write
keeps the picker open and reports the error through the existing snackbar; unrelated generic
settings updates retain their existing behavior.

External handoff uses `Context.startActivity` with `Intent.FLAG_ACTIVITY_NEW_TASK`, replacing
the previous activity-result launch whose result was ignored. This requests the destination
app's Android task, allowing FrogReader and the external app to be switched independently in
Recents. No `CLEAR_TASK`, `CLEAR_TOP`, or other stack-clearing flag is added. The destination
still controls its own activities and Back navigation. Returning successfully from
`startActivity` establishes only that Android accepted the launch; FrogReader does not infer
that the destination displayed results or automatically try another contract afterward.

## Supported contracts

| Role | Contract | Selected-text payload |
| --- | --- | --- |
| Translate | `android.intent.action.PROCESS_TEXT`, MIME `text/plain` | `Intent.EXTRA_PROCESS_TEXT` and `Intent.EXTRA_PROCESS_TEXT_READONLY = true` |
| Translate | `android.intent.action.TRANSLATE` | `Intent.EXTRA_TEXT` |
| Define | `android.intent.action.DEFINE` | `Intent.EXTRA_TEXT` |
| Define | `aard2.lookup` | `SearchManager.QUERY` |
| Define | `colordict.intent.action.SEARCH` | `"EXTRA_QUERY"` |
| Define | `com.hughes.action.ACTION_SEARCH_DICT` (QuickDic) | `SearchManager.QUERY` |
| Define | `com.ngc.fora.action.LOOKUP` (Fora Dictionary) | `"HEADWORD"` |
| Search | `android.intent.action.WEB_SEARCH` | `SearchManager.QUERY` |
| Search | `android.intent.action.SEARCH` | `SearchManager.QUERY` |
| Define / Search | Kiwix selected-text adapter using `PROCESS_TEXT`, MIME `text/plain` | `Intent.EXTRA_PROCESS_TEXT` and read-only flag |

Standard `DEFINE` and `TRANSLATE` handlers are discovered both without a MIME type and with
`text/plain`; dispatch preserves the variant that actually resolved. These standard contracts
do not require the caller to assume a MIME type. Search prefers `WEB_SEARCH`, with `SEARCH`
as the next discovery preference for activities advertising that native search contract.
Both routes are retained when advertised by the same activity, and an existing saved route
continues to be used. This priority does not cause a second launch after a successful handoff.

`PROCESS_TEXT` is a general text-processing contract. Existing translator choices continue to
use it, but arbitrary text processors are not automatically classified as dictionaries or
search applications. Likewise, accepting `ACTION_SEND` does not establish lookup or search
support; generic share destinations are not offered in those categories.

The Kiwix adapter discovers `ACTION_VIEW` handlers for `kiwix://search`, then intersects their
**activity components** with the components advertising `PROCESS_TEXT`. The same activity must
support both signatures. The adapter launches the verified `PROCESS_TEXT` contract. It neither
classifies applications by their names nor assumes every activity in a matching package is
compatible. This adapter is isolated in the resolver.

Selected text is passed unchanged in extras: Unicode, Cyrillic, Arabic, Hebrew, newlines, quotes,
and URI-looking characters are not parsed as an Intent or interpolated into a URL. No URL
encoding is necessary because FrogReader constructs no web-search URL. Empty selections are
rejected. Selections over 200,000 UTF-16 code units are rejected with an explanation rather than
silently truncated; this leaves room below Android's shared Binder transaction limit.

## Preference compatibility

All choices use the existing `SettingsRepository` / DataStore Preferences architecture:

| Setting field | Existing/new key |
| --- | --- |
| `defaultTranslator` | Existing `default_translator`, unchanged |
| `defaultDictionary` | New `default_dictionary` |
| `defaultSearchApp` | New `default_search_app` |

Each nullable field defaults to `null`, allowing old stored settings and backups to decode.
`null` means no saved choice: the next use opens the picker and can save a default. The `"ask"`
sentinel means **Ask every time**: selection opens for each use without replacing that preference.
Reset removes only the chosen role's preference. Choices in the other roles are unaffected.

Legacy translator IDs remain the exact flattened `PROCESS_TEXT` activity component strings.
Other contracts use a mechanism prefix plus the component, so their dispatch contract is not
lost. All advertised routes survive activity-row grouping, so a saved prefixed ID still resolves
when its contract becomes an alternative to a newly preferred contract. Existing translator
users do not need to configure the same app again. These fields are part of the existing app
settings model and do not change the backup archive format.

## Android compatibility and privacy

The feature preserves minSdk 26 and targetSdk 36. Its resolver uses the legacy integer-flags
`PackageManager` query overload available throughout the supported range. `TRANSLATE` and
`DEFINE` became framework constants in API 29; FrogReader uses their literal action strings,
which can safely be queried and sent on API 26 without invoking newer framework APIs. A handler
is offered only if the installed app advertises that contract.

Narrow intent signatures in `AndroidManifest.xml` make compatible handlers discoverable under
Android 11+ package visibility rules. The feature adds no permission and does not request
`QUERY_ALL_PACKAGES`. Resolution filters out disabled applications and activities, unexported
activities, FrogReader itself, and components requiring a permission FrogReader does not hold.
Installation/removal races and launch exceptions must be handled by the UI even after a query
succeeds; discovery is not a guarantee that another app will complete the operation.

FrogReader sends text only after the user invokes an action and chooses an application or has
configured one. It performs no search network requests and adds no analytics. A destination
application may use its own network access and privacy settings.

## Third-party compatibility and limitations

- **OSS Dict / Aard 2:** the actual exported dictionary activity advertises `aard2.lookup` and
  ColorDict search. OSS Dict's lookup implementation reads `SearchManager.QUERY` and
  `"EXTRA_QUERY"`. Its own imported dictionaries determine whether a definition exists.
- **QuickDic:** its exported dictionary activity advertises
  `com.hughes.action.ACTION_SEARCH_DICT`; its implementation reads `SearchManager.QUERY`.
  Optional source/destination language extras are not required for a basic lookup. Discovery
  uses that dictionary-specific action rather than classifying every generic `SEARCH` or
  `PROCESS_TEXT` handler as a dictionary.
- **Fora Dictionary:** its documented Android launch API uses `com.ngc.fora.action.LOOKUP`
  with the string extra `"HEADWORD"`. This activity launch does not require its separate
  service-based definition-query API, new permissions, or an embedded definition renderer.
- **ColorDict / NGHS Offline Dictionaries:** ColorDict-compatible handlers receive
  `colordict.intent.action.SEARCH` and `"EXTRA_QUERY"`. NGHS explicitly documents this
  interoperable contract, so its separate application-specific action is unnecessary.
- **Kiwix:** current upstream `PROCESS_TEXT` handling opens search with the supplied text.
  Define therefore searches Kiwix content rather than guaranteeing a dictionary entry;
  downloaded ZIMs determine the available results. Its advertised `kiwix://search` URI is used
  only for discovery because the current `ACTION_VIEW` parser rejects that scheme in its
  selected-text search path. Installed versions must expose both required signatures to appear.
  The user reports that search reaches downloaded Wiki content in Kiwix 3.14.1 on their phone,
  but Back reloads that content and can trap navigation. The separate-task launch change has
  not yet been validated on that phone and is not evidence that Kiwix's internal Back loop is
  fixed. Android Recents provides a separate way to switch back to FrogReader; it does not
  establish that pressing Back inside Kiwix returns correctly.
- **IronFox / Firefox-derived browsers:** current Fenix source advertises `WEB_SEARCH`, reads
  `SearchManager.QUERY`, and sends non-URL text to its selected/default search engine. The
  installed build's advertised capability determines whether it appears.
- **Vanadium / Chromium-derived browsers:** current Chromium source advertises `WEB_SEARCH`
  and places the supplied query in its search activity. The browser may require the user to
  confirm the populated query. Its search engine selection remains owned by the browser.
- **URL-only browsers:** merely accepting HTTP(S) `ACTION_VIEW` is insufficient to perform a
  search. There is no universal Android API for discovering such a browser's configured search
  URL. FrogReader supplies no guessed URL and silently forces no provider. A browser without a
  supported search contract will not appear.
- The selected destination owns query interpretation. For example, a browser may navigate to
  selected text it recognizes as a URL. FrogReader does not override destination behavior.
- Upstream source inspection establishes advertised contracts, not successful testing of an
  installed third-party build. The Kiwix phone behavior above is a user report; the latest
  separate-task handoff has not been tested by the agent on a phone or emulator.

## Choosing and setting up an offline dictionary

An installed dictionary app and its local dictionary data are separate requirements. Install
one of the following compatible viewers, add suitable data in that app, and confirm that a
word lookup works there before choosing it in FrogReader's **Default dictionary** setting.
The chosen data determines the languages, definitions, spelling variants, and phrase coverage.

| Dictionary app | Local data and setup | FrogReader launch contract |
| --- | --- | --- |
| [OSS Dict](https://github.com/Akylas/OSS-Dict/blob/master/README.md) | Add SLOB, MDict or StarDict files through the system document chooser. [DSL support](https://github.com/Akylas/OSS-Dict/releases/tag/v2.1.0/80) is also available in newer builds. | `aard2.lookup` + `query`; ColorDict is also advertised. |
| [Aard 2](https://github.com/itkach/aard2-android/blob/master/README.md) | Download `.slob` dictionaries separately and add them through the document chooser. The app itself does not download dictionary data. | `aard2.lookup` + `query`; ColorDict is also advertised. |
| [QuickDic](https://github.com/rdoeffinger/Dictionary) | Download and open compatible `.quickdic` dictionaries; the project provides Wiktionary-based data and tools for generating other dictionaries. Dictionary selection and an optional default remain in QuickDic. | `com.hughes.action.ACTION_SEARCH_DICT` + `query`. |
| [Fora Dictionary](https://fora-dictionary.com/) | Use **Import** in Dictionary Manager for a StarDict, DSL, XDXF, Dictd or TSV/plain dictionary index or archive. It also supports ZIM files and MediaWiki dumps. | `com.ngc.fora.action.LOOKUP` + `HEADWORD`. |
| [ColorDict](https://play.google.com/store/apps/details?id=com.socialnmobile.colordict) | Use its WordNet data or import compatible StarDict dictionaries. Follow the installed app's storage instructions; its dictionary location changed on Android 11+. | `colordict.intent.action.SEARCH` + `EXTRA_QUERY`. |
| [NGHS Offline Dictionaries](https://nghs.fr/en/api) | Use the app's dictionaries, or supply a custom `.ddb` SQLite database following its documented dictionary schema. | Existing ColorDict contract + `EXTRA_QUERY`. |

These choices use their advertised dictionary contracts; no application name or package-name
heuristic is needed. The QuickDic and Fora actions have narrowly scoped package-visibility
queries and do not use a MIME type. A handler is still validated on the installed device before
dispatch, and a compatible app can return no definition when its data lacks the selected word.

**DictTango** supports [MDX and TangoDict data](https://github.com/Jimex/DictTango-Android), but
its public release/documentation repository did not establish an exported lookup action and
payload contract for this integration. Its [Tap Anywhere feature](https://github.com/Jimex/DictTango-Android/wiki/Tap-Anywhere)
uses Accessibility access; that does not establish compatibility with FrogReader's explicit
Intent handoff. No guessed adapter or guaranteed DictTango support is provided. Likewise,
**GoldenDict Android** has no independently verified current launch contract here; any installed
build that genuinely advertises the supported ColorDict contract can be discovered, but named
GoldenDict compatibility remains unverified.

## Validation status

Automated logic tests, Intent construction checks, and build results are reported separately
from runtime integration checks. Passing a build does not establish third-party behavior or
cross-version runtime compatibility.

### Follow-up — 2026-10-16

- QuickDic and Fora dictionary contracts and narrow package-visibility queries are implemented.
  Per-activity alternatives retain every advertised route so exact stored choice IDs continue
  to resolve; a successful launch does not trigger an automatic fallback or second launch.
- The external launch now uses `Context.startActivity` and `FLAG_ACTIVITY_NEW_TASK` to request
  the destination app's own task. This change targets the report that Kiwix appeared within
  FrogReader's task. It does not claim to repair Kiwix's internal Back navigation or downloaded
  Wiki reload loop.
- Read-only ADB metadata confirmed the connected phone runs **API 37** and has **Kiwix 3.14.1**.
  No external activity was launched during that inspection. The user's report confirms search
  works in their current installation and identifies a Back-navigation failure; the failure
  remains unresolved until the updated handoff is checked on that installation.
- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=2`
  completed successfully for this follow-up: **1,801 unit tests, zero failures/errors, five
  skipped**. Instrumentation sources for QuickDic/Fora discovery, payloads, retained routes,
  and task flags compile, but have not been executed for this change.
- `git diff --check` passed. The follow-up `:app:lintDebug` run still fails on the existing
  `WrongConstant` diagnostic in `ui/theme/ReaderFonts.kt:232` (one error, 69 warnings, one
  hint); the unrelated font source was not changed for this follow-up.
- No phone runtime check or emulator run has been performed for the new launch change. The
  user explicitly prohibited further emulator use, and the emulator has been stopped. The
  historical runtime results below do not validate this updated launch behavior.
- Until Back behavior is verified, Android Recents can be used to switch to FrogReader. This
  is a navigation fallback, not a claim that the destination's Back behavior is fixed.

### Historical validation — 2026-10-15

The following completed checks apply to the earlier implementation, before the separate-task
launch and exact-route preservation follow-up:

- `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=2`
  completed with `BUILD SUCCESSFUL`. The unit-test XML reports contain **1,797 tests, zero
  failures/errors, and five skipped tests**.
- Targeted connected tests on an **API 37 Pixel 9a emulator running in read-only mode** passed:
  **17 tests, zero failures/errors/skipped**. Twelve tests verify native Intent construction;
  five use Android's actual `PackageManager` against exported fixture activities in the test
  APK to check discovery, contract matching, and filtering. This is fixture-based validation,
  not a test of installed OSS Dict, Kiwix, IronFox, or Vanadium builds.
- `git diff --check` passed. The project has no configured ktlint or Spotless task.
- Full `:app:lintDebug` fails on a **pre-existing, unrelated `WrongConstant` error at
  `ui/theme/ReaderFonts.kt:232`**; that code was not changed for this feature. The repeat run
  reports that same error, 69 warnings, and one hint. The feature-local `UseKtx` warning was
  corrected and is absent from the repeat report; no new feature-local warning was found.

At the time of these historical checks, no physical-device or third-party application integration
had been verified. The API 37 fixture run does not validate any version in the requested API
26–36 matrix. Those system images were not installed in the available environment:

| Android API | Runtime integration status |
| --- | --- |
| 26 — Android 8.0 | Unverified |
| 28 — Android 9 | Unverified |
| 29 — Android 10 | Unverified |
| 30 — Android 11 | Unverified |
| 31 — Android 12 | Unverified |
| 33 — Android 13 | Unverified |
| 34 — Android 14 | Unverified |
| 35 — Android 15 | Unverified |
| 36 — Android 16 | Unverified |

On each available version, manually check installed-handler discovery, all three reader actions,
independent preference persistence, reset and ask-every-time, uninstall/disable fallback, Unicode
and multiline transfer, return to reader, and the destination browser's engine/confirmation
behavior. Inspect the picker and reading settings in Light, Beige, and Dark, including narrow
width, large text, and TalkBack. Offline dictionary/search apps need suitable local content.

During the historical 2026-10-15 checks, native Compose components were rendered through
`PreviewActivity` and visually inspected on the read-only emulator. The fixtures use in-memory data:

- Pickers and all three settings rows in Light, Beige, and Dark.
- Selection toolbar in Light and Dark, plus Beige at large text.
- A 320 dp width with 150% font scale for all three pickers and the Beige settings/toolbar.
  The choice list scrolls within its bounds, fixed buttons remain reachable without clipping,
  and content below the viewport remains scrollable.

The [normal contact sheet](../app/build/qa/external-text/normal-contact-sheet.png) and
[large-text contact sheet](../app/build/qa/external-text/large-contact-sheet.png) are local,
ignored QA outputs. PreviewActivity's status/navigation bar appearance does not verify the
production reader's system bars. TalkBack and physical touch behavior remain unverified.

The final 2026-10-15 unit-test/debug/test-APK build rerun also completed successfully after adding
awaited settings persistence, with the same 1,797 tests, zero failures/errors, and five skipped
tests. It predates the follow-up changes documented above.

## Feature file inventory

The following files contain this feature's changes. Some already contained unrelated local
changes; those existing changes were retained. No files outside this task's scope were changed
for the external text integration.

Main source and resources (12):

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/frogreader/data/SettingsRepository.kt`
- `app/src/main/java/com/example/frogreader/data/translation/TranslationApps.kt`
- `app/src/main/java/com/example/frogreader/ui/reader/QuoteToolbar.kt`
- `app/src/main/java/com/example/frogreader/ui/reader/ReaderScreen.kt`
- `app/src/main/java/com/example/frogreader/ui/reader/ReaderViewModel.kt`
- `app/src/main/java/com/example/frogreader/ui/settings/ReadingPrivacySettingsPages.kt`
- `app/src/main/java/com/example/frogreader/ui/settings/SettingsScreen.kt`
- `app/src/main/java/com/example/frogreader/ui/settings/SettingsViewModel.kt`
- `app/src/main/java/com/example/frogreader/ui/translation/TranslationAction.kt`
- `app/src/main/java/com/example/frogreader/ui/translation/TranslatorPickerDialog.kt`
- `app/src/main/res/values/external_text_actions.xml`

Native Compose previews (2):

- `app/src/debug/java/com/example/frogreader/ui/reader/ReaderQuotePreviews.kt`
- `app/src/debug/java/com/example/frogreader/ui/translation/ExternalTextActionPreviews.kt`

Unit tests (4):

- `app/src/test/java/com/example/frogreader/data/AppSettingsTest.kt`
- `app/src/test/java/com/example/frogreader/data/ExternalAppPreferencesTest.kt`
- `app/src/test/java/com/example/frogreader/data/backup/BackupArchiveTest.kt`
- `app/src/test/java/com/example/frogreader/data/translation/TranslationCoordinatorTest.kt`

Android instrumentation and test-only fixtures (4):

- `app/src/androidTest/AndroidManifest.xml`
- `app/src/androidTest/java/com/example/frogreader/data/translation/ExternalTextIntentTest.kt`
- `app/src/androidTest/java/com/example/frogreader/data/translation/ExternalTextDiscoveryTest.kt`
- `app/src/androidTest/java/com/example/frogreader/data/translation/fixtures/ContractFixtures.kt`

Documentation (1): `docs/external-text-actions.md`.

## Primary sources

Contract research was performed on 2026-10-15. Upstream branch links can change over time.

- [Android Intent API reference](https://developer.android.com/reference/android/content/Intent):
  `PROCESS_TEXT`, `TRANSLATE`, `DEFINE`, `WEB_SEARCH`, `SEARCH`, and their extras/API introduction levels.
- [Android package visibility declarations](https://developer.android.com/training/package-visibility/declaring):
  narrow intent-based `<queries>` declarations.
- [OSS Dict manifest](https://github.com/Akylas/OSS-Dict/blob/master/AndroidManifest.xml) and
  [lookup implementation](https://github.com/Akylas/OSS-Dict/blob/master/src/itkach/aard2/article/ArticleCollectionViewModel.java).
- [Aard 2 manifest](https://github.com/itkach/aard2-android/blob/master/AndroidManifest.xml) and
  [documented lookup API and dictionary setup](https://github.com/itkach/aard2-android/blob/master/README.md).
- [QuickDic manifest](https://github.com/rdoeffinger/Dictionary/blob/main/AndroidManifest.xml)
  and [dictionary Intent parser](https://github.com/rdoeffinger/Dictionary/blob/main/src/com/hughes/android/dictionary/DictionaryActivity.kt):
  `com.hughes.action.ACTION_SEARCH_DICT` and `SearchManager.QUERY`.
- [Fora Dictionary official documentation](https://fora-dictionary.com/), including its embedded
  `integration.txt` Android Launch API: `com.ngc.fora.action.LOOKUP` and `HEADWORD`.
- [NGHS Offline Dictionaries developer API](https://nghs.fr/en/api): ColorDict-compatible
  action, accepted query extras, and custom SQLite dictionary format.
- [ColorDict official listing](https://play.google.com/store/apps/details?id=com.socialnmobile.colordict):
  supported dictionary data, ebook-reader integration, and Android 11+ storage changes.
- [Kiwix manifest](https://github.com/kiwix/kiwix-android/blob/main/app/src/main/AndroidManifest.xml)
  and [reader Intent parser](https://github.com/kiwix/kiwix-android/blob/main/core/src/main/java/org/kiwix/kiwixmobile/core/main/reader/helper/intent/PendingIntentParser.kt).
- [Fenix manifest](https://github.com/mozilla-firefox/firefox/blob/main/mobile/android/fenix/app/src/main/AndroidManifest.xml),
  [Intent processor](https://github.com/mozilla-firefox/firefox/blob/main/mobile/android/android-components/components/feature/intent/src/main/java/mozilla/components/feature/intent/processing/TabIntentProcessor.kt),
  and [selected/default search engine dispatch](https://github.com/mozilla-firefox/firefox/blob/main/mobile/android/android-components/components/feature/search/src/main/java/mozilla/components/feature/search/SearchUseCases.kt).
- [IronFox project](https://github.com/ironfox-oss/IronFox) and
  [Vanadium project](https://github.com/GrapheneOS/Vanadium): upstream browser foundations.
- [Chromium manifest](https://github.com/chromium/chromium/blob/main/chrome/android/java/AndroidManifest.xml),
  [search Intent utility](https://github.com/chromium/chromium/blob/main/chrome/android/java/src/org/chromium/chrome/browser/searchwidget/SearchActivityUtils.java),
  and [search UI coordinator](https://github.com/chromium/chromium/blob/main/chrome/android/java/src/org/chromium/chrome/browser/searchwidget/SearchUiCoordinator.java).
