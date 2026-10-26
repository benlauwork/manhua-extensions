package eu.kanade.tachiyomi.extension.zh.manhua1234

import eu.kanade.tachiyomi.source.model.SChapter
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.decodeBase64
import org.jsoup.nodes.Document

internal class Chapter(val name: String, val url: String) {
    fun toSChapter(): SChapter = SChapter.create().apply {
        name = this@Chapter.name
        url = this@Chapter.url
    }

    companion object {
        fun desktop(document: Document): List<Chapter> = document.select(".mint-chapters a[href^=/go/]")
            .mapNotNull { anchor ->
                val name = anchor.attr("title").ifBlank { anchor.text() }
                    .takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Chapter(name, anchor.absUrl("href").toHttpUrl().encodedPath)
            }
            .distinctBy { it.url }

        fun mobile(document: Document, knownChapters: List<Chapter>): List<Chapter> {
            // Preserve existing URLs so refreshing does not replace read/downloaded chapters.
            val knownUrls = knownChapters.associate { chapter ->
                val id = if (chapter.url.startsWith("/go/")) {
                    chapter.url.substringAfter("/go/").decodeBase64()?.utf8()?.split('-')?.getOrNull(1)
                } else {
                    chapter.url.substringAfterLast('/').substringBefore('.')
                }
                id to chapter.url
            }
            return document.select("[data-mint-chapters] a[data-chapter-id][href]")
                .mapNotNull { anchor ->
                    val name = anchor.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val url = knownUrls[anchor.attr("data-chapter-id")]
                        ?: anchor.absUrl("href").toHttpUrl().encodedPath
                    Chapter(name, url)
                }
                .distinctBy { it.url }
        }
    }
}
