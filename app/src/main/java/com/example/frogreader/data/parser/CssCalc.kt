package com.example.frogreader.data.parser

/**
 * Evaluator for CSS math functions — `calc()`, `min()`, `max()`, `clamp()` —
 * in the caller's output space. The caller describes how each unit maps into
 * that space via [Ctx]; anything unresolvable (mixed dimensions, division by
 * zero, unknown units) evaluates to null and the declaration is ignored,
 * exactly like an unsupported plain value.
 */
internal object CssCalc {

    /**
     * Unit mapping into the caller's output space: [emUnit] = value of `1em`,
     * [remUnit] = `1rem` (also the base for viewport units), [pxUnit] = `1px`,
     * [percentUnit] = `1%` (null → percentages are invalid here).
     */
    class Ctx(
        val emUnit: Float,
        val remUnit: Float,
        val pxUnit: Float,
        val percentUnit: Float?,
    )

    fun isMath(value: String): Boolean =
        value.startsWith("calc(") || value.startsWith("min(") ||
            value.startsWith("max(") || value.startsWith("clamp(")

    /**
     * The numeric result, or null. A dimensionless result is only accepted
     * when [acceptScalar] (line-height) — or when it is exactly zero.
     */
    fun eval(expr: String, ctx: Ctx, acceptScalar: Boolean = false): Float? {
        val parser = Parser(expr.trim(), ctx)
        val value = runCatching { parser.parseFactor() }.getOrNull() ?: return null
        parser.skipWhitespace()
        if (parser.pos != parser.text.length) return null
        if (!value.isLength && !acceptScalar && value.number != 0f) return null
        return value.number
    }

    private class Value(val number: Float, val isLength: Boolean)

    private class Parser(val text: String, val ctx: Ctx) {
        var pos = 0

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        /** expr := term (('+' | '-') term)*, operators whitespace-delimited. */
        fun parseExpr(): Value? {
            var left = parseTerm() ?: return null
            while (true) {
                val save = pos
                skipWhitespace()
                val op = if (pos < text.length) text[pos] else ' '
                // CSS requires whitespace around +/- (else "-3" is a number).
                if ((op == '+' || op == '-') && pos > save) {
                    pos++
                    if (pos >= text.length || !text[pos].isWhitespace()) return null
                    val right = parseTerm() ?: return null
                    if (left.isLength != right.isLength) return null
                    left = Value(
                        if (op == '+') left.number + right.number else left.number - right.number,
                        left.isLength,
                    )
                } else {
                    pos = save
                    return left
                }
            }
        }

        /** term := factor (('*' | '/') factor)* */
        fun parseTerm(): Value? {
            var left = parseFactor() ?: return null
            while (true) {
                val save = pos
                skipWhitespace()
                val op = if (pos < text.length) text[pos] else ' '
                if (op == '*' || op == '/') {
                    pos++
                    val right = parseFactor() ?: return null
                    left = if (op == '*') {
                        if (left.isLength && right.isLength) return null
                        Value(left.number * right.number, left.isLength || right.isLength)
                    } else {
                        if (right.isLength || right.number == 0f) return null
                        Value(left.number / right.number, left.isLength)
                    }
                } else {
                    pos = save
                    return left
                }
            }
        }

        /** factor := number unit? | '(' expr ')' | func '(' expr (',' expr)* ')' */
        fun parseFactor(): Value? {
            skipWhitespace()
            if (pos >= text.length) return null
            val c = text[pos]
            if (c == '(') {
                pos++
                val inner = parseExpr() ?: return null
                skipWhitespace()
                if (pos >= text.length || text[pos] != ')') return null
                pos++
                return inner
            }
            if (c.isLetter()) return parseFunction()
            return parseNumber()
        }

        private fun parseFunction(): Value? {
            val start = pos
            while (pos < text.length && text[pos].isLetter()) pos++
            val name = text.substring(start, pos).lowercase()
            skipWhitespace()
            if (pos >= text.length || text[pos] != '(') return null
            pos++
            val args = mutableListOf<Value>()
            while (true) {
                args += parseExpr() ?: return null
                skipWhitespace()
                if (pos < text.length && text[pos] == ',') {
                    pos++
                    continue
                }
                break
            }
            if (pos >= text.length || text[pos] != ')') return null
            pos++
            if (args.map { it.isLength }.distinct().size > 1) return null
            val isLength = args.first().isLength
            return when (name) {
                "calc" -> args.singleOrNull()
                "min" -> if (args.isEmpty()) null else Value(args.minOf { it.number }, isLength)
                "max" -> if (args.isEmpty()) null else Value(args.maxOf { it.number }, isLength)
                "clamp" -> if (args.size == 3) {
                    Value(
                        maxOf(args[0].number, minOf(args[1].number, args[2].number)),
                        isLength,
                    )
                } else {
                    null
                }

                else -> null
            }
        }

        private fun parseNumber(): Value? {
            val start = pos
            if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
            var digits = false
            while (pos < text.length && text[pos].isDigit()) {
                pos++
                digits = true
            }
            if (pos < text.length && text[pos] == '.') {
                pos++
                while (pos < text.length && text[pos].isDigit()) {
                    pos++
                    digits = true
                }
            }
            if (!digits) return null
            val number = text.substring(start, pos).toFloatOrNull() ?: return null
            val unitStart = pos
            while (pos < text.length && text[pos].isLetter()) pos++
            var unit = text.substring(unitStart, pos).lowercase()
            if (unit.isEmpty() && pos < text.length && text[pos] == '%') {
                unit = "%"
                pos++
            }
            return unitValue(number, unit)
        }

        private fun unitValue(number: Float, unit: String): Value? = when (unit) {
            "" -> Value(number, false)
            "em" -> Value(number * ctx.emUnit, true)
            "rem" -> Value(number * ctx.remUnit, true)
            "px" -> Value(number * ctx.pxUnit, true)
            "pt" -> Value(number * ctx.pxUnit * (4f / 3f), true)
            "pc" -> Value(number * ctx.pxUnit * 16f, true)
            "in" -> Value(number * ctx.pxUnit * 96f, true)
            "cm" -> Value(number * ctx.pxUnit * 37.795f, true)
            "mm" -> Value(number * ctx.pxUnit * 3.7795f, true)
            "q" -> Value(number * ctx.pxUnit * 0.94488f, true)
            "ch", "ex" -> Value(number * ctx.emUnit * 0.5f, true)
            // Viewport units against the 30-em content-width convention.
            "vw", "vmin" -> Value(number * ctx.remUnit * 0.30f, true)
            "vh", "vmax" -> Value(number * ctx.remUnit * 0.50f, true)
            "%" -> ctx.percentUnit?.let { Value(number * it, true) }
            else -> null
        }
    }
}
