package com.example.frogreader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.R
import com.example.frogreader.ui.reader.sheetMaxContentHeight
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Rect

/**
 * The sheets behind every long press in the library.
 *
 * All of them are the same object: a `ModalBottomSheet` with the theme
 * picker's chrome (34dp corners, a pill handle, an uppercase title with a round
 * close button) over a list of [SheetRow]s. Written once here rather than three
 * times at the call sites — the app has one shape of menu, and it should keep
 * having one.
 *
 * Content is capped at [sheetMaxContentHeight]: the material3 alpha misplaces
 * hit targets once a sheet reaches full window height (see ReaderSheets).
 */

/**
 * Books on their way into a shelf.
 *
 * [fromShelfId] is the shelf they are leaving, if any — the picker hides it,
 * because "move these to the shelf they are already in" is not a choice.
 */
data class AddToShelfRequest(val bookIds: List<String>, val fromShelfId: String? = null)

/** What a long press was on, and therefore which actions apply. */
sealed interface MenuTarget {
    /** The `LibraryEntry.id` the menu belongs to, so "Select" can tick it. */
    val entryId: String

    /** A book. [shelfId] is set when it was pressed inside an open folder. */
    data class BookTarget(
        val bookId: String,
        override val entryId: String,
        val shelfId: String?,
    ) : MenuTarget

    data class ShelfTarget(val shelfId: String, override val entryId: String) : MenuTarget
}

/**
 * A menu waiting to be shown, and the item's rect in root coordinates that it
 * should hang off.
 */
data class MenuRequest(val target: MenuTarget, val anchor: Rect)

/**
 * The long-press menu, hanging off the item it belongs to.
 *
 * A dropdown and not a bottom sheet: a half-screen panel for "rename this
 * folder" covers the thing being renamed and reads as a page change. The caller
 * puts this inside a zero-content Box laid over the item's rect, so the menu
 * anchors to the cover and flips above it near the bottom of the screen — the
 * launcher behaviour this replaced.
 */
@Composable
internal fun LibraryItemMenu(
    target: MenuTarget,
    onSelect: () -> Unit,
    onAddToShelf: () -> Unit,
    onAddBooks: () -> Unit,
    onRemoveFromShelf: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    // Opened on the frame AFTER the anchor appears, so the menu plays its
    // scale-in instead of being born already expanded.
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { expanded = true }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = scheme.surfaceContainerHigh,
        modifier = Modifier.width(216.dp),
    ) {
        // Filling a folder is the thing a folder is FOR, so for a folder it
        // comes before everything else. A book has no such headline action and
        // starts with Select, as it did.
        if (target is MenuTarget.ShelfTarget) {
            FrogMenuItem(
                icon = Icons.Rounded.LibraryAdd,
                label = stringResource(R.string.shelf_add_books),
                onClick = onAddBooks,
            )
        }
        FrogMenuItem(
            icon = Icons.Rounded.CheckCircle,
            label = stringResource(R.string.library_menu_select),
            onClick = onSelect,
        )
        when (target) {
            is MenuTarget.BookTarget -> {
                // A book on a shelf is offered the way OUT of it, not another
                // way in: it can only be on one shelf, so "add to shelf" while
                // it is already on one is really a move, and reads as a second
                // membership.
                if (target.shelfId != null) {
                    FrogMenuItem(
                        icon = Icons.Rounded.RemoveCircleOutline,
                        label = stringResource(R.string.shelf_menu_remove_from_shelf),
                        onClick = onRemoveFromShelf,
                    )
                } else {
                    FrogMenuItem(
                        icon = Icons.Rounded.LibraryAdd,
                        label = stringResource(R.string.library_menu_add_to_shelf),
                        onClick = onAddToShelf,
                    )
                }
                FrogMenuItem(
                    icon = Icons.Rounded.Edit,
                    label = stringResource(R.string.library_menu_edit),
                    onClick = onEdit,
                )
            }

            is MenuTarget.ShelfTarget -> FrogMenuItem(
                icon = Icons.Rounded.DriveFileRenameOutline,
                label = stringResource(R.string.shelf_menu_rename),
                onClick = onRename,
            )
        }
        FrogMenuItem(
            icon = Icons.Rounded.Delete,
            label = when (target) {
                is MenuTarget.BookTarget -> stringResource(R.string.library_menu_remove)
                is MenuTarget.ShelfTarget -> stringResource(R.string.shelf_menu_delete)
            },
            destructive = true,
            onClick = onDelete,
        )
    }
}

/** One row of a Frog dropdown: uppercase, wide-tracked, 20dp leading icon. */
@Composable
internal fun FrogMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val text = if (destructive) scheme.error else scheme.onSurface
    val tint = if (destructive) scheme.error else scheme.onSurfaceVariant

    DropdownMenuItem(
        text = {
            Text(
                text = label.uppercase(),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
                color = text,
            )
        },
        leadingIcon = {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 18.dp),
    )
}

/**
 * Picks the shelf some books are going into, or makes a new one for them.
 *
 * "New shelf" comes first and not last: with no shelves yet it is the only
 * thing on the list, and burying the one available action under an empty list
 * would be perverse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToShelfSheet(
    shelves: List<LibraryEntry.ShelfEntry>,
    exceptShelfId: String?,
    onNewShelf: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    FrogActionSheet(title = stringResource(R.string.shelf_add_title), onDismiss = onDismiss) {
        SheetRow(
            icon = Icons.Rounded.Add,
            label = stringResource(R.string.shelf_add_new),
            secondary = stringResource(R.string.shelf_add_new_hint),
            accent = true,
            onClick = onNewShelf,
        )
        shelves
            .filterNot { it.shelf.id == exceptShelfId }
            .forEach { entry ->
                // entry.books, not shelf.bookIds: the stored list can still name
                // a book that no longer resolves, and the count should not.
                val count = entry.books.size
                SheetRow(
                    icon = Icons.Rounded.FolderOpen,
                    label = shelfName(entry),
                    secondary = pluralStringResource(R.plurals.shelf_books_count, count, count),
                    onClick = { onPick(entry.shelf.id) },
                )
            }
    }
}

/**
 * The two ways to be rid of a folder. Kept apart on purpose: "remove shelf" and
 * "delete books" differ by everything the user cares about, and a single
 * "Delete?" with a Yes button would make that difference invisible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeleteShelfSheet(
    onRemoveShelf: () -> Unit,
    onDeleteBooks: () -> Unit,
    onDismiss: () -> Unit,
) {
    FrogActionSheet(title = stringResource(R.string.shelf_delete_title), onDismiss = onDismiss) {
        SheetRow(
            icon = Icons.Rounded.FolderOpen,
            label = stringResource(R.string.shelf_delete_remove_shelf),
            secondary = stringResource(R.string.shelf_delete_remove_shelf_hint),
            onClick = onRemoveShelf,
        )
        SheetRow(
            icon = Icons.Rounded.DeleteForever,
            label = stringResource(R.string.shelf_delete_books),
            secondary = stringResource(R.string.shelf_delete_books_hint),
            destructive = true,
            onClick = onDeleteBooks,
        )
    }
}

/**
 * What a confirmed "delete" is about to take away.
 *
 * Folders in a multi-selection are DISSOLVED — their books stay in the library.
 * Taking a folder's books away with it is a separate, spelled-out choice in
 * [DeleteShelfSheet], and it should not be something a bulk tick can trigger.
 */
data class PendingRemoval(val bookIds: List<String>, val shelfIds: List<String> = emptyList()) {
    /** True for the everyday case: one book, named, nothing else caught up in it. */
    val isSingleBook: Boolean get() = bookIds.size == 1 && shelfIds.isEmpty()

    @Composable
    fun describe(): String = listOfNotNull(
        bookIds.size.takeIf { it > 0 }?.let {
            pluralStringResource(R.plurals.library_selection_delete_books, it, it)
        },
        shelfIds.size.takeIf { it > 0 }?.let {
            pluralStringResource(R.plurals.library_selection_delete_shelves, it, it)
        },
    ).joinToString(" ")
}

/** "Remove from library", for one book or for a whole selection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfirmRemoveSheet(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    FrogActionSheet(title = title, onDismiss = onDismiss) {
        SheetRow(
            icon = Icons.Rounded.Delete,
            label = stringResource(R.string.library_menu_remove),
            secondary = message,
            destructive = true,
            onClick = onConfirm,
        )
    }
}

// ------------------------------------------------------------------- chrome

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrogActionSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 14.dp, bottom = 14.dp)
                    .size(width = 34.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = sheetMaxContentHeight())
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 28.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title.uppercase(),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.library_delete_cancel),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SheetRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    secondary: String? = null,
    destructive: Boolean = false,
    accent: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val tint = when {
        destructive -> scheme.error
        accent -> scheme.primary
        else -> scheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    when {
                        destructive -> scheme.error.copy(alpha = 0.12f)
                        accent -> scheme.primary.copy(alpha = 0.14f)
                        else -> scheme.surfaceContainer
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 13.5.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Medium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondary != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = secondary,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}
