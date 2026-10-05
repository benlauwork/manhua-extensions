package eu.kanade.tachiyomi.extension.zh.eightcomic

internal object Reader {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val RECORD_LENGTH = 47
    private val payloadRegex = Regex("""var\s+\w+\s*=\s*['"]([a-zA-Z0-9]{94,})['"]""")
    private val recordRegex = Regex("[a-zA-Z]{6}[a-zA-Z0-9]{40}[0a-z]")

    fun imageUrls(script: String, mangaId: String, chapter: String): List<String> {
        val payload = payloadRegex.find(script)?.groupValues?.get(1) ?: return emptyList()
        val part = chapter.lastOrNull()?.takeIf { it in 'a'..'z' }
        val number = chapter.removeSuffix(part?.toString().orEmpty()).toInt()
        val record = payload.chunked(RECORD_LENGTH)
            .takeWhile { recordRegex.matches(it) }
            .firstOrNull {
                decodePair(it.substring(0, 2)) == number && (part == null || it.last() == part)
            } ?: return emptyList()

        val count = decodePair(record.substring(2, 4))
        val server = decodePair(record.substring(4, 6)).toString()
        val code = record.substring(6, 46)
        val chapterId = number.toString() + record.substring(46).takeUnless { it == "0" }.orEmpty()

        // Four hex triplets before the random 47-character suffix encode the image host and extension.
        fun tail(offset: Int): String {
            val start = payload.length - RECORD_LENGTH - offset * 6
            return payload.substring(start, start + 6).chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        }
        val host = "${tail(4)}${server[0]}.8${tail(3)}${tail(2)}${tail(3)}"
        val prefix = "https://$host/${server.substring(1)}/$mangaId/$chapterId/"
        val extension = tail(1)
        return (1..count).map { page ->
            val offset = (page - 1) / 10 % 10 + (page - 1) % 10 * 3
            "$prefix${page.toString().padStart(3, '0')}_${code.substring(offset, offset + 3)}.$extension"
        }
    }

    internal fun decodePair(pair: String): Int = if (pair[0] == 'Z') {
        8000 + ALPHABET.indexOf(pair[1])
    } else {
        ALPHABET.indexOf(pair[0]) * 52 + ALPHABET.indexOf(pair[1])
    }
}
