package eu.kanade.tachiyomi.extension.zh.manhua1234

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class ReaderTest {
    private fun fixture(name: String): Document = Jsoup.parse(
        javaClass.getResourceAsStream("/$name")!!.bufferedReader().use { it.readText() },
        "https://m.wmh1234.com/comic/31457/3941804.html",
    )

    @Test
    fun resolvesReaderWhenResponseStillHasOriginalMobileUrl() {
        assertEquals(
            "https://reader.hqread.cc/r/MzE0NTctMzk0MTgwNC03ZDYyMjUxMTEz",
            Reader.redirectUrl(fixture("redirect-183.html")),
        )
    }

    @Test
    fun extractsEveryImageInReadingOrder() {
        val images = Reader.imageUrls(fixture("reader-183.html"))
        assertEquals(91, images.size)
        assertTrue(images.first().contains("/80309d9cbe97230217db662368063e84.webp?"))
        assertTrue(images[45].contains("/937ca12ff167b474ec3dfe71a58a0aa9.webp?"))
        assertTrue(images.last().contains("/e28c306855e6c55840b7b78bec7018db.webp?"))
    }

    @Test
    fun retriesTimedOutReaderRequestOnce() = runBlocking {
        var requests = 0
        val expected = fixture("reader-183.html")
        val document = Reader.retryOnTimeout {
            requests++
            if (requests == 1) throw SocketTimeoutException("Read timed out")
            expected
        }
        assertEquals(2, requests)
        assertEquals(91, Reader.imageUrls(document).size)
    }

    @Test
    fun stopsAfterSecondTimeout() {
        var requests = 0
        assertThrows(SocketTimeoutException::class.java) {
            runBlocking {
                Reader.retryOnTimeout {
                    requests++
                    throw SocketTimeoutException("Read timed out")
                }
            }
        }
        assertEquals(2, requests)
    }

    @Test
    fun doesNotRetryOtherFailures() {
        var requests = 0
        assertThrows(IOException::class.java) {
            runBlocking {
                Reader.retryOnTimeout {
                    requests++
                    throw IOException("HTTP error")
                }
            }
        }
        assertEquals(1, requests)
    }
}
