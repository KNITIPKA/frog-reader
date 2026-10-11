package com.example.frogreader.ui.settings

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.example.frogreader.R
import com.example.frogreader.ui.theme.floatingSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class AboutLink(
    val title: Int,
    val subtitle: Int,
    val uri: String,
    val drawable: Int? = null,
    val materialIcon: ImageVector? = null,
)

private val supportLinks = listOf(
    AboutLink(
        R.string.settings_about_telegram, R.string.settings_about_support_subtitle,
        "https://t.me/knitipka", drawable = R.drawable.ic_about_telegram,
    ),
    AboutLink(
        R.string.settings_about_signal, R.string.settings_about_support_subtitle,
        "https://signal.me/#eu/gXfCfTAgdX8qNuAaZ-L3ozxFAJ0qb1ObkD9t2TNTVr6iPYYMhVVOSrXw-5pjdEjb",
        drawable = R.drawable.ic_about_signal,
    ),
    AboutLink(
        R.string.settings_about_email, R.string.settings_about_email_subtitle,
        "mailto:knitipka@proton.me", materialIcon = Icons.Outlined.Email,
    ),
)

private val projectLinks = listOf(
    AboutLink(
        R.string.settings_about_github, R.string.settings_about_github_subtitle,
        "https://github.com/KNITIPKA/frog-reader", drawable = R.drawable.ic_about_github,
    ),
    AboutLink(
        R.string.settings_about_releases, R.string.settings_about_releases_subtitle,
        "https://github.com/KNITIPKA/frog-reader/releases", materialIcon = Icons.Rounded.LocalOffer,
    ),
)

private val channelLink = AboutLink(
    R.string.settings_about_channel, R.string.settings_about_channel_subtitle,
    "https://t.me/frogreader", materialIcon = Icons.Rounded.Groups,
)

@Composable
internal fun AboutDialog(onDismiss: () -> Unit, onOpenFailed: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val appInfo = remember(context) { installedAppInfo(context) }
    val failedMessage = stringResource(R.string.settings_link_failed)
    val copyFailedMessage = stringResource(R.string.settings_about_diagnostics_failed)
    val copyLabel = stringResource(R.string.settings_about_diagnostics)
    val emailSubject = stringResource(R.string.settings_about_email_subject)
    var copied by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf(false) }
    var licensePage by remember { mutableStateOf<AboutLicensePage?>(null) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        AboutPopup(
            versionName = appInfo.versionName,
            copied = copied,
            onDismiss = onDismiss,
            onOpenLink = { uri ->
                val intent = if (uri.startsWith("mailto:")) {
                    Intent(Intent.ACTION_SENDTO, uri.toUri()).putExtra(Intent.EXTRA_SUBJECT, emailSubject)
                } else {
                    Intent(Intent.ACTION_VIEW, uri.toUri())
                }
                runCatching { context.startActivity(intent) }
                    .onFailure { onOpenFailed(failedMessage) }
            },
            onCopyDiagnostics = {
                if (!copying) scope.launch {
                    copying = true
                    copied = false
                    try {
                        val diagnostics = collectAppDiagnosticInfo(context, appInfo)
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(copyLabel, diagnostics)))
                        copied = true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        onOpenFailed(copyFailedMessage)
                    } finally {
                        copying = false
                    }
                }
            },
            onOpenLicenses = { licensePage = it },
        )
    }
    licensePage?.let { page ->
        AboutLicensesDialog(initialPage = page, onDismiss = { licensePage = null })
    }
}

/** The whole popup is visible at once, including on short screens and at large text sizes. */
@Composable
internal fun AboutPopup(
    versionName: String,
    copied: Boolean,
    onDismiss: () -> Unit,
    onOpenLink: (String) -> Unit,
    onCopyDiagnostics: () -> Unit,
    onOpenLicenses: (AboutLicensePage) -> Unit = {},
) {
    BoxWithConstraints(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val wide = maxWidth >= 560.dp && maxHeight < 560.dp
        FitAboutPopup(Modifier.widthIn(max = if (wide) 720.dp else 520.dp).fillMaxWidth()) {
            AboutContent(versionName, copied, wide, onDismiss, onOpenLink, onCopyDiagnostics, onOpenLicenses)
        }
    }
}

/** Measure the complete popup first; only unusually tight viewports need to scale it down. */
@Composable
private fun FitAboutPopup(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val popup = measurables.single().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val scale = if (constraints.hasBoundedHeight && popup.height > constraints.maxHeight) {
            constraints.maxHeight.toFloat() / popup.height
        } else 1f
        val width = constraints.constrainWidth(popup.width)
        layout(width, (popup.height * scale).roundToInt()) {
            val left = ((width - popup.width * scale) / 2f).roundToInt()
            popup.placeWithLayer(left, 0) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
            }
        }
    }
}

@Composable
private fun AboutContent(
    versionName: String,
    copied: Boolean,
    wide: Boolean,
    onDismiss: () -> Unit,
    onOpenLink: (String) -> Unit,
    onCopyDiagnostics: () -> Unit,
    onOpenLicenses: (AboutLicensePage) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val viewLicense = stringResource(R.string.settings_about_view_license)
    val viewThirdParty = stringResource(R.string.settings_about_view_third_party)
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = colors.floatingSurface,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(start = 2.dp, top = 4.dp)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_about_version, versionName),
                        modifier = Modifier.clickable(role = Role.Button, onClickLabel = viewLicense) {
                            onOpenLicenses(AboutLicensePage.APP)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(44.dp).background(colors.surfaceContainerHigh, CircleShape),
                ) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.settings_close), Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.settings_about_intro),
                modifier = Modifier.padding(horizontal = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f)) {
                        AboutSupport(copied, onOpenLink, onCopyDiagnostics)
                    }
                    Column(Modifier.weight(1f)) {
                        AboutProject(onOpenLink)
                        Spacer(Modifier.height(18.dp))
                        AboutUpdates(onOpenLink)
                    }
                }
            } else {
                AboutSupport(copied, onOpenLink, onCopyDiagnostics)
                Spacer(Modifier.height(18.dp))
                AboutProject(onOpenLink)
                Spacer(Modifier.height(18.dp))
                AboutUpdates(onOpenLink)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.settings_about_credits),
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = viewThirdParty) {
                    onOpenLicenses(AboutLicensePage.THIRD_PARTY)
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AboutSupport(copied: Boolean, onOpenLink: (String) -> Unit, onCopy: () -> Unit) {
    AboutSectionTitle(stringResource(R.string.settings_about_support))
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        supportLinks.forEach { link ->
            AboutSupportCard(link, { onOpenLink(link.uri) }, Modifier.weight(1f).fillMaxHeight())
        }
    }
    Spacer(Modifier.height(10.dp))
    AboutActionSurface {
        AboutActionRow(
            title = stringResource(if (copied) R.string.settings_about_diagnostics_copied else R.string.settings_about_diagnostics),
            subtitle = stringResource(R.string.settings_about_diagnostics_subtitle),
            icon = { Icon(Icons.Outlined.Description, null, Modifier.size(22.dp)) },
            trailing = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            onClick = onCopy,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun AboutSupportCard(link: AboutLink, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 5.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AboutIconBadge(size = 42.dp) { AboutLinkIcon(link, Modifier.size(24.dp)) }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(link.title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(link.subtitle),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AboutProject(onOpenLink: (String) -> Unit) {
    AboutSectionTitle(stringResource(R.string.settings_about_project))
    AboutActionSurface {
        projectLinks.forEachIndexed { index, link ->
            if (index > 0) {
                HorizontalDivider(
                    Modifier.padding(start = 60.dp, end = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            AboutActionRow(
                title = stringResource(link.title), subtitle = stringResource(link.subtitle),
                icon = { AboutLinkIcon(link, Modifier.size(22.dp)) },
                onClick = { onOpenLink(link.uri) },
            )
        }
    }
}

@Composable
private fun AboutUpdates(onOpenLink: (String) -> Unit) {
    AboutSectionTitle(stringResource(R.string.settings_about_updates))
    AboutActionSurface {
        AboutActionRow(
            title = stringResource(channelLink.title), subtitle = stringResource(channelLink.subtitle),
            icon = { AboutLinkIcon(channelLink, Modifier.size(22.dp)) },
            onClick = { onOpenLink(channelLink.uri) },
        )
    }
}

@Composable
private fun AboutSectionTitle(title: String) {
    Text(
        title, Modifier.padding(start = 2.dp, bottom = 10.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun AboutActionSurface(content: @Composable () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp,
    ) { Column { content() } }
}

@Composable
private fun AboutActionRow(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: ImageVector = Icons.AutoMirrored.Rounded.OpenInNew,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AboutIconBadge(size = 38.dp, icon = icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                color = colors.onSurfaceVariant,
            )
        }
        Icon(trailing, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
private fun AboutIconBadge(size: androidx.compose.ui.unit.Dp, icon: @Composable () -> Unit) {
    Surface(
        Modifier.size(size), shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 0.dp,
    ) { Box(contentAlignment = Alignment.Center) { icon() } }
}

@Composable
private fun AboutLinkIcon(link: AboutLink, modifier: Modifier) {
    if (link.drawable != null) {
        Icon(painterResource(link.drawable), contentDescription = null, modifier = modifier)
    } else {
        Icon(requireNotNull(link.materialIcon), contentDescription = null, modifier = modifier)
    }
}
