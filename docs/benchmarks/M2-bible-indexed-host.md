# M2 indexed-access host measurements

Measured 2026-11-10T08:27:00.124842Z using OpenJDK 64-Bit Server VM 21.0.7 on Mac OS X aarch64.

Corpus: optional local EPUB; source 33221611 bytes, 122 chapters, 44446 elements; FRB 15740112 bytes.

| Phase | Position | Run | Time (ms) |
| --- | ---: | ---: | ---: |
| source_parse | all | 1 | 1554.579 |
| frb_save | all | 1 | 648.061 |
| index_open | 0% | 1 | 65.750 |
| target_chapter | 0% | 1 | 1.133 |
| index_and_target | 0% | 1 | 66.883 |
| index_open | 0% | 2 | 40.911 |
| target_chapter | 0% | 2 | 0.405 |
| index_and_target | 0% | 2 | 41.316 |
| index_open | 0% | 3 | 36.069 |
| target_chapter | 0% | 3 | 0.418 |
| index_and_target | 0% | 3 | 36.487 |
| index_open | 0% | 4 | 35.778 |
| target_chapter | 0% | 4 | 0.378 |
| index_and_target | 0% | 4 | 36.156 |
| index_open | 0% | 5 | 35.513 |
| target_chapter | 0% | 5 | 0.340 |
| index_and_target | 0% | 5 | 35.853 |
| index_open | 85% | 1 | 34.881 |
| target_chapter | 85% | 1 | 66.232 |
| index_and_target | 85% | 1 | 101.113 |
| index_open | 85% | 2 | 38.449 |
| target_chapter | 85% | 2 | 34.183 |
| index_and_target | 85% | 2 | 72.632 |
| index_open | 85% | 3 | 35.418 |
| target_chapter | 85% | 3 | 26.170 |
| index_and_target | 85% | 3 | 61.588 |
| index_open | 85% | 4 | 35.181 |
| target_chapter | 85% | 4 | 25.878 |
| index_and_target | 85% | 4 | 61.059 |
| index_open | 85% | 5 | 35.213 |
| target_chapter | 85% | 5 | 22.581 |
| index_and_target | 85% | 5 | 57.794 |
| index_open | 100% | 1 | 34.675 |
| target_chapter | 100% | 1 | 14.371 |
| index_and_target | 100% | 1 | 49.046 |
| index_open | 100% | 2 | 47.328 |
| target_chapter | 100% | 2 | 14.078 |
| index_and_target | 100% | 2 | 61.406 |
| index_open | 100% | 3 | 35.339 |
| target_chapter | 100% | 3 | 9.695 |
| index_and_target | 100% | 3 | 45.035 |
| index_open | 100% | 4 | 34.780 |
| target_chapter | 100% | 4 | 9.558 |
| index_and_target | 100% | 4 | 44.338 |
| index_open | 100% | 5 | 34.777 |
| target_chapter | 100% | 5 | 9.427 |
| index_and_target | 100% | 5 | 44.204 |
| heavy_globals | all | 1 | 198.169 |

| Phase | Position | p50 (ms) | p95 (ms) |
| --- | ---: | ---: | ---: |
| index_open | 0% | 36.069 | 65.750 |
| target_chapter | 0% | 0.405 | 1.133 |
| index_and_target | 0% | 36.487 | 66.883 |
| index_open | 85% | 35.213 | 38.449 |
| target_chapter | 85% | 26.170 | 66.232 |
| index_and_target | 85% | 61.588 | 101.113 |
| index_open | 100% | 34.780 | 47.328 |
| target_chapter | 100% | 9.695 | 14.371 |
| index_and_target | 100% | 45.035 | 61.406 |

Each index_open creates a new native session and validates the complete source SHA-256 and lightweight manifest. target_chapter reads exactly the destination chapter; it does not decode preceding chapters or heavy notes/linked documents. Per-chapter canonical SHA-256, all chapter coordinates and titles are verified outside timers. Heavy globals are timed separately and verified with a canonical model digest. The parser model leaves scope before selective reads.

Five repeats share a warmed JVM and OS cache; p95 uses the maximum sample (nearest rank). No CPU, native heap, Android text measurement, page cuts, input latency or displayed frame is measured. These timings establish a storage primitive and do not prove that the real reader's TOC, slider, exact page count or complete readiness meets the M2/M2F budgets.
