# M0 Kotlin engine baseline

Measured on 2026-11-10T00:02:37.882922Z with OpenJDK 64-Bit Server VM 21.0.7 on Mac OS X aarch64.

Corpus: optional local EPUB; 9648164 source bytes; 677 parsed chapters; 190851 elements.

| Phase | Position | Time (ms) | Heap before (bytes) | Sampled heap peak (bytes) | Success | Prefix ready (ms) | Cache bytes |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: |
| parse_start | 0% | 1538.737 | 24886400 | 405224784 | n/a | 137.622 | n/a |
| parse_resume | 85% | 1002.402 | 219621536 | 775463088 | n/a | 828.815 | n/a |
| preview_save | 0% | 69.766 | 775463088 | 779657392 | true | n/a | 6792 |
| preview_restore | 0% | 18.263 | 779657392 | 783851696 | true | n/a | 6792 |
| preview_save | 85% | 862.293 | 802726064 | 980983984 | false | n/a | 0 |
| preview_restore | 85% | 0.009 | 980983984 | 980983984 | false | n/a | 0 |
| complete_save | all | 770.000 | 980983984 | 983081136 | false | n/a | 148 |
| complete_restore | all | 0.014 | 535960544 | 535960544 | false | n/a | 148 |

Total parsed-cache bytes across independent benchmark entries: 6940.

The start parse has empty derived resources and no parsed cache. The 85% parse also uses empty derived resources, in the same JVM after the start parse. JVM/JIT and OS caches are not reset; these are cache-cold engine parses, not process-cold Android launches. Warm restores validate the source and decode disk snapshots in the same process, with no repository RAM-cache hit.

Prefix ready is the parser callback time before pagination. It does not measure the first displayed page. Heap is process-wide used JVM heap sampled every 2 ms during each phase; retained objects and GC from other phases can affect it. It is neither allocated bytes nor native memory. Cache bytes include headers and rejection markers. No timing threshold is asserted.

Android first-frame timing, Android JVM/native memory, and before/after performance claims require separate device measurements. M0 adds infrastructure; it changes no parser or cache implementation.
