package com.example.frogreader.parser

import com.example.frogreader.data.parser.CssColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CssColorTest {

    @Test
    fun `all CSS named colors and transparent are recognized`() {
        val names = """
            aliceblue antiquewhite aqua aquamarine azure beige bisque black blanchedalmond
            blue blueviolet brown burlywood cadetblue chartreuse chocolate coral cornflowerblue
            cornsilk crimson cyan darkblue darkcyan darkgoldenrod darkgray darkgreen darkgrey
            darkkhaki darkmagenta darkolivegreen darkorange darkorchid darkred darksalmon
            darkseagreen darkslateblue darkslategray darkslategrey darkturquoise darkviolet
            deeppink deepskyblue dimgray dimgrey dodgerblue firebrick floralwhite forestgreen
            fuchsia gainsboro ghostwhite gold goldenrod gray green greenyellow grey honeydew
            hotpink indianred indigo ivory khaki lavender lavenderblush lawngreen lemonchiffon
            lightblue lightcoral lightcyan lightgoldenrodyellow lightgray lightgreen lightgrey
            lightpink lightsalmon lightseagreen lightskyblue lightslategray lightslategrey
            lightsteelblue lightyellow lime limegreen linen magenta maroon mediumaquamarine
            mediumblue mediumorchid mediumpurple mediumseagreen mediumslateblue mediumspringgreen
            mediumturquoise mediumvioletred midnightblue mintcream mistyrose moccasin navajowhite
            navy oldlace olive olivedrab orange orangered orchid palegoldenrod palegreen
            paleturquoise palevioletred papayawhip peachpuff peru pink plum powderblue purple
            rebeccapurple red rosybrown royalblue saddlebrown salmon sandybrown seagreen seashell
            sienna silver skyblue slateblue slategray slategrey snow springgreen steelblue tan teal
            thistle tomato turquoise violet wheat white whitesmoke yellow yellowgreen transparent
        """.trimIndent().split(Regex("\\s+"))

        assertEquals(149, names.size)
        assertTrue(names.all { CssColor.parse(it) != null })
        assertEquals(0xfff0f8ff.toInt(), CssColor.parse("  AliceBlue  "))
        assertEquals(0xff663399.toInt(), CssColor.parse("REBECCAPURPLE"))
        assertEquals(CssColor.parse("darkslategray"), CssColor.parse("darkslategrey"))
        assertEquals(CssColor.parse("aqua"), CssColor.parse("cyan"))
        assertEquals(CssColor.parse("fuchsia"), CssColor.parse("magenta"))
        assertEquals(0x00000000, CssColor.parse("transparent"))
    }

    @Test
    fun `hex syntax supports short long and alpha forms`() {
        assertEquals(0xff00ff88.toInt(), CssColor.parse("#0f8"))
        assertEquals(0xcc00ff88.toInt(), CssColor.parse("#0F8c"))
        assertEquals(0xff123456.toInt(), CssColor.parse("#123456"))
        assertEquals(0x78123456, CssColor.parse("#12345678"))

        listOf("#", "#12", "#12345", "#1234567", "#123456789", "#12xz56").forEach {
            assertNull(it, CssColor.parse(it))
        }
    }

    @Test
    fun `legacy rgb syntax supports numbers percentages aliases and alpha`() {
        assertEquals(0xffff0080.toInt(), CssColor.parse("rgb(255, 0, 127.5)"))
        assertEquals(0x80ff0000.toInt(), CssColor.parse("rgba(300, -10, 0, .5)"))
        assertEquals(0xffff8000.toInt(), CssColor.parse("rgb(100%, 50%, 0%)"))
        assertEquals(0x4000ff00, CssColor.parse("rgba(0%, 100%, 0%, 25%)"))
        // CSS Color 4 makes rgb() and rgba() exact aliases.
        assertEquals(0x80102030.toInt(), CssColor.parse("rgb(16, 32, 48, 50%)"))
        assertEquals(0xff102030.toInt(), CssColor.parse("rgba(16, 32, 48)"))
    }

    @Test
    fun `modern rgb syntax supports whitespace slash and percentages`() {
        assertEquals(0x80ff0080.toInt(), CssColor.parse("rgb(255 0 128 / 50%)"))
        assertEquals(0x40ff0080, CssColor.parse("rgba(100% 0% 50% / .25)"))
        assertEquals(0xffff0000.toInt(), CssColor.parse("rgb(100% 0 0%)"))
        assertEquals(0x00ff0000, CssColor.parse("RGB(300 -5 0 / -1)"))
        assertEquals(0xffff0000.toInt(), CssColor.parse("rgb(255 0 0 / 200%)"))
        assertEquals(0xff640000.toInt(), CssColor.parse("rgb(1e2 0 0)"))
    }

    @Test
    fun `hsl supports legacy modern angles alpha and hue wrapping`() {
        assertEquals(0xffff0000.toInt(), CssColor.parse("hsl(0, 100%, 50%)"))
        assertEquals(0xff00ff00.toInt(), CssColor.parse("hsl(120deg 100% 50%)"))
        assertEquals(0xff00ff00.toInt(), CssColor.parse("hsl(120 100 50)"))
        assertEquals(0xff0000ff.toInt(), CssColor.parse("hsl(240deg 100 50%)"))
        assertEquals(0xff00ffff.toInt(), CssColor.parse("hsl(200grad 100% 50%)"))
        assertEquals(0xff00ffff.toInt(), CssColor.parse("hsl(3.141592653589793rad 100% 50%)"))
        assertEquals(0xff00ffff.toInt(), CssColor.parse("hsl(.5turn 100% 50%)"))
        assertEquals(0xff0000ff.toInt(), CssColor.parse("hsl(-120deg 100% 50%)"))
        assertEquals(0x40ffff00, CssColor.parse("hsla(60 100% 50% / 25%)"))
        assertEquals(0x80808080.toInt(), CssColor.parse("hsl(0, 0%, 50%, .5)"))
        assertEquals(0xff000000.toInt(), CssColor.parse("hsl(720 200% -10%)"))
    }

    @Test
    fun `contextual keywords and malformed colors do not resolve`() {
        listOf(
            "",
            "currentColor",
            "inherit",
            "unset",
            "initial",
            "none",
            "not-a-color",
            "rgb()",
            "rgb(1 2)",
            "rgb(1, 2, 3 / .5)",
            "rgb(100%, 0, 0%)",
            "rgb(1 2 3 / .5 .6)",
            "rgb(NaN 0 0)",
            "rgba(1, 2, 3, Infinity)",
            "hsl(0, 100, 50%)",
            "hsl(0, 100%, 50)",
            "hsl(1px 100% 50%)",
            "hsl(0, 100%, 50% / .5)",
            "rgb(var(--red) 0 0)",
            "rgb (255 0 0)",
            "rgb(255 0 0))",
        ).forEach { value ->
            assertNull(value, CssColor.parse(value))
        }
    }
}
