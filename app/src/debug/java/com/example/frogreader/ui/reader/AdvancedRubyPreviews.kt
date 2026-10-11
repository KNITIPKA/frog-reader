package com.example.frogreader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "Tristram Shandy · native Latin ruby", widthDp = 393, heightDp = 820)
@Composable
private fun AdvancedRuby() = AdvancedEpubPreview(RUBY_HTML, "rt { font-size:1em; ruby-align:center; }")

@Preview(name = "Tristram Shandy · native ruby narrow large text", widthDp = 320, heightDp = 1600, fontScale = 1.3f)
@Composable
private fun AdvancedRubyLarge() = AdvancedEpubPreview(RUBY_HTML, "rt { font-size:1em; ruby-align:center; }")

// Actual public-domain source from chapter III:XI, not a synthetic screenshot.
private const val RUBY_HTML = """
<section xml:lang="la">
<p>Excommunicamus, et anathematizamus <ruby>hunc<rt><i>vel</i> os</rt></ruby> fure<ruby>m<rt>s</rt></ruby>, <ruby>vel hunc<rt><i>vel</i> os</rt></ruby> malefactor<ruby>em<rt>s</rt></ruby>, <abbr>N.</abbr> <abbr>N.</abbr> et a liminibus sanctæ Dei ecclesiæ sequestramus, et æternis suppliciis excruci<ruby>andus<rt><i>vel</i> i</rt></ruby>, manci<ruby>petur<rt>n</rt></ruby>, cum Dathan et Abiram, et cum his qui dixerunt Domino Deo, Recede à nobis, scientiam viarum tuarum nolumus: et sicut aquâ ignis extinguitur, sic extinguatur <ruby>lucerna ejus<rt><i>vel</i> eorum</rt></ruby> in secula seculorum nisi resipuer<ruby>it<rt>n</rt></ruby>, et ad satisfactionem vener<ruby>it<rt>n</rt></ruby>. Amen.</p>
<p>Maledicat ill<ruby>um<rt>os</rt></ruby> Deus Pater qui hominem creavit. Maledicat ill<ruby>um<rt>os</rt></ruby> Dei Filius qui pro homine passus est. Maledicat ill<ruby>um<rt>os</rt></ruby> Spiritus Sanctus qui in baptismo effusus est. Maledicat ill<ruby>um<rt>os</rt></ruby> sancta crux, quam Christus pro nostrâ salute hostem triumphans ascendit.</p>
</section>
"""
