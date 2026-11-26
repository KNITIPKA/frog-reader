# M1 complete chunked-cache host measurements

Measured on 2026-11-10T00:09:46.755291Z with OpenJDK 64-Bit Server VM 21.0.7 on Mac OS X aarch64.

Corpus: deterministic generated EPUB (16 spine documents, 96 paragraphs per document); 23847 source bytes; 16 parsed chapters; 1552 elements. Canonical complete-model JSON: 7151131 bytes. Final cache tree: 98946 bytes.

| Phase | Saved position | Time (ms) | JVM heap before (bytes) | Sampled JVM heap peak (bytes) | Success | FRB bytes |
| --- | ---: | ---: | ---: | ---: | --- | ---: |
| parse | all | 287.165 | 31899648 | 67288728 | n/a | n/a |
| frb_save | all | 64.173 | 76855808 | 112507392 | true | 98946 |
| frb_full_restore | 0% | 154.973 | 112507392 | 117750272 | true | 98946 |
| prepare_saved_chapter | 0% | 0.182 | 91226112 | 92274688 | n/a | n/a |
| frb_full_restore | 85% | 70.053 | 92274688 | 135266304 | true | 98946 |
| prepare_saved_chapter | 85% | 0.178 | 61094888 | 61094888 | n/a | n/a |

Every complete restore must match the original parser model by a streaming SHA-256 over all serialized BookContent fields and by PaginationCache.contentSignature over ReaderChapterItems. Those checks run outside the timed phases. The original parser model leaves scope before the restores.

M1 restores the complete model for either saved position. These two rows are independent native-reader sessions in the same warmed host JVM, not selective chapter loading. Preparing the saved chapter is recorded separately. No Android text measurement, pagination or displayed UI frame is timed.

The source parse has empty derived resources and no parsed cache. JVM/JIT, OS caches, font caches and GC are not reset. Heap is whole-process used JVM heap sampled every 2 ms; it is not allocated bytes, native memory or a guaranteed absolute peak. No timing threshold is asserted.

Compare with the separately captured before reports for the same source. The old monolithic codec is not retained for compatibility. Model correctness and successful persistence do not by themselves prove an Android opening-time improvement; device measurements remain a separate gate.
