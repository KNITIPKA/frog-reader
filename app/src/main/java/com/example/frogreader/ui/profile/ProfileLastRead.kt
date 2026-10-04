package com.example.frogreader.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.ui.theme.LocalFrogColors

internal enum class LastReadUnit { JUST_NOW, MINUTES, HOURS, DAYS }
internal data class LastReadAge(val unit: LastReadUnit, val count: Long = 0)

/** Unknown older history stays unknown; a clock correction never shows negative time. */
internal fun lastReadAge(lastReadAtMillis: Long?, nowMillis: Long): LastReadAge? {
    if (lastReadAtMillis == null || lastReadAtMillis <= 0L) return null
    val elapsed = if (nowMillis <= lastReadAtMillis) 0L else nowMillis - lastReadAtMillis
    return when {
        elapsed < 60_000L -> LastReadAge(LastReadUnit.JUST_NOW)
        elapsed < 3_600_000L -> LastReadAge(LastReadUnit.MINUTES, elapsed / 60_000L)
        elapsed < 86_400_000L -> LastReadAge(LastReadUnit.HOURS, elapsed / 3_600_000L)
        else -> LastReadAge(LastReadUnit.DAYS, elapsed / 86_400_000L)
    }
}

@Composable
internal fun ProfileLastRead(lastReadAtMillis: Long?, nowMillis: Long) {
    val age = lastReadAge(lastReadAtMillis, nowMillis)
    val time = when (age?.unit) {
        null -> stringResource(R.string.profile_last_read_unknown)
        LastReadUnit.JUST_NOW -> stringResource(R.string.profile_last_read_now)
        LastReadUnit.MINUTES -> stringResource(R.string.profile_last_read_minutes, age.count)
        LastReadUnit.HOURS -> stringResource(R.string.profile_last_read_hours, age.count)
        LastReadUnit.DAYS -> stringResource(R.string.profile_last_read_days, age.count)
    }
    val ink = LocalFrogColors.current.onProfile
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(14.dp), tint = ink)
        Text(
            stringResource(R.string.profile_last_read, time),
            color = ink,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
