package com.example.frogreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.frogreader.R
import com.example.frogreader.data.CustomFontFace
import com.example.frogreader.data.CustomFontGroup
import com.example.frogreader.data.groupCustomFonts
import com.example.frogreader.data.model.EmbeddedFont
import com.example.frogreader.ui.theme.customFontPreviewFamily
import com.example.frogreader.ui.theme.floatingSurface
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Family navigation never changes the reader until Auto or a face is chosen. */
@Composable
internal fun CustomFontsDialog(
    colors: ReaderColors,
    bookFonts: List<EmbeddedFont> = emptyList(),
    bodyFontPath: String? = null,
    bodyFontAuto: Boolean = false,
    onPickBookFont: (CustomFontFace) -> Unit = {},
    onPickBookFamily: (CustomFontGroup) -> Unit = {},
    selectedPath: String?,
    selectedAxes: Map<String, Float> = emptyMap(),
    selectedAuto: Boolean = false,
    fontsVersion: Int,
    onAdd: () -> Unit,
    onPick: (CustomFontFace) -> Unit,
    onPickAuto: (CustomFontGroup) -> Unit = {},
    onDelete: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var library by remember { mutableStateOf(FontLibraryMerge()) }
    val fonts = library.fonts
    val embedded = library.embedded
    var loading by remember { mutableStateOf(true) }
    var openFamily by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    LaunchedEffect(fontsVersion, bookFonts) {
        val groups = withContext(Dispatchers.IO) {
            mergeFontLibrary(
                groupCustomFonts(File(context.filesDir, "fonts").listFiles().orEmpty().toList()),
                groupCustomFonts(bookFonts.map { File(it.path) }.distinct()),
            )
        }
        library = groups
        loading = false
        openFamily?.let { (fromBook, key) ->
            if ((if (fromBook) groups.embedded else groups.fonts).none { it.key == key }) openFamily = null
        }
    }
    val opened = openFamily?.let { (fromBook, key) ->
        (if (fromBook) embedded else fonts).firstOrNull { it.key == key }
    }
    fun goBack() { openFamily = null }
    Dialog(onDismissRequest = { if (openFamily != null) goBack() else onDismiss() }) {
        CustomFontDialogSurface {
            CustomFontsContent(
                colors = colors,
                fonts = fonts,
                embedded = embedded,
                opened = opened,
                fromBook = openFamily?.first == true,
                selectedPath = selectedPath,
                selectedAxes = selectedAxes,
                selectedAuto = selectedAuto,
                bodyFontPath = bodyFontPath,
                bodyFontAuto = bodyFontAuto,
                savedBookPaths = library.savedBookPaths,
                loading = loading,
                onBack = ::goBack,
                onOpen = { fromBook, group -> openFamily = fromBook to group.key },
                onAdd = onAdd,
                onPick = { face ->
                    if (openFamily?.first == true) {
                        onPickBookFont(face)
                    } else onPick(face)
                },
                onPickAuto = { group ->
                    if (openFamily?.first == true) onPickBookFamily(group)
                    else onPickAuto(group)
                },
                onDelete = onDelete,
            )
        }
    }
}

/** Floating dialogs stay distinct from the fixed reading paper in every theme. */
@Composable
internal fun CustomFontDialogSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.floatingSurface,
        contentColor = scheme.onSurface,
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = modifier,
        content = content,
    )
}

/** Separate from Dialog so native previews can inspect both navigation levels. */
@Composable
internal fun CustomFontsContent(
    colors: ReaderColors,
    fonts: List<CustomFontGroup>,
    embedded: List<CustomFontGroup> = emptyList(),
    opened: CustomFontGroup? = null,
    fromBook: Boolean = false,
    selectedPath: String? = null,
    selectedAxes: Map<String, Float> = emptyMap(),
    selectedAuto: Boolean = false,
    bodyFontPath: String? = null,
    bodyFontAuto: Boolean = false,
    savedBookPaths: Map<String, String> = emptyMap(),
    loading: Boolean = false,
    onBack: () -> Unit = {},
    onOpen: (Boolean, CustomFontGroup) -> Unit = { _, _ -> },
    onAdd: () -> Unit = {},
    onPick: (CustomFontFace) -> Unit = {},
    onPickAuto: (CustomFontGroup) -> Unit = {},
    onDelete: (List<String>) -> Unit = {},
    previewFamily: (CustomFontFace, String) -> FontFamily? = { face, label ->
        customFontPreviewFamily(face.path, face.weight, face.italic, face.variationAxes,
            previewText = label, fallbackFamily = face.fallbackFamily)
    },
) {
    val haptics = LocalHapticFeedback.current
    val listState = remember(opened?.key, fromBook) { LazyListState() }
    val selectedBookPath = selectedPath?.let(savedBookPaths::get)
    val embeddedPaths = embedded.flatMap { group -> group.faces.map { it.path } }.toSet()
    val savedCopies = savedBookPaths.filterKeys { it !in embeddedPaths }
    Column(Modifier.padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (opened != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.reader_fonts_back))
                }
                Spacer(Modifier.width(4.dp))
            }
            val title = opened?.name?.removePrefix("Subset-") ?: stringResource(R.string.reader_fonts_title)
            val face = opened?.preview
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontSynthesis = FontSynthesis.None),
                fontFamily = face?.let { previewFamily(it, title) },
                fontWeight = face?.let { FontWeight(it.weight) } ?: FontWeight.Bold,
                fontStyle = if (face?.italic == true) FontStyle.Italic else FontStyle.Normal,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(14.dp))
        if (opened == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(colors.accent)
                    .clickable(role = Role.Button) {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onAdd()
                    }.padding(vertical = 13.dp, horizontal = 12.dp),
            ) {
                Icon(Icons.Rounded.Add, null, tint = colors.onAccent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.reader_font_add),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onAccent,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        LazyColumn(
            modifier = Modifier.heightIn(max = 500.dp).then(
                if (opened != null) Modifier.selectableGroup() else Modifier,
            ),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (opened != null) {
                item(key = "auto:${opened.key}") {
                    FontFamilyAutoRow(
                        selected = if (fromBook) bodyFontAuto && opened.contains(bodyFontPath) ||
                            selectedAuto && opened.contains(selectedBookPath)
                            else selectedAuto && opened.contains(selectedPath),
                        onClick = { onPickAuto(opened) },
                    )
                    Text(
                        stringResource(R.string.reader_font_family_auto_description),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                items(opened.faces, key = { face -> face.key }) { face ->
                    FontLibraryRow(
                        colors = colors,
                        label = face.styleName,
                        face = face,
                        preview = previewFamily(face, face.styleName),
                        selected = if (fromBook) !bodyFontAuto && face.matchesSelection(bodyFontPath, emptyMap()) ||
                            !selectedAuto && face.matchesSelection(selectedBookPath, selectedAxes)
                            else !selectedAuto && face.matchesSelection(selectedPath, selectedAxes),
                        onClick = { onPick(face) },
                        // A virtual instance shares its file with the other styles;
                        // delete that file through the family menu instead.
                        onDelete = if (face.variationAxes.isNotEmpty()) null else if (fromBook) {
                            val saved = savedCopies.filterValues { it == face.path }.keys.toList()
                            if (saved.isEmpty()) null else ({ onDelete(saved) })
                        } else ({ onDelete(listOf(face.path)) }),
                        deleteLabel = if (fromBook) stringResource(R.string.reader_font_delete_saved_copy) else null,
                    )
                }
            } else {
                if (embedded.isNotEmpty()) {
                    item { FontLibraryLabel(stringResource(R.string.reader_fonts_in_book), colors) }
                    items(embedded, key = { group -> "book:${group.key}" }) { group ->
                        FontLibraryRow(
                            colors, group.name.removePrefix("Subset-"), group.preview, group.contains(bodyFontPath) || group.contains(selectedBookPath),
                            preview = previewFamily(group.preview, group.name.removePrefix("Subset-")),
                            family = true, onClick = { onOpen(true, group) },
                            onDelete = savedCopies.filterValues(group::contains).keys.toList().takeIf { it.isNotEmpty() }
                                ?.let { saved -> ({ onDelete(saved) }) },
                            deleteLabel = stringResource(R.string.reader_font_delete_saved_copy),
                        )
                    }
                    item { Spacer(Modifier.height(10.dp)) }
                    if (fonts.isNotEmpty()) {
                        item { FontLibraryLabel(stringResource(R.string.reader_fonts_saved), colors) }
                    }
                }
                items(fonts, key = { group -> "saved:${group.key}" }) { group ->
                    FontLibraryRow(
                        colors, group.name.removePrefix("Subset-"), group.preview, group.contains(selectedPath),
                        preview = previewFamily(group.preview, group.name.removePrefix("Subset-")),
                        family = true,
                        onClick = { onOpen(false, group) },
                        onDelete = { onDelete(group.faces.map { it.path }.distinct()) },
                    )
                }
                if (fonts.isEmpty() && embedded.isEmpty() && !loading) {
                    item {
                        Text(
                            stringResource(R.string.reader_fonts_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onChromeVariant,
                            modifier = Modifier.padding(vertical = 14.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FontFamilyAutoRow(selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val foreground = if (selected) scheme.onPrimaryContainer else scheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) scheme.primaryContainer else scheme.surfaceContainerHighest)
            .selectable(selected = selected, role = Role.RadioButton) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            }
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            Icons.Rounded.BrightnessAuto,
            contentDescription = null,
            tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.reader_font_family_auto),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                fontWeight = FontWeight.SemiBold,
                color = foreground,
            )
            Text(
                stringResource(R.string.reader_font_family_auto_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Rounded.Check, null, tint = foreground, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun FontLibraryLabel(label: String, colors: ReaderColors) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onChromeVariant,
        modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp))
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FontLibraryRow(
    colors: ReaderColors,
    label: String,
    face: CustomFontFace,
    selected: Boolean,
    preview: FontFamily?,
    family: Boolean = false,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    deleteLabel: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Column {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (selected) scheme.primaryContainer else scheme.surfaceContainer)
                    .semantics { this.selected = selected }
                    .combinedClickable(
                        role = if (family) Role.Button else Role.RadioButton,
                        onClick = onClick,
                        onLongClick = onDelete?.let {
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuOpen = true
                            }
                        },
                    ).heightIn(min = 48.dp).padding(vertical = 12.dp, horizontal = 12.dp),
            ) {
                val foreground = if (selected) scheme.onPrimaryContainer else scheme.onSurface
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSynthesis = FontSynthesis.None),
                    fontFamily = preview,
                    fontWeight = FontWeight(face.weight),
                    fontStyle = if (face.italic) FontStyle.Italic else FontStyle.Normal,
                    color = foreground,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.Check, null, tint = scheme.onPrimaryContainer)
                }
                if (family) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                        tint = if (selected) scheme.onPrimaryContainer else colors.onChromeVariant)
                }
            }
            if (onDelete != null) {
                DropdownMenu(
                    containerColor = scheme.floatingSurface,
                    tonalElevation = 0.dp,
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(deleteLabel ?: stringResource(if (family) R.string.reader_font_delete_family else R.string.reader_font_delete)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

