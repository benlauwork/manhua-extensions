package eu.kanade.tachiyomi.extension.zh.hipmh

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class ReaderTest {
    @Test
    fun decodesAndPreservesPageOrder() {
        val payload = "qM993JrzrB0Jr4mB_w09Vx3knMv5rJiIrQjwqr29pL0kM3JpQTBmzrB0z_w0Z7"
        assertEquals("""["/one.webp","/two.webp","/three.webp"]""", Reader.decode(payload))
    }

    @Test(expected = IOException::class)
    fun rejectsUnexpectedMarkers() {
        Reader.decode("qM993JrzrB0Jr4mB_w09Xx3knMv5rJiIrQjwqr29pL0kM3JpQTBmzrB0z_w0Z7")
    }

    @Test
    fun removesOnlyTheInsertedImage() {
        val images = listOf("/one.webp", "/extra.webp", "/two.webp", "/three.webp")
        assertEquals(listOf("/one.webp", "/two.webp", "/three.webp"), Reader.withoutExtraImage(images, 1, 194236))
        assertEquals(images, Reader.withoutExtraImage(images, null, 194236))
    }
}
