package eu.kanade.tachiyomi.extension.zh.baoziddmanga

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import java.io.IOException

@Source
abstract class BaoziManhua : KeiSource() {
    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/classify".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        val results = mangaCards(client.get(url).asJsoup())
        return MangasPage(results, results.size >= 36)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        return MangasPage(mangaCards(client.get("$baseUrl/list/new").asJsoup()), false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val term = query.trim()
        if (term.isEmpty()) return getPopularManga(page)
        if (page > 1) return MangasPage(emptyList(), false)

        // The WEBTOON title is an alias; the site's search only knows the Baozi title.
        val keyword = when (term) {
            "重生傭兵的完美計畫", "重生佣兵的完美计划" -> "重生傭兵王的復仇"
            else -> term
        }
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", keyword)
            .build()
        return MangasPage(mangaCards(client.get(url).asJsoup()), false)
    }

    private fun mangaCards(document: Document): List<SManga> = document.select(".comics-card a.comics-card__poster[href]")
        .mapNotNull { poster ->
            val title = poster.attr("title").ifBlank { return@mapNotNull null }
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(poster.absUrl("href"))
                thumbnail_url = poster.selectFirst("amp-img[src], img[src]")?.absUrl("src")
            }
        }
        .distinctBy { it.url }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host !in SUPPORTED_HOSTS || url.pathSegments.firstOrNull() != "comic") return null
        val segments = url.pathSegments
        val slug = segments.getOrNull(if (segments.getOrNull(1) == "chapter") 2 else 1)
            ?.takeIf { it.isNotBlank() } ?: return null
        val manga = SManga.create().apply { this.url = "/comic/$slug" }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()
        return SMangaUpdate(
            manga = if (fetchDetails) mangaDetails(manga, document) else manga,
            chapters = if (fetchChapters) chapterList(document) else chapters,
        )
    }

    private fun mangaDetails(manga: SManga, document: Document): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("h1.comics-detail__title")?.text()
            ?: throw IOException("搵唔到漫畫資料")
        thumbnail_url = document.selectFirst("meta[name=og:image]")?.attr("content")
        author = document.selectFirst(".comics-detail__author")?.text()
        description = document.selectFirst(".comics-detail__desc")?.text()
        val tags = document.select(".tag-list > .tag").map { it.text() }
        status = when (tags.firstOrNull()) {
            "連載中", "连载中" -> SManga.ONGOING
            "已完結", "已完结" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        genre = tags.drop(1).joinToString().ifBlank { null }
    }

    private fun chapterList(document: Document): List<SChapter> {
        val anchors = document.select("#chapter-items a.comics-chapters__item[href], #chapters_other_list a.comics-chapters__item[href]")
            .asReversed()
            .ifEmpty { document.select(".comics-detail a.comics-chapters__item[href]") }
        return anchors.mapNotNull { anchor ->
            val link = anchor.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
            val path = if (link.encodedPath == "/user/page_direct") {
                val slug = link.queryParameter("comic_id") ?: return@mapNotNull null
                val section = link.queryParameter("section_slot") ?: return@mapNotNull null
                val slot = link.queryParameter("chapter_slot") ?: return@mapNotNull null
                "/comic/chapter/$slug/${section}_$slot.html"
            } else {
                link.encodedPath.takeIf { it.startsWith("/comic/chapter/") } ?: return@mapNotNull null
            }
            SChapter.create().apply {
                url = path
                name = anchor.text()
            }
        }.distinctBy { it.url }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val firstUrl = (baseUrl + chapter.url).toHttpUrl()
        val partPrefix = firstUrl.encodedPath.removeSuffix(".html") + "_"
        val visitedPaths = mutableSetOf<String>()
        val images = linkedSetOf<String>()
        var url: HttpUrl? = firstUrl

        // Reader parts overlap. Follow only links to the same chapter and keep each image once.
        while (url != null && visitedPaths.add(url.encodedPath)) {
            val document = client.get(url).asJsoup()
            val partImages = document.select(".comic-contain img[src], .comic-contain img[data-src]")
                .map { it.absUrl("data-src").ifBlank { it.absUrl("src") } }
                .filter(String::isNotBlank)
            if (partImages.isEmpty()) throw IOException("搵唔到本話嘅圖片")
            images.addAll(partImages)
            url = document.select(".next_chapter a[href]")
                .mapNotNull { it.absUrl("href").toHttpUrlOrNull() }
                .firstOrNull { it.host in SUPPORTED_HOSTS && it.encodedPath.startsWith(partPrefix) }
        }

        return images.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    private companion object {
        val SUPPORTED_HOSTS = setOf("www.ddmanga.com", "cn.ddmanga.com", "www.twbzmg.com", "www.baozimh.com", "cn.baozimh.com")
    }
}
