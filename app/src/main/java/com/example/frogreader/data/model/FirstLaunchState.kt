package com.example.frogreader.data.model

import kotlinx.serialization.Serializable

/** Device-local welcome progress. Deliberately absent from AppSettings and backups. */
@Serializable
internal data class FirstLaunchState(val completed: Boolean = false)
