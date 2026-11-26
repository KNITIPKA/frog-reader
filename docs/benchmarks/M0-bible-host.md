# M0 Kotlin engine baseline

Measured on 2026-11-09T23:38:59.498997Z with OpenJDK 64-Bit Server VM 21.0.7 on Mac OS X aarch64.

Corpus: optional local EPUB; 33221611 source bytes; 122 parsed chapters; 44446 elements.

| Phase | Position | Time (ms) | Heap before (bytes) | Sampled heap peak (bytes) | Success | Prefix ready (ms) | Cache bytes |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: |
| parse_start | 0% | 1597.635 | 24910824 | 271996592 | n/a | 203.451 | n/a |
| parse_resume | 85% | 957.651 | 262095152 | 451321272 | n/a | 819.341 | n/a |
| preview_save | 0% | 129.150 | 445638400 | 449832704 | true | n/a | 2099 |
| preview_restore | 0% | 27.751 | 231513808 | 235708112 | true | n/a | 2099 |
| preview_save | 85% | 1132.555 | 260873936 | 445423312 | false | n/a | 0 |
| preview_restore | 85% | 0.008 | 294095456 | 294095456 | false | n/a | 0 |
| complete_save | all | 1101.748 | 294095456 | 470256224 | false | n/a | 247 |
| complete_restore | all | 0.018 | 300043752 | 300043752 | false | n/a | 247 |

Total parsed-cache bytes across independent benchmark entries: 2346.

The start parse has empty derived resources and no parsed cache. The 85% parse also uses empty derived resources, in the same JVM after the start parse. JVM/JIT and OS caches are not reset; these are cache-cold engine parses, not process-cold Android launches. Warm restores validate the source and decode disk snapshots in the same process, with no repository RAM-cache hit.

Prefix ready is the parser callback time before pagination. It does not measure the first displayed page. Heap is process-wide used JVM heap sampled every 2 ms during each phase; retained objects and GC from other phases can affect it. It is neither allocated bytes nor native memory. Cache bytes include headers and rejection markers. No timing threshold is asserted.

These are host measurements. Android first-frame timing and Android JVM/native memory require separate device measurements; these values do not establish a Rust speedup. M0 adds infrastructure; it changes no parser or cache implementation.
