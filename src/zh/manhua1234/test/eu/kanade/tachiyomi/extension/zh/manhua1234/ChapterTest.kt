package eu.kanade.tachiyomi.extension.zh.manhua1234

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterTest {
    private fun fixture(name: String) = Jsoup.parse(
        javaClass.getResourceAsStream("/$name")!!.bufferedReader().use { it.readText() },
        "https://m.wmh1234.com",
    )

    @Test
    fun includesNewMobileChaptersWhileRetainingDesktopChapterUrls() {
        val desktop = Chapter.desktop(fixture("desktop-31457.html"))
        val mobile = Chapter.mobile(fixture("mobile-31457.html"), desktop)

        assertEquals(187, mobile.size)
        assertEquals("[第185话] 最终决战(2)", mobile.first().name)
        assertEquals("/comic/31457/3966774.html", mobile.first().url)
        assertEquals("/comic/31457/3941804.html", mobile.first { it.name.startsWith("[第183话]") }.url)
        assertEquals("[第1话] 你们被困在了我的地盘里", mobile.last().name)
        assertEquals(mobile.size, mobile.map { it.url }.distinct().size)
        for (chapter in desktop.filter { it.name != "最新话" }) {
            assertTrue("Missing old chapter ${chapter.name}", mobile.any { it.url == chapter.url })
        }
    }

    @Test
    fun keepsSavedMobileUrlAfterDesktopCatchesUp() {
        val desktop = Chapter.desktop(fixture("desktop-31457.html"))
        val saved = Chapter("[第181话]", "/comic/31457/3901559.html")
        val mobile = Chapter.mobile(fixture("mobile-31457.html"), desktop + saved)
        assertEquals(saved.url, mobile.first { it.name.startsWith("[第181话]") }.url)
    }

    @Test
    fun emptyDirectoryDoesNotReuseStaleDesktopList() {
        val desktop = Chapter.desktop(fixture("desktop-31457.html"))
        assertTrue(Chapter.mobile(Jsoup.parse(""), desktop).isEmpty())
    }
}
