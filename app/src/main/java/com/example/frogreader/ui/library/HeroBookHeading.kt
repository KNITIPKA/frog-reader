package com.example.frogreader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.frogreader.ui.theme.LocalFrogColors
import java.util.Locale
import kotlin.math.abs

/** Keeps normal type sizes, balancing short titles over two lines in compact cards. */
@Composable
internal fun HeroBookHeading(title: String, author: String?, modifier: Modifier = Modifier) {
    val shownAuthor = author?.takeIf { it.isNotBlank() }
    val baseStyle = LocalTextStyle.current
    val titleStyle = baseStyle.copy(
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 1.16.em,
        letterSpacing = (-0.3).sp,
        color = LocalFrogColors.current.ink,
    )
    val authorStyle = baseStyle.copy(
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(modifier) {
        val heading = remember(title, shownAuthor, titleStyle, authorStyle, constraints, measurer, density) {
            val widthConstraints = Constraints(maxWidth = constraints.maxWidth)
            val authorHeight = if (shownAuthor == null) 0 else with(density) { 3.dp.roundToPx() } +
                measurer.measure(
                    text = shownAuthor,
                    style = authorStyle,
                    maxLines = 1,
                    constraints = widthConstraints,
                ).size.height
            val titleConstraints = Constraints(
                maxWidth = constraints.maxWidth,
                maxHeight = (constraints.maxHeight - authorHeight).coerceAtLeast(0),
            )
            val naturalLayout = measurer.measure(
                text = title, style = titleStyle, maxLines = 2, constraints = widthConstraints,
            )

            // Only reflow a short, single-line title on compact screens.
            // Measure candidate lines rather than balancing character counts.
            val displayTitle = if (constraints.maxWidth <= with(density) { 360.dp.roundToPx() } &&
                naturalLayout.lineCount == 1 && !naturalLayout.hasVisualOverflow
            ) {
                heroTitleBreakCandidates(title).mapNotNull { candidate ->
                    val layout = measurer.measure(
                        text = candidate, style = titleStyle, maxLines = 2, constraints = widthConstraints,
                    )
                    if (layout.hasVisualOverflow || layout.lineCount != 2) return@mapNotNull null
                    val first = layout.getLineRight(0) - layout.getLineLeft(0)
                    val second = layout.getLineRight(1) - layout.getLineLeft(1)
                    // Avoid a tiny orphan word opposite a much longer line.
                    if (minOf(first, second) < maxOf(first, second) * 0.35f) null
                    else candidate to abs(first - second)
                }.minByOrNull { it.second }?.first ?: title
            } else title

            var titleSp = 20f
            while (titleSp > 14f && measurer.measure(
                    text = displayTitle,
                    style = titleStyle.copy(fontSize = titleSp.sp),
                    maxLines = 2,
                    constraints = titleConstraints,
                ).hasVisualOverflow
            ) {
                titleSp -= 0.5f
            }
            displayTitle to titleStyle.copy(fontSize = titleSp.sp)
        }

        // Any space left after wrapping belongs above the heading, keeping
        // the author close to the progress bar even on very wide screens.
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(3.dp, alignment = Alignment.Bottom),
        ) {
            Text(heading.first, style = heading.second, maxLines = 2, overflow = TextOverflow.Ellipsis)
            shownAuthor?.let {
                Text(it, style = authorStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val HeroTitleWordSpaces = Regex(" +")
private val HeroTitleConnectors = setOf(
    "a", "an", "the", "and", "or", "of", "in", "on", "to", "for", "at", "by", "from", "with",
    "и", "или", "а", "в", "во", "на", "с", "со", "к", "ко", "у", "о", "об", "от", "до",
    "по", "за", "из", "для", "без", "над", "под", "про", "і", "й", "та", "чи", "з", "зі", "із", "від",
)

/** Display-only breaks: preserve existing line breaks and non-breaking spaces. */
private fun heroTitleBreakCandidates(title: String): List<String> {
    if ('\n' in title || '\r' in title) return emptyList()
    return HeroTitleWordSpaces.findAll(title).mapNotNull { space ->
        val first = title.substring(0, space.range.first).trim()
        val second = title.substring(space.range.last + 1).trim()
        if (first.isEmpty() || second.isEmpty() || second.first() in ",.;:!?—–-") return@mapNotNull null
        val lastWord = first.substringAfterLast(' ').trim { !it.isLetter() }.lowercase(Locale.ROOT)
        if (lastWord.isEmpty() || lastWord in HeroTitleConnectors) null else "$first\n$second"
    }.toList()
}
