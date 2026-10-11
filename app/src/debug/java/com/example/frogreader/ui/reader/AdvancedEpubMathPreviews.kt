package com.example.frogreader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.frogreader.data.AppTheme

/** Actual presentation trees from A Tangled Tale's appendix, with adjacent source prose. */
private const val TANGLED_MATH_HTML = """
<p>“<math alttext="y = 6 − ((x − 3y) ÷ 6)"><mi>y</mi><mo>=</mo><mn>6</mn><mo>−</mo><mfrac><mrow><mo fence="true">(</mo><mi>x</mi><mo>−</mo><mn>3</mn><mi>y</mi><mo fence="true">)</mo></mrow><mrow><mn>6</mn></mrow></mfrac></math>;</p>
<p>Then <math alttext="AB = √(12² + 5²) = √169 = 13"><mi>AB</mi><mo>=</mo><msqrt><mo fence="true" form="prefix">(</mo><msup><mn>12</mn><mn>2</mn></msup><mo>+</mo><msup><mn>5</mn><mn>2</mn></msup><mo fence="true" form="postfix">)</mo></msqrt><mo>=</mo><msqrt><mn>169</mn></msqrt><mo>=</mo><mn>13</mn></math>;</p>
<p>She then subtracts the second equation from the first, and deduces <math alttext="3y + 7 × (3-y) ÷ 3 − 4y + 10 × (3 − y) ÷ 3 = 3"><mn>3</mn><mn>y</mn><mo>+</mo><mn>7</mn><mo>×</mo><mfrac><mrow><mn>3</mn><mo>−</mo><mi>y</mi></mrow><mrow><mn>3</mn></mrow></mfrac><mo>−</mo><mn>4</mn><mi>y</mi><mo>+</mo><mn>10</mn><mo>×</mo><mfrac><mrow><mn>3</mn><mo>−</mo><mi>y</mi></mrow><mrow><mn>3</mn></mrow></mfrac><mo>=</mo><mn>3</mn></math>. By making two mistakes in this line, she brings out <math alttext="y = ³⁄₂"><mi>y</mi><mo>=</mo><mfrac><mn>3</mn><mn>2</mn></mfrac></math>.</p>
<p>This distance would be traversed by an omnibus in <math alttext="15 / 4"><mfrac><mn>15</mn><mn>4</mn></mfrac></math> minutes, and therefore by the traveller in <math alttext="5 × (15 / 4)"><mn>5</mn><mo>×</mo><mfrac><mn>15</mn><mn>4</mn></mfrac></math>.</p>
<p>Now we have <math alttext="x(x + ½) = 3,630"><mi>x</mi><mo fence="true">(</mo><mi>x</mi><mo>+</mo><mfrac><mn>1</mn><mn>2</mn></mfrac><mo fence="true">)</mo><mo>=</mo><mn>3,630</mn></math>. Solving this Quadratic, we find the result.</p>
"""

@Preview(name = "Tangled Tale · native fractions and radicals", widthDp = 393, heightDp = 780)
@Composable
private fun AdvancedNativeMath() = AdvancedEpubPreview(TANGLED_MATH_HTML, "")

@Preview(name = "Tangled Tale · native math OLED", widthDp = 393, heightDp = 780)
@Composable
private fun AdvancedNativeMathOled() = AdvancedEpubPreview(TANGLED_MATH_HTML, "", AppTheme.OLED)

@Preview(name = "Tangled Tale · native math narrow large", widthDp = 320, heightDp = 1000, fontScale = 1.3f)
@Composable
private fun AdvancedNativeMathLarge() = AdvancedEpubPreview(TANGLED_MATH_HTML, "")

@Preview(name = "Tangled Tale · native math left alignment diagnostic", widthDp = 393, heightDp = 780)
@Composable
private fun AdvancedNativeMathLeft() = AdvancedEpubPreview(TANGLED_MATH_HTML, "p {text-align:left}")
