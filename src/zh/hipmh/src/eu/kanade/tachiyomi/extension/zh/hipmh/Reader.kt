package eu.kanade.tachiyomi.extension.zh.hipmh

import okio.ByteString.Companion.decodeBase64
import java.io.IOException

internal object Reader {
    private const val PREFIX = "qM9"
    private const val SUFFIX = "Z7"
    private const val FIRST_MARKER = "Vx"
    private const val SECOND_MARKER = "pL0"
    private const val BLOCK_SIZE = 7
    private const val SITE_ALPHABET = "_-9876543210abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun decode(payload: String): String {
        if (!payload.startsWith(PREFIX) || !payload.endsWith(SUFFIX)) invalidPayload()

        val body = payload.substring(PREFIX.length, payload.length - SUFFIX.length)
        val payloadLength = body.length - FIRST_MARKER.length - SECOND_MARKER.length
        if (payloadLength <= 0) invalidPayload()

        val firstLength = payloadLength / 3
        val secondLength = (payloadLength - firstLength) / 2
        val thirdLength = payloadLength - firstLength - secondLength
        val thirdStart = secondLength + FIRST_MARKER.length
        val firstStart = thirdStart + thirdLength + SECOND_MARKER.length
        if (!body.startsWith(FIRST_MARKER, secondLength) ||
            !body.startsWith(SECOND_MARKER, thirdStart + thirdLength)
        ) {
            invalidPayload()
        }

        // The site sends the encoded chunks in second, third, first order.
        val encoded = body.substring(firstStart) + body.substring(0, secondLength) + body.substring(thirdStart, thirdStart + thirdLength)
        val base64 = buildString(encoded.length) {
            encoded.chunked(BLOCK_SIZE).forEachIndexed { index, block ->
                val orderedBlock = if (index % 2 == 0) block else block.reversed()
                orderedBlock.forEach { character ->
                    val position = SITE_ALPHABET.indexOf(character)
                    if (position < 0) invalidPayload()
                    append(BASE64_ALPHABET[position])
                }
            }
        }
        return base64.decodeBase64()?.utf8() ?: invalidPayload()
    }

    fun withoutExtraImage(images: List<String>, orderId: Long?, sid: Long?): List<String> {
        if (images.isEmpty() || orderId == null || sid == null ||
            orderId !in 0L..0xFFFFFFFFL || sid !in 0L..0xFFFFFFFFL
        ) {
            return images
        }

        // The reader mixes one extra URL into the array and stores its masked index.
        val count = images.size.toULong()
        val mask = ((sid.toULong() * 2_654_435_761uL) xor (count * 2_246_822_507uL)) % count
        val index = orderId xor mask.toLong()
        if (index !in 0L until images.size.toLong()) return images
        return images.filterIndexed { position, _ -> position.toLong() != index }
    }

    private fun invalidPayload(): Nothing = throw IOException("嬉皮漫畫圖片清單格式有變")
}
