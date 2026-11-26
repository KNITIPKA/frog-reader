# M1 complete chunked-cache host measurements

Measured on 2026-11-10T00:09:54.072376Z with OpenJDK 64-Bit Server VM 21.0.7 on Mac OS X aarch64.

Corpus: optional local EPUB; 9648164 source bytes; 677 parsed chapters; 190851 elements. Canonical complete-model JSON: 109781201 bytes. Final cache tree: 19507258 bytes.

| Phase | Saved position | Time (ms) | JVM heap before (bytes) | Sampled JVM heap peak (bytes) | Success | FRB bytes |
| --- | ---: | ---: | ---: | ---: | --- | ---: |
| parse | all | 1495.726 | 29087888 | 387460640 | n/a | n/a |
| frb_save | all | 512.847 | 388323752 | 469194912 | true | 19507258 |
| frb_full_restore | 0% | 837.226 | 469194912 | 517380544 | true | 19507258 |
| prepare_saved_chapter | 0% | 0.305 | 370438144 | 370438144 | n/a | n/a |
| frb_full_restore | 85% | 675.470 | 370438144 | 573861888 | true | 19507258 |
| prepare_saved_chapter | 85% | 0.359 | 795457200 | 795457200 | n/a | n/a |

Every complete restore must match the original parser model by a streaming SHA-256 over all serialized BookContent fields and by PaginationCache.contentSignature over ReaderChapterItems. Those checks run outside the timed phases. The original parser model leaves scope before the restores.

M1 restores the complete model for either saved position. These two rows are independent native-reader sessions in the same warmed host JVM, not selective chapter loading. Preparing the saved chapter is recorded separately. No Android text measurement, pagination or displayed UI frame is timed.

The source parse has empty derived resources and no parsed cache. JVM/JIT, OS caches, font caches and GC are not reset. Heap is whole-process used JVM heap sampled every 2 ms; it is not allocated bytes, native memory or a guaranteed absolute peak. No timing threshold is asserted.

Compare with the separately captured before reports for the same source. The old monolithic codec is not retained for compatibility. Model correctness and successful persistence do not by themselves prove an Android opening-time improvement; device measurements remain a separate gate.
