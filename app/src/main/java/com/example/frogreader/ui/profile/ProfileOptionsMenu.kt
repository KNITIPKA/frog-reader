package com.example.frogreader.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.example.frogreader.R
import com.example.frogreader.ui.theme.floatingSurface

@Composable
internal fun ProfileOptionsMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onChangeName: () -> Unit,
    onChangeAvatar: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.widthIn(min = 224.dp, max = 280.dp),
        shape = RoundedCornerShape(20.dp),
        containerColor = colors.floatingSurface,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, colors.outlineVariant),
        // Release focus as soon as a rename is selected, while the popup fades out.
        properties = PopupProperties(focusable = expanded),
    ) {
        ProfileOptionsMenuItems(onChangeName, onChangeAvatar)
    }
}

@Composable
internal fun ProfileOptionsMenuItems(onChangeName: () -> Unit, onChangeAvatar: () -> Unit) {
    ProfileOption(Icons.Rounded.Edit, stringResource(R.string.profile_edit_name), onChangeName)
    ProfileOption(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.profile_photo_change), onChangeAvatar)
}

@Composable
private fun ProfileOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) },
        leadingIcon = {
            Box(
                Modifier.size(34.dp).background(colors.secondaryContainer, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(19.dp), tint = colors.onSecondaryContainer)
            }
        },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 56.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    )
}
