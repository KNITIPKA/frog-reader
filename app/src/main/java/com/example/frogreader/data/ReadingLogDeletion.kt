package com.example.frogreader.data

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.ReadingLogEntry

/** The durable deletion receipt used by Undo; it is never written into a backup or store. */
data class ReadingLogDeletion(val entry: ReadingLogEntry, val index: Int, val previousReading: Book?)
