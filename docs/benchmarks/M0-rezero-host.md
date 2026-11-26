# M0 Kotlin engine baseline

Measured on 2026-11-09T23:38:34.709323Z with OpenJDK 64-Bit Server VM 21.0.7 on Mac OS X aarch64.

Corpus: optional local EPUB; 9648164 source bytes; 677 parsed chapters; 190851 elements.

| Phase | Position | Time (ms) | Heap before (bytes) | Sampled heap peak (bytes) | Success | Prefix ready (ms) | Cache bytes |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: |
| parse_start | 0% | 2023.069 | 24921568 | 352625096 | n/a | 173.536 | n/a |
| parse_resume | 85% | 1271.799 | 273827208 | 571888096 | n/a | 1091.820 | n/a |
| preview_save | 0% | 80.434 | 500864000 | 507155456 | true | n/a | 6792 |
| preview_restore | 0% | 25.264 | 507155456 | 511349760 | true | n/a | 6792 |
| preview_save | 85% | 1008.274 | 530224128 | 578458624 | false | n/a | 0 |
| preview_restore | 85% | 0.038 | 486801400 | 486801400 | false | n/a | 0 |
| complete_save | all | 1072.118 | 486801400 | 581173240 | false | n/a | 148 |
| complete_restore | all | 0.019 | 438447512 | 438447512 | false | n/a | 148 |

Total parsed-cache bytes across independent benchmark entries: 6940.

The start parse has empty derived resources and no parsed cache. The 85% parse also uses empty derived resources, in the same JVM after the start parse. JVM/JIT and OS caches are not reset; these are cache-cold engine parses, not process-cold Android launches. Warm restores validate the source and decode disk snapshots in the same process, with no repository RAM-cache hit.

Prefix ready is the parser callback time before pagination. It does not measure the first displayed page. Heap is process-wide used JVM heap sampled every 2 ms during each phase; retained objects and GC from other phases can affect it. It is neither allocated bytes nor native memory. Cache bytes include headers and rejection markers. No timing threshold is asserted.

These are host measurements. Android first-frame timing and Android JVM/native memory require separate device measurements; these values do not establish a Rust speedup. M0 adds infrastructure; it changes no parser or cache implementation.
