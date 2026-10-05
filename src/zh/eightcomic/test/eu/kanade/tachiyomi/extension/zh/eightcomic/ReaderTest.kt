package eu.kanade.tachiyomi.extension.zh.eightcomic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTest {
    private fun fixture(name: String): String = javaClass.getResourceAsStream("/$name")!!.bufferedReader().use { it.readText() }

    @Test
    fun matchesAllImagesRenderedBySiteForChapter25() {
        val expected = fixture("24975-ch25.txt").lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(41, expected.size)
        assertEquals(expected, Reader.imageUrls(fixture("24975.js"), "24975", "25"))
    }

    @Test
    fun keepsSplitChaptersSeparate() {
        val script = fixture("24975.js")
        val first = Reader.imageUrls(script, "24975", "8a")
        val second = Reader.imageUrls(script, "24975", "8b")
        assertEquals(27, first.size)
        assertEquals(14, second.size)
        assertEquals(fixture("24975-ch8b.txt").trim().lines(), second)
        assertTrue(first.all { "/8a/" in it })
        assertTrue(second.all { "/8b/" in it })
        assertEquals(first, Reader.imageUrls(script, "24975", "8"))
    }

    @Test
    fun doesNotFallBackToAnotherChapterWhenMissing() {
        assertTrue(Reader.imageUrls(fixture("24975.js"), "24975", "31").isEmpty())
        assertTrue(Reader.imageUrls(fixture("24975.js"), "24975", "8c").isEmpty())
        assertTrue(Reader.imageUrls("", "24975", "25").isEmpty())
    }

    @Test
    fun decodesLargeChapterNumberEscape() {
        assertEquals(8000, Reader.decodePair("Za"))
        assertEquals(8051, Reader.decodePair("ZZ"))
        assertEquals(52, Reader.decodePair("ba"))
    }
}
