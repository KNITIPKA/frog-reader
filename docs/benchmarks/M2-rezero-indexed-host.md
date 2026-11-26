# M2 indexed-access host measurements

Measured 2026-11-10T08:26:55.253722Z using OpenJDK 64-Bit Server VM 21.0.7 on Mac OS X aarch64.

Corpus: optional local EPUB; source 9648164 bytes, 677 chapters, 190851 elements; FRB 19551244 bytes.

| Phase | Position | Run | Time (ms) |
| --- | ---: | ---: | ---: |
| source_parse | all | 1 | 1621.473 |
| frb_save | all | 1 | 674.659 |
| index_open | 0% | 1 | 30.587 |
| target_chapter | 0% | 1 | 5.363 |
| index_and_target | 0% | 1 | 35.950 |
| index_open | 0% | 2 | 11.984 |
| target_chapter | 0% | 2 | 2.116 |
| index_and_target | 0% | 2 | 14.099 |
| index_open | 0% | 3 | 11.542 |
| target_chapter | 0% | 3 | 2.126 |
| index_and_target | 0% | 3 | 13.668 |
| index_open | 0% | 4 | 10.763 |
| target_chapter | 0% | 4 | 1.553 |
| index_and_target | 0% | 4 | 12.316 |
| index_open | 0% | 5 | 10.339 |
| target_chapter | 0% | 5 | 2.694 |
| index_and_target | 0% | 5 | 13.033 |
| index_open | 85% | 1 | 10.200 |
| target_chapter | 85% | 1 | 5.978 |
| index_and_target | 85% | 1 | 16.178 |
| index_open | 85% | 2 | 10.038 |
| target_chapter | 85% | 2 | 3.855 |
| index_and_target | 85% | 2 | 13.893 |
| index_open | 85% | 3 | 9.891 |
| target_chapter | 85% | 3 | 2.809 |
| index_and_target | 85% | 3 | 12.700 |
| index_open | 85% | 4 | 9.657 |
| target_chapter | 85% | 4 | 2.424 |
| index_and_target | 85% | 4 | 12.081 |
| index_open | 85% | 5 | 9.744 |
| target_chapter | 85% | 5 | 2.437 |
| index_and_target | 85% | 5 | 12.181 |
| index_open | 100% | 1 | 9.685 |
| target_chapter | 100% | 1 | 3.563 |
| index_and_target | 100% | 1 | 13.247 |
| index_open | 100% | 2 | 9.654 |
| target_chapter | 100% | 2 | 3.367 |
| index_and_target | 100% | 2 | 13.021 |
| index_open | 100% | 3 | 9.607 |
| target_chapter | 100% | 3 | 3.211 |
| index_and_target | 100% | 3 | 12.818 |
| index_open | 100% | 4 | 9.548 |
| target_chapter | 100% | 4 | 2.788 |
| index_and_target | 100% | 4 | 12.336 |
| index_open | 100% | 5 | 9.309 |
| target_chapter | 100% | 5 | 2.718 |
| index_and_target | 100% | 5 | 12.027 |
| heavy_globals | all | 1 | 4.028 |

| Phase | Position | p50 (ms) | p95 (ms) |
| --- | ---: | ---: | ---: |
| index_open | 0% | 11.542 | 30.587 |
| target_chapter | 0% | 2.126 | 5.363 |
| index_and_target | 0% | 13.668 | 35.950 |
| index_open | 85% | 9.891 | 10.200 |
| target_chapter | 85% | 2.809 | 5.978 |
| index_and_target | 85% | 12.700 | 16.178 |
| index_open | 100% | 9.607 | 9.685 |
| target_chapter | 100% | 3.211 | 3.563 |
| index_and_target | 100% | 12.818 | 13.247 |

Each index_open creates a new native session and validates the complete source SHA-256 and lightweight manifest. target_chapter reads exactly the destination chapter; it does not decode preceding chapters or heavy notes/linked documents. Per-chapter canonical SHA-256, all chapter coordinates and titles are verified outside timers. Heavy globals are timed separately and verified with a canonical model digest. The parser model leaves scope before selective reads.

Five repeats share a warmed JVM and OS cache; p95 uses the maximum sample (nearest rank). No CPU, native heap, Android text measurement, page cuts, input latency or displayed frame is measured. These timings establish a storage primitive and do not prove that the real reader's TOC, slider, exact page count or complete readiness meets the M2/M2F budgets.
