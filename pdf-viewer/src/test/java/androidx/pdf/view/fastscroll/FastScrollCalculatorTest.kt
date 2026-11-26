package androidx.pdf.view.fastscroll

import android.content.Context
import android.content.res.Resources
import androidx.pdf.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class FastScrollCalculatorTest {
    private val resources = mock<Resources> {
        on { getDimension(R.dimen.scroller_top_margin) } doReturn 56f
        on { getDimension(R.dimen.scroller_bottom_margin) } doReturn 32f
    }
    private val calculator = FastScrollCalculator(mock<Context> {
        on { resources } doReturn this@FastScrollCalculatorTest.resources
    })

    @Test
    fun panelInsetsCoverSystemBarMargins() {
        val track = calculator.thumbTrack(viewHeight = 900, thumbHeightPx = 56, topInset = 116, bottomInset = 104)
        assertEquals(116..740, track)
        assertEquals(track.first, calculator.computeThumbPosition(0.0, track))
        assertEquals(1.0, calculator.scrollFraction(track.last, track), 0.0)
    }

    @Test
    fun systemBarMarginsStillApplyWhenPanelsAreHiddenOrSliding() {
        val track = calculator.thumbTrack(viewHeight = 900, thumbHeightPx = 56, topInset = 12, bottomInset = 0)
        assertEquals(56..812, track)
    }
}
