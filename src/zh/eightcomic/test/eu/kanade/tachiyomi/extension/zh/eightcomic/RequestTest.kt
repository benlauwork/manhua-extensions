package eu.kanade.tachiyomi.extension.zh.eightcomic

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

class RequestTest {
    private lateinit var source: LegacySourceHarness
    private lateinit var manga: SManga

    @Before
    fun setUp() {
        source = mock(LegacySourceHarness::class.java, CALLS_REAL_METHODS)
        doReturn("https://www.8comic.com").`when`(source).baseUrl
        doReturn(Headers.Builder().set("Referer", "https://www.8comic.com/").build()).`when`(source).headers
        manga = mock(SManga::class.java)
        doReturn("/html/24975.html").`when`(manga).url
    }

    @Test
    fun detailsRequestResolvesWithoutRecursingThroughLegacyUrlHelper() {
        assertEquals("https://www.8comic.com/html/24975.html", source.mangaDetailsRequest(manga).url.toString())
    }

    @Test
    fun chapterDirectoryRequestResolvesWithoutRecursion() {
        val request = source.directoryRequest(manga)
        assertEquals("https://www.8comic.com/html/24975.html", request.url.toString())
        assertEquals("https://www.8comic.com/", request.header("Referer"))
    }

    @Test
    fun legacyWebViewUrlResolvesWithoutRecursion() {
        assertEquals("https://www.8comic.com/html/24975.html", source.getMangaUrl(manga))
    }

    @Test
    fun readerRequestKeepsReaderHostAndMainSiteReferer() {
        val chapter = mock(SChapter::class.java)
        doReturn("/online/new-24975.html?ch=25").`when`(chapter).url
        val request = source.readerRequest(chapter)
        assertEquals("https://articles.onemoreplace.tw/online/new-24975.html?ch=25", request.url.toString())
        assertEquals("https://www.8comic.com/", request.header("Referer"))
    }

    abstract class LegacySourceHarness : EightComic() {
        // HttpSource 1.4 resolves the WebView URL through mangaDetailsRequest.
        override fun getMangaUrl(manga: SManga): String = mangaDetailsRequest(manga).url.toString()

        fun directoryRequest(manga: SManga): Request = chapterListRequest(manga)

        fun readerRequest(chapter: SChapter): Request = pageListRequest(chapter)
    }
}
