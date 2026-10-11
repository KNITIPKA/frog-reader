package com.example.frogreader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

/** Actual text and active local.css rules from the supplied Shandy edition. */
private const val SHANDY_INLINE_WIDTH_HTML = """
<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
<section id="chapter-8-13">
<p><span>A</span>gitating</p>
<p><span>B</span>ewitching</p>
<p><span>C</span>onfounded</p>
<p><span>D</span>evilish affairs of life⁠—the most</p>
<p><span>E</span>xtravagant</p>
<p><span>F</span>utilitous</p>
<p><span>G</span>alligaskinish</p>
<p><span>H</span>andy-dandyish</p>
<p><span>I</span>racundulous (there is no K to it) and</p>
</section>
<section id="chapter-8-20">
<p>⸺“<em>It was not love</em>”⁠⸺⁠for during the three weeks she was almost constantly with me, fomenting my knee with her hand, night and day⁠—I can honestly say, an’ please your honour⁠—that <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> <span>*</span> once.</p>
</section>
</body></html>
"""

private const val SHANDY_INLINE_WIDTH_CSS = """
#chapter-8-13 p > span{
    display: inline-block;
    text-indent: 0;
    width: 1.2em;
}
#chapter-8-20 p > span{
    display: inline-block;
    text-align: center;
    text-indent: 0;
    width: 2em;
}
"""

private const val SHANDY_GREEK_EPIGRAPH_HTML = """
<html xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en-GB"><body epub:type="frontmatter">
<section id="epigraph" epub:type="epigraph">
    <blockquote xml:lang="el">
        <p>Ταράσσει τοὺς Ἀνθρώπους οὐ τὰ Πράγματα,<br/>Ἀλλὰ τὰ περὶ τῶν Πραγμάτων Δόγματα.</p>
    </blockquote>
</section>
</body></html>
"""

private const val SHANDY_GREEK_EPIGRAPH_CSS = """
@namespace epub "http://www.idpf.org/2007/ops";
[epub|type~="epigraph"]{
    font-style: italic;
    hyphens: none;
    -epub-hyphens: none;
}
section[epub|type~="epigraph"]{
    text-align: center;
}
section[epub|type~="epigraph"] > *{
    display: inline-block;
    margin: auto;
    margin-top: 3em;
    max-width: 80%;
    text-align: initial;
}
@supports(display: flex){
    section[epub|type~="epigraph"]{
        align-items: center;
        box-sizing: border-box;
        display: flex;
        flex-direction: column;
        justify-content: center;
        min-height: calc(98vh - 3em);
        padding-top: 3em;
    }
    section[epub|type~="epigraph"] > *{
        margin: 0;
    }
}
"""

@Preview(name = "Tristram Shandy · authored inline widths", widthDp = 393, heightDp = 1400)
@Composable
private fun AdvancedInlineWidths() = AdvancedEpubPreview(SHANDY_INLINE_WIDTH_HTML, SHANDY_INLINE_WIDTH_CSS)

@Preview(name = "Tristram Shandy · inline widths narrow large", widthDp = 320, heightDp = 2000, fontScale = 1.3f)
@Composable
private fun AdvancedInlineWidthsLarge() = AdvancedEpubPreview(SHANDY_INLINE_WIDTH_HTML, SHANDY_INLINE_WIDTH_CSS)

@Preview(name = "Tristram Shandy · authored Greek epigraph", widthDp = 393, heightDp = 780)
@Composable
private fun AdvancedGreekEpigraph() = AdvancedEpubPreview(SHANDY_GREEK_EPIGRAPH_HTML, SHANDY_GREEK_EPIGRAPH_CSS)

@Preview(name = "Tristram Shandy · Greek epigraph narrow large", widthDp = 320, heightDp = 780, fontScale = 1.3f)
@Composable
private fun AdvancedGreekEpigraphLarge() = AdvancedEpubPreview(SHANDY_GREEK_EPIGRAPH_HTML, SHANDY_GREEK_EPIGRAPH_CSS)
