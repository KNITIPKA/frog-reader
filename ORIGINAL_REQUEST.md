# Original User Request

## 2026-09-10T20:08:18Z

Глибинний аудит архітектури, рушія рендерингу (Compose/Kotlin), сховища даних та функціоналу Android-додатка FrogReader з формуванням пріоритетної дорожньої карти розвитку.

Working directory: /Users/frog/AndroidStudioProjects/FrogReader
Integrity mode: development

## Requirements

### R1. Аудит рушія типографіки та пагінації
Провести оцінку архітектури Pagination.kt, PaginationCache.kt, SideBoxLayout.kt, TableMeasure.kt, BidiLayoutText.kt та Woff2Decoder.kt на предмет стабільності, продуктивності на низькорівневих пристроях (minSdk 26) та коректності розриву сторінок.

### R2. Аналіз надійності сховища та персистентності
Проаналізувати трьохрівневу модель персистентності (library.json, userdata.json, progress.json) та механізм AtomicJsonFile.kt на стійкість до збоїв живлення, R8-сумісність та синхронізацію.

### R3. Пріоритизація функціоналу (Feature Gap Analysis)
Сформувати детальний технічний план реалізації відсутніх модулів (TTS, Profile/Tracker, PDF/CBZ, WebDAV sync, E-ink оптимізація) із врахуванням збереження чистоти архітектури без WebView.

## Acceptance Criteria

### Архітектурна відповідність
- [ ] Збереження вимоги minSdk 26 без використання незахищених API 28+.
- [ ] Відсутність важких WebView та збереження 100% native Compose рендерингу.
- [ ] Усі 548+ юніт-тестів проходять без помилок (export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew test).
- [ ] Повна збереженість формату резервних копій BackupArchive.kt.
- [ ] Сам вихідний код проєкту не змінювати, виконувати лише читання та аналітику.
