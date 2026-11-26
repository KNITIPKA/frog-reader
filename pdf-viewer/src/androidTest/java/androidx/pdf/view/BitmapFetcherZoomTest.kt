package androidx.pdf.view

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BitmapFetcherZoomTest : BitmapFetcherZoomContract() {
    override fun onMainTest(block: suspend () -> Unit) = runBlocking {
        withContext(Dispatchers.Main) { block() }
    }
}
