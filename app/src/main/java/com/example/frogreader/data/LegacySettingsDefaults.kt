package com.example.frogreader.data

/** Missing fields on an existing installation or in an older backup retain their previous defaults. */
internal object LegacySettingsDefaults {
    val reader = ReaderSettings(
        justify = true,
        hyphenation = true,
        bookStyles = false,
        pageMargins = PageMargins.NORMAL,
        readingMode = ReadingMode.SCROLL,
        dropCaps = false,
        pageTurnAnimation = PageTurnAnimation.SLIDE,
    )

    val app = AppSettings(
        lightThemeDefault = LightThemeDefault.LIGHT,
        autoInvertImages = true,
    )
}
