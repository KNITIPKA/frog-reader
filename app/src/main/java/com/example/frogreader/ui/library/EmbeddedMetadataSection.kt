package com.example.frogreader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.frogreader.R

/** Unknown/custom properties remain readable without overwhelming the book's title page. */
@Composable
internal fun EmbeddedMetadataSection(fields: Map<String, String>, modifier: Modifier = Modifier) {
    if (fields.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.pdf_metadata_hide else R.string.pdf_metadata_show))
        }
        if (expanded) Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            fields.forEach { (label, value) ->
                BookDetailField(value, label = label, textStyle = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
