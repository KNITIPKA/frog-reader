# M0 Pixel engine records

2026-11-10, Pixel 9a, isolated debug APK, bounded partial CPU wake lock, fixed
diagnostic pagination. These are Kotlin engine baselines plus a real Rust JNI
transport smoke check. See [M0-baseline.md](M0-baseline.md) for definitions,
limits, commands and summary. Each corpus test completed `OK (1 test)`.

## Generated EPUB

```text
ENGINE_DEVICE native_bridge=true package=knitipka.frogreader.rust
ENGINE_DEVICE corpus=generated source_bytes=23843 heap_sampling_ms=2 ui_frames=false
ENGINE_DEVICE phase=cold_parse position=0 elapsed_ms=3961.195 process_jvm_before_bytes=11043552 sampled_process_jvm_peak_bytes=122331872 process_native_before_bytes=8107424 sampled_process_native_peak_bytes=49099936 first_three_pages_ms=882.292 prefix_emitted=true chapters=16 elements=1552 pages=3
ENGINE_DEVICE phase=preview_save position=0 elapsed_ms=251.414 process_jvm_before_bytes=122790624 sampled_process_jvm_peak_bytes=124535520 process_native_before_bytes=49136240 sampled_process_native_peak_bytes=49462432 saved=true cache_bytes=7918
ENGINE_DEVICE phase=warm_open position=0 elapsed_ms=472.710 process_jvm_before_bytes=124568288 sampled_process_jvm_peak_bytes=134742752 process_native_before_bytes=49144128 sampled_process_native_peak_bytes=49252512 restore_ms=445.648 first_three_pages_ms=472.710 pages=3 cache_bytes=7918
ENGINE_DEVICE phase=complete_save position=0 elapsed_ms=2806.448 process_jvm_before_bytes=134775520 sampled_process_jvm_peak_bytes=162243296 process_native_before_bytes=49230528 sampled_process_native_peak_bytes=49581888 saved=true cache_bytes=187790 rejected_marker=false
ENGINE_DEVICE phase=cold_parse position=85 elapsed_ms=2334.759 process_jvm_before_bytes=24953568 sampled_process_jvm_peak_bytes=180175584 process_native_before_bytes=8854288 sampled_process_native_peak_bytes=62238960 first_three_pages_ms=2082.996 prefix_emitted=true chapters=16 elements=1552 pages=38
ENGINE_DEVICE phase=preview_save position=85 elapsed_ms=2294.746 process_jvm_before_bytes=185582304 sampled_process_jvm_peak_bytes=218194656 process_native_before_bytes=63269136 sampled_process_native_peak_bytes=63605088 saved=true cache_bytes=161104
ENGINE_DEVICE phase=warm_open position=85 elapsed_ms=2866.970 process_jvm_before_bytes=218194656 sampled_process_jvm_peak_bytes=256545504 process_native_before_bytes=63278688 sampled_process_native_peak_bytes=63346752 restore_ms=2659.802 first_three_pages_ms=2866.970 pages=38 cache_bytes=161104
```

## Re:Zero

```text
ENGINE_DEVICE native_bridge=true package=knitipka.frogreader.rust
ENGINE_DEVICE corpus=local source_bytes=9648164 heap_sampling_ms=2 ui_frames=false
ENGINE_DEVICE phase=cold_parse position=0 elapsed_ms=38770.559 process_jvm_before_bytes=4641504 sampled_process_jvm_peak_bytes=259515104 process_native_before_bytes=8102048 sampled_process_native_peak_bytes=47929840 first_three_pages_ms=826.629 prefix_emitted=true chapters=677 elements=190851 pages=3
ENGINE_DEVICE phase=preview_save position=0 elapsed_ms=97.563 process_jvm_before_bytes=157053664 sampled_process_jvm_peak_bytes=157389536 process_native_before_bytes=30774496 sampled_process_native_peak_bytes=30774496 saved=true cache_bytes=6799
ENGINE_DEVICE phase=warm_open position=0 elapsed_ms=121.758 process_jvm_before_bytes=157422304 sampled_process_jvm_peak_bytes=158487264 process_native_before_bytes=11591968 sampled_process_native_peak_bytes=11652256 restore_ms=102.226 first_three_pages_ms=121.758 pages=3 cache_bytes=6799
ENGINE_DEVICE phase=complete_save position=0 elapsed_ms=12547.179 process_jvm_before_bytes=158487264 sampled_process_jvm_peak_bytes=254522080 process_native_before_bytes=11643680 sampled_process_native_peak_bytes=12008704 saved=false cache_bytes=6954 rejected_marker=true
ENGINE_DEVICE phase=cold_parse position=85 elapsed_ms=33353.843 process_jvm_before_bytes=228860640 sampled_process_jvm_peak_bytes=268378848 process_native_before_bytes=8954880 sampled_process_native_peak_bytes=48954800 first_three_pages_ms=28091.443 prefix_emitted=true chapters=677 elements=190851 pages=69
ENGINE_DEVICE phase=preview_save position=85 elapsed_ms=11587.420 process_jvm_before_bytes=158388960 sampled_process_jvm_peak_bytes=265593568 process_native_before_bytes=12602784 sampled_process_native_peak_bytes=12934064 saved=false cache_bytes=0
ENGINE_DEVICE phase=warm_open position=85 cache_hit=false first_three_pages_ms=unavailable reason=baseline_cache_rejection
```

## Hebrew Bible

```text
ENGINE_DEVICE native_bridge=true package=knitipka.frogreader.rust
ENGINE_DEVICE corpus=local source_bytes=33221611 heap_sampling_ms=2 ui_frames=false
ENGINE_DEVICE phase=cold_parse position=0 elapsed_ms=33032.651 process_jvm_before_bytes=4313824 sampled_process_jvm_peak_bytes=236282592 process_native_before_bytes=7820304 sampled_process_native_peak_bytes=102399008 first_three_pages_ms=1775.296 prefix_emitted=true chapters=122 elements=44446 pages=3
ENGINE_DEVICE phase=preview_save position=0 elapsed_ms=192.128 process_jvm_before_bytes=129688288 sampled_process_jvm_peak_bytes=129958624 process_native_before_bytes=29245280 sampled_process_native_peak_bytes=29580016 saved=true cache_bytes=2009
ENGINE_DEVICE phase=warm_open position=0 elapsed_ms=160.540 process_jvm_before_bytes=129991392 sampled_process_jvm_peak_bytes=131220192 process_native_before_bytes=29253920 sampled_process_native_peak_bytes=29379440 restore_ms=129.179 first_three_pages_ms=160.540 pages=3 cache_bytes=2009
ENGINE_DEVICE phase=complete_save position=0 elapsed_ms=19576.423 process_jvm_before_bytes=131220192 sampled_process_jvm_peak_bytes=252801760 process_native_before_bytes=29371680 sampled_process_native_peak_bytes=29749536 saved=false cache_bytes=2169 rejected_marker=true
ENGINE_DEVICE phase=cold_parse position=85 elapsed_ms=25605.445 process_jvm_before_bytes=199520992 sampled_process_jvm_peak_bytes=264942304 process_native_before_bytes=8991296 sampled_process_native_peak_bytes=110182672 first_three_pages_ms=21441.956 prefix_emitted=true chapters=122 elements=44446 pages=17
ENGINE_DEVICE phase=preview_save position=85 elapsed_ms=18132.531 process_jvm_before_bytes=192733920 sampled_process_jvm_peak_bytes=256451296 process_native_before_bytes=58405072 sampled_process_native_peak_bytes=58739264 saved=false cache_bytes=0
ENGINE_DEVICE phase=warm_open position=85 cache_hit=false first_three_pages_ms=unavailable reason=baseline_cache_rejection
```

