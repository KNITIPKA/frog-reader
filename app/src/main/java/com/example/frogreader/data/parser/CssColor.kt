package com.example.frogreader.data.parser

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Dependency-free parser for CSS `<color>` values used by the native reader engines.
 *
 * The returned integer uses Android's ARGB layout (`AARRGGBB`). CSS-wide keywords and
 * `currentColor` deliberately resolve to `null`: their meaning depends on the cascade and
 * therefore cannot be decided by a value parser.
 */
internal object CssColor {

    fun parse(raw: String): Int? {
        val value = raw.trim().lowercase()
        if (value.isEmpty() || value in UNRESOLVED_KEYWORDS) return null

        NAMED_COLORS[value]?.let { return it }
        if (value.startsWith('#')) return parseHex(value)

        val openParenthesis = value.indexOf('(')
        if (
            openParenthesis <= 0 ||
            !value.endsWith(')') ||
            value.substring(0, openParenthesis).any { it !in 'a'..'z' }
        ) {
            return null
        }

        val function = value.substring(0, openParenthesis)
        val arguments = value.substring(openParenthesis + 1, value.lastIndex)
        // Nested functions (var(), calc(), color-mix(), …) belong to the cascade/value
        // resolver. Rejecting them here also prevents accepting a stray closing parenthesis.
        if ('(' in arguments || ')' in arguments) return null

        return when (function) {
            "rgb", "rgba" -> parseRgb(arguments)
            "hsl", "hsla" -> parseHsl(arguments)
            else -> null
        }
    }

    private fun parseHex(value: String): Int? {
        val digits = value.drop(1)
        if (digits.length !in setOf(3, 4, 6, 8)) return null
        val nibbles = IntArray(digits.length)
        for (index in digits.indices) {
            nibbles[index] = digits[index].digitToIntOrNull(16) ?: return null
        }

        return when (nibbles.size) {
            3 -> argb(
                alpha = 0xff,
                red = expandNibble(nibbles[0]),
                green = expandNibble(nibbles[1]),
                blue = expandNibble(nibbles[2]),
            )

            4 -> argb(
                alpha = expandNibble(nibbles[3]),
                red = expandNibble(nibbles[0]),
                green = expandNibble(nibbles[1]),
                blue = expandNibble(nibbles[2]),
            )

            6 -> argb(
                alpha = 0xff,
                red = nibbles[0] * 16 + nibbles[1],
                green = nibbles[2] * 16 + nibbles[3],
                blue = nibbles[4] * 16 + nibbles[5],
            )

            8 -> argb(
                alpha = nibbles[6] * 16 + nibbles[7],
                red = nibbles[0] * 16 + nibbles[1],
                green = nibbles[2] * 16 + nibbles[3],
                blue = nibbles[4] * 16 + nibbles[5],
            )

            else -> null
        }
    }

    private fun parseRgb(arguments: String): Int? {
        val components = parseFunctionalComponents(arguments, channelCount = 3) ?: return null
        val channels = components.channels
        val percentageChannels = channels.map { it.isPercentage() }
        // The legacy comma grammar requires all three channels to use the same kind. The
        // modern whitespace grammar deliberately allows numbers and percentages to mix.
        if (
            components.legacyCommaSyntax &&
            percentageChannels.any { it } &&
            !percentageChannels.all { it }
        ) {
            return null
        }

        val red = parseRgbChannel(channels[0]) ?: return null
        val green = parseRgbChannel(channels[1]) ?: return null
        val blue = parseRgbChannel(channels[2]) ?: return null
        val alpha = components.alpha?.let(::parseAlpha)
            ?: if (components.alpha == null) 0xff else return null
        return argb(alpha, red, green, blue)
    }

    private fun parseHsl(arguments: String): Int? {
        val components = parseFunctionalComponents(arguments, channelCount = 3) ?: return null
        val hue = parseHue(components.channels[0]) ?: return null
        val saturation = parseHslChannel(
            component = components.channels[1],
            allowNumber = !components.legacyCommaSyntax,
        ) ?: return null
        val lightness = parseHslChannel(
            component = components.channels[2],
            allowNumber = !components.legacyCommaSyntax,
        ) ?: return null
        val alpha = components.alpha?.let(::parseAlpha)
            ?: if (components.alpha == null) 0xff else return null

        val normalizedHue = ((hue % 360.0) + 360.0) % 360.0
        val s = saturation.coerceIn(0.0, 1.0)
        val l = lightness.coerceIn(0.0, 1.0)
        val chroma = (1.0 - abs(2.0 * l - 1.0)) * s
        val hueSector = normalizedHue / 60.0
        val secondary = chroma * (1.0 - abs(hueSector % 2.0 - 1.0))
        val (redPrime, greenPrime, bluePrime) = when {
            hueSector < 1.0 -> Triple(chroma, secondary, 0.0)
            hueSector < 2.0 -> Triple(secondary, chroma, 0.0)
            hueSector < 3.0 -> Triple(0.0, chroma, secondary)
            hueSector < 4.0 -> Triple(0.0, secondary, chroma)
            hueSector < 5.0 -> Triple(secondary, 0.0, chroma)
            else -> Triple(chroma, 0.0, secondary)
        }
        val match = l - chroma / 2.0
        return argb(
            alpha = alpha,
            red = ((redPrime + match) * 255.0).coerceIn(0.0, 255.0).roundToInt(),
            green = ((greenPrime + match) * 255.0).coerceIn(0.0, 255.0).roundToInt(),
            blue = ((bluePrime + match) * 255.0).coerceIn(0.0, 255.0).roundToInt(),
        )
    }

    private fun parseFunctionalComponents(
        arguments: String,
        channelCount: Int,
    ): FunctionalComponents? {
        if (',' in arguments) {
            // A slash belongs only to modern, whitespace-separated syntax.
            if ('/' in arguments) return null
            val parts = arguments.split(',').map(String::trim)
            if (parts.size !in channelCount..(channelCount + 1) || parts.any(String::isEmpty)) {
                return null
            }
            return FunctionalComponents(
                channels = parts.take(channelCount),
                alpha = parts.getOrNull(channelCount),
                legacyCommaSyntax = true,
            )
        }

        val slash = arguments.indexOf('/')
        if (slash != arguments.lastIndexOf('/')) return null
        val channelText = if (slash >= 0) arguments.substring(0, slash) else arguments
        val alphaText = if (slash >= 0) arguments.substring(slash + 1) else null
        val channels = channelText.cssTokens()
        if (channels.size != channelCount) return null
        val alpha = alphaText?.cssTokens()?.singleOrNull() ?: if (alphaText != null) return null else null
        return FunctionalComponents(
            channels = channels,
            alpha = alpha,
            legacyCommaSyntax = false,
        )
    }

    private fun parseRgbChannel(component: String): Int? {
        val numeric = if (component.isPercentage()) {
            parsePercentage(component)?.times(255.0)
        } else {
            parseNumber(component)
        } ?: return null
        return numeric.coerceIn(0.0, 255.0).roundToInt()
    }

    private fun parseAlpha(component: String): Int? {
        val numeric = if (component.isPercentage()) {
            parsePercentage(component)
        } else {
            parseNumber(component)
        } ?: return null
        return (numeric.coerceIn(0.0, 1.0) * 255.0).roundToInt()
    }

    private fun parsePercentage(component: String): Double? {
        if (!component.isPercentage()) return null
        return parseNumber(component.dropLast(1))?.div(100.0)
    }

    private fun parseHslChannel(component: String, allowNumber: Boolean): Double? =
        if (component.isPercentage()) {
            parsePercentage(component)
        } else if (allowNumber) {
            // CSS Color 4 gives modern HSL numbers a 0..100 reference range, matching
            // percentages (for example, `hsl(120 100 50)` is lime).
            parseNumber(component)?.div(100.0)
        } else {
            null
        }

    private fun parseHue(component: String): Double? {
        val match = ANGLE.matchEntire(component) ?: return null
        val number = parseNumber(match.groupValues[1]) ?: return null
        return when (match.groupValues[2]) {
            "", "deg" -> number
            "grad" -> number * 0.9
            "rad" -> number * 180.0 / PI
            "turn" -> number * 360.0
            else -> null
        }?.takeIf(Double::isFinite)
    }

    private fun parseNumber(component: String): Double? {
        if (!NUMBER.matches(component)) return null
        return component.toDoubleOrNull()?.takeIf(Double::isFinite)
    }

    private fun String.cssTokens(): List<String> {
        val trimmed = trim()
        return if (trimmed.isEmpty()) emptyList() else trimmed.split(CSS_WHITESPACE)
    }

    private fun String.isPercentage(): Boolean = endsWith('%')

    private fun expandNibble(value: Int): Int = value * 17

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        ((alpha and 0xff) shl 24) or
            ((red and 0xff) shl 16) or
            ((green and 0xff) shl 8) or
            (blue and 0xff)

    private data class FunctionalComponents(
        val channels: List<String>,
        val alpha: String?,
        val legacyCommaSyntax: Boolean,
    )

    private val CSS_WHITESPACE = Regex("\\s+")
    private const val NUMBER_SOURCE = "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"
    private val NUMBER = Regex("^$NUMBER_SOURCE$")
    private val ANGLE = Regex("^($NUMBER_SOURCE)(deg|grad|rad|turn)?$")

    private val UNRESOLVED_KEYWORDS = setOf(
        "currentcolor",
        "inherit",
        "initial",
        "unset",
    )

    private val NAMED_COLORS: Map<String, Int> = buildMap {
        NAMED_COLOR_DATA.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { entry ->
                val separator = entry.indexOf(' ')
                check(separator > 0) { "Malformed CSS named-color entry: $entry" }
                val name = entry.substring(0, separator)
                val rgb = entry.substring(separator + 1).trim().toInt(16)
                put(name, 0xff000000.toInt() or rgb)
            }
        put("transparent", 0x00000000)
    }

    /** CSS Color 4 named colors (aliases intentionally retained). */
    private const val NAMED_COLOR_DATA = """
        aliceblue F0F8FF
        antiquewhite FAEBD7
        aqua 00FFFF
        aquamarine 7FFFD4
        azure F0FFFF
        beige F5F5DC
        bisque FFE4C4
        black 000000
        blanchedalmond FFEBCD
        blue 0000FF
        blueviolet 8A2BE2
        brown A52A2A
        burlywood DEB887
        cadetblue 5F9EA0
        chartreuse 7FFF00
        chocolate D2691E
        coral FF7F50
        cornflowerblue 6495ED
        cornsilk FFF8DC
        crimson DC143C
        cyan 00FFFF
        darkblue 00008B
        darkcyan 008B8B
        darkgoldenrod B8860B
        darkgray A9A9A9
        darkgreen 006400
        darkgrey A9A9A9
        darkkhaki BDB76B
        darkmagenta 8B008B
        darkolivegreen 556B2F
        darkorange FF8C00
        darkorchid 9932CC
        darkred 8B0000
        darksalmon E9967A
        darkseagreen 8FBC8F
        darkslateblue 483D8B
        darkslategray 2F4F4F
        darkslategrey 2F4F4F
        darkturquoise 00CED1
        darkviolet 9400D3
        deeppink FF1493
        deepskyblue 00BFFF
        dimgray 696969
        dimgrey 696969
        dodgerblue 1E90FF
        firebrick B22222
        floralwhite FFFAF0
        forestgreen 228B22
        fuchsia FF00FF
        gainsboro DCDCDC
        ghostwhite F8F8FF
        gold FFD700
        goldenrod DAA520
        gray 808080
        green 008000
        greenyellow ADFF2F
        grey 808080
        honeydew F0FFF0
        hotpink FF69B4
        indianred CD5C5C
        indigo 4B0082
        ivory FFFFF0
        khaki F0E68C
        lavender E6E6FA
        lavenderblush FFF0F5
        lawngreen 7CFC00
        lemonchiffon FFFACD
        lightblue ADD8E6
        lightcoral F08080
        lightcyan E0FFFF
        lightgoldenrodyellow FAFAD2
        lightgray D3D3D3
        lightgreen 90EE90
        lightgrey D3D3D3
        lightpink FFB6C1
        lightsalmon FFA07A
        lightseagreen 20B2AA
        lightskyblue 87CEFA
        lightslategray 778899
        lightslategrey 778899
        lightsteelblue B0C4DE
        lightyellow FFFFE0
        lime 00FF00
        limegreen 32CD32
        linen FAF0E6
        magenta FF00FF
        maroon 800000
        mediumaquamarine 66CDAA
        mediumblue 0000CD
        mediumorchid BA55D3
        mediumpurple 9370DB
        mediumseagreen 3CB371
        mediumslateblue 7B68EE
        mediumspringgreen 00FA9A
        mediumturquoise 48D1CC
        mediumvioletred C71585
        midnightblue 191970
        mintcream F5FFFA
        mistyrose FFE4E1
        moccasin FFE4B5
        navajowhite FFDEAD
        navy 000080
        oldlace FDF5E6
        olive 808000
        olivedrab 6B8E23
        orange FFA500
        orangered FF4500
        orchid DA70D6
        palegoldenrod EEE8AA
        palegreen 98FB98
        paleturquoise AFEEEE
        palevioletred DB7093
        papayawhip FFEFD5
        peachpuff FFDAB9
        peru CD853F
        pink FFC0CB
        plum DDA0DD
        powderblue B0E0E6
        purple 800080
        rebeccapurple 663399
        red FF0000
        rosybrown BC8F8F
        royalblue 4169E1
        saddlebrown 8B4513
        salmon FA8072
        sandybrown F4A460
        seagreen 2E8B57
        seashell FFF5EE
        sienna A0522D
        silver C0C0C0
        skyblue 87CEEB
        slateblue 6A5ACD
        slategray 708090
        slategrey 708090
        snow FFFAFA
        springgreen 00FF7F
        steelblue 4682B4
        tan D2B48C
        teal 008080
        thistle D8BFD8
        tomato FF6347
        turquoise 40E0D0
        violet EE82EE
        wheat F5DEB3
        white FFFFFF
        whitesmoke F5F5F5
        yellow FFFF00
        yellowgreen 9ACD32
    """
}
