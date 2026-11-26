# M0 Kotlin engine baseline

Measured on 2026-11-10T00:02:44.746623Z with OpenJDK 64-Bit Server VM 21.0.7 on Mac OS X aarch64.

Corpus: optional local EPUB; 33221611 source bytes; 122 parsed chapters; 44446 elements.

| Phase | Position | Time (ms) | Heap before (bytes) | Sampled heap peak (bytes) | Success | Prefix ready (ms) | Cache bytes |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: |
| parse_start | 0% | 1761.113 | 25288704 | 284492456 | n/a | 258.840 | n/a |
| parse_resume | 85% | 942.154 | 229273312 | 458886304 | n/a | 803.068 | n/a |
| preview_save | 0% | 119.161 | 394217344 | 400508800 | true | n/a | 2100 |
| preview_restore | 0% | 27.675 | 400508800 | 404703104 | true | n/a | 2100 |
| preview_save | 85% | 1162.513 | 429868928 | 459229056 | false | n/a | 0 |
| preview_restore | 85% | 0.017 | 446172480 | 446172480 | false | n/a | 0 |
| complete_save | all | 1138.913 | 448269632 | 470001504 | false | n/a | 247 |
| complete_restore | all | 0.011 | 239059392 | 239059392 | false | n/a | 247 |

Total parsed-cache bytes across independent benchmark entries: 2347.

The start parse has empty derived resources and no parsed cache. The 85% parse also uses empty derived resources, in the same JVM after the start parse. JVM/JIT and OS caches are not reset; these are cache-cold engine parses, not process-cold Android launches. Warm restores validate the source and decode disk snapshots in the same process, with no repository RAM-cache hit.

Prefix ready is the parser callback time before pagination. It does not measure the first displayed page. Heap is process-wide used JVM heap sampled every 2 ms during each phase; retained objects and GC from other phases can affect it. It is neither allocated bytes nor native memory. Cache bytes include headers and rejection markers. No timing threshold is asserted.

Android first-frame timing, Android JVM/native memory, and before/after performance claims require separate device measurements. M0 adds infrastructure; it changes no parser or cache implementation.
