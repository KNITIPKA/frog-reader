# Reading Log

Reading Log holds one record per reading, including paper books and books read
elsewhere. It lives independently of the library and Reading Queue. Removing
a book file or library book does not remove the history or its retained cover.

## Behavior

- Opening a new library book creates an **In progress** record. Its endpoint
  is the last opening date, not a claim that the book was finished.
- Passing the final page opens the completion page. In scroll mode, completion
  is detected at the completion item after the text. Review on the last paged
  page is also an intentional completion action.
- The current active reading time is saved before completion. Time on the
  completion page or review screen does not count as reading.
- Reopening a finished or abandoned reading offers **Read again**, **Read without logging**,
  or keeping the previous reading. A logged reread starts a new record, date
  range, progress and timer. Previous ratings and reviews remain intact.
- Each record can be marked **Finished**, **In progress** or **Abandoned**.
- Dates can be an exact day, a month and year, a year, or unknown. The precision
  is retained. Partial dates use their earliest possible day only for sorting;
  the interface does not display an invented day.
- Ratings are optional, from 1 to 5 stars in half-star steps. Reviews belong to
  the chosen reading. Editing an earlier reading does not replace the current
  library review.
- The default order is newest ending date first. Start and ending date orders
  support both directions, with unknown dates at the end. Manual order retains
  its own list positions, right-side drag handles, edge scrolling and accessible
  move actions. The selected mode is saved in DataStore and settings backups.

## Cover capture

Paper records offer an in-app camera and gallery picker. The camera viewfinder
has a 2:3 frame. CameraX's controller shares the preview viewport with capture;
import normalizes orientation, limits decoding size and saves an upright 2:3
JPEG. Camera permission is requested only when opening capture, and the camera
is released when leaving the screen. Gallery selection remains available if
camera access is denied or the device has no camera.

The implementation follows the official [CameraX controller documentation](https://developer.android.com/media/camera/camerax/architecture).

## Storage and backup

`ReadingLogEntry` and its partial date/source/status models belong to
`data.model`. The ordered log is in `UserDataStore`, written through
`BookRepository` and `AtomicJsonFile`. New serialized fields have defaults.
Existing started/finished library books are migrated into log records.

Legacy whole-star ratings remain decodable; an additive half-star field takes
precedence when present. The current library book references its active record.
An unlogged reread suppresses automatic log updates, retaining old records.

`BackupDocument` includes the log and manual order, and the manifest reports its
entry count. **DATA and FULL** in-app backups both include log covers, including
paper photos. FULL additionally includes book files and other library covers.
Restore resolves asset availability and publishes the complete history.

Android's separate quota-limited cloud Auto Backup rules continue to cover the
JSON stores; the in-app archive is the complete backup for log photographs.

## Validation

Repository tests cover persistence, date precision and ranges, half stars,
automatic completion, active last opening, page-turn write isolation, rereads
with and without logging, historical-review isolation, status changes, undo,
library deletion, legacy ratings, failed writes and DATA/FULL cover restoration.
UI state tests cover ordering and date ranges; reader timer tests cover active
time accounting.

CLI verification: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test :app:assembleDebug --no-configuration-cache --max-workers=2`.
Both full runs completed 1,032 tests with no failures or errors and 5 skips;
all 24 new log/date/reread/backup/timer tests passed. The final debug APK includes
the last receipt layout and unsaved-review refinements.

Camera preview/capture, keyboard layout, drag gestures, animations and TalkBack
must also be checked on an actual phone. Compiling debug previews is not visual
or hardware verification.
