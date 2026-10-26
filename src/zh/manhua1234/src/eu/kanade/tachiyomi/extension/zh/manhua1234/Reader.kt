package eu.kanade.tachiyomi.extension.zh.manhua1234

import org.jsoup.nodes.Document
import java.net.SocketTimeoutException

internal object Reader {
    // Read the supplied target instead of relying on the host app to expose the final request URL.
    fun redirectUrl(document: Document): String? = document.selectFirst("noscript a[href]")?.absUrl("href")
        ?.takeIf { it.isNotBlank() }

    fun imageUrls(document: Document): List<String> = document.select("img.reader-image[data-src]")
        .mapNotNull { it.absUrl("data-src").takeIf(String::isNotBlank) }

    suspend fun retryOnTimeout(fetch: suspend () -> Document): Document = try {
        fetch()
    } catch (_: SocketTimeoutException) {
        fetch()
    }
}
