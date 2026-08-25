package com.example.frogreader.parser

import com.example.frogreader.data.parser.LanguageTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageTagTest {

    @Test
    fun `normalizes valid tags`() {
        assertEquals("ru", LanguageTag.normalize("ru"))
        assertEquals("ru-ru", LanguageTag.normalize("ru-RU"))
        assertEquals("uk", LanguageTag.normalize(" UK "))
        assertEquals("en-us", LanguageTag.normalize("en_US"))
    }

    @Test
    fun `maps the common ua mistake to uk`() {
        assertEquals("uk", LanguageTag.normalize("ua"))
        assertEquals("uk", LanguageTag.normalize("UA"))
    }

    @Test
    fun `rejects garbage`() {
        assertNull(LanguageTag.normalize(null))
        assertNull(LanguageTag.normalize(""))
        assertNull(LanguageTag.normalize("Русский"))
        assertNull(LanguageTag.normalize("russian language"))
        assertNull(LanguageTag.normalize("r"))
    }

    @Test
    fun `detects russian from cyrillic text`() {
        val text = "Каждый день он выходил из дома и долго смотрел на серое небо " +
            "над крышами, вспоминая всё, что случилось прошлой осенью."
        assertEquals("ru", LanguageTag.detect(text))
    }

    @Test
    fun `detects ukrainian by its specific letters`() {
        val text = "Щодня він виходив із дому і довго дивився на сіре небо " +
            "над дахами, згадуючи все, що сталося минулої осені."
        assertEquals("uk", LanguageTag.detect(text))
    }

    @Test
    fun `detects arabic when metadata is absent`() {
        val text = "كان القارئ يفتح الكتاب كل مساء ويتابع الحكاية بهدوء، " +
            "ثم يدوّن ملاحظاته عن الشخصيات والأماكن والأحداث المهمة في الفصل."
        assertEquals("ar", LanguageTag.detect(text))
        assertEquals(true, LanguageTag.isRtl("ar-EG"))
        assertEquals(true, LanguageTag.isRtl("fa_IR"))
        listOf("az-Arab", "syr-Syrc", "dv-Thaa", "nqo-Nkoo", "ff-Adlm", "rhg-Rohg")
            .forEach { assertEquals(it, true, LanguageTag.isRtl(it)) }
    }

    @Test
    fun `detects hebrew with niqqud when metadata is absent`() {
        val text = "הַקּוֹרֵא פָּתַח אֶת הַסֵּפֶר בְּכָל עֶרֶב וְהִמְשִׁיךְ " +
            "בַּסִּפּוּר בְּשֶׁקֶט, וְאַחַר כָּךְ כָּתַב הֶעָרוֹת עַל הַדְּמוּיוֹת וְהַמְּקוֹמוֹת."
        assertEquals("he", LanguageTag.detect(text))
        assertEquals(true, LanguageTag.isRtl("he"))
        assertEquals(false, LanguageTag.isRtl("en"))
    }

    @Test
    fun `does not guess latin-script languages`() {
        val text = "Every day he walked out of the house and stared at the grey " +
            "sky above the rooftops for a long while."
        assertNull(LanguageTag.detect(text))
    }

    @Test
    fun `needs enough text to decide`() {
        assertNull(LanguageTag.detect("Привет."))
        assertNull(LanguageTag.detect(""))
    }
}
