package com.example.frogreader.ui.reader

import android.util.Log

/** Local performance diagnostics, containing durations/counts rather than book text. */
internal class ReaderLoadTrace {
    private val started = System.nanoTime()
    private var surfaceRecorded = false

    fun stage(name: String, since: Long, count: Int = 0) {
        val now = System.nanoTime()
        Log.i("FrogReaderLoad", "$name ms=${(now - since) / 1_000_000} total_ms=${(now - started) / 1_000_000} count=$count")
    }

    fun surfaceReady() {
        if (surfaceRecorded) return
        surfaceRecorded = true
        stage("surface_ready", started)
    }
}
