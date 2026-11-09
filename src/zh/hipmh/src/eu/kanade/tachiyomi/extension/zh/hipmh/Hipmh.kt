package eu.kanade.tachiyomi.extension.zh.hipmh

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
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.io.IOException
import kotlin.time.Instant

@Source
abstract class Hipmh : KeiSource() {
    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/popularity".toHttpUrl().newBuilder().addQueryParameter("page", page.toString()).build()
        val document = client.get(url).asJsoup()
        val hasNextPage = document.selectFirst("a.pagination-next[href][aria-disabled=false]") != null
        return MangasPage(mangaCards(document, "a.manga-card-link[href]"), hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val document = client.get(baseUrl).asJsoup()
        return MangasPage(mangaCards(document, ".recent-updates-item a[href]"), false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val term = query.trim()
        if (term.isEmpty()) return getPopularManga(page)
        val url = "$API_BASE/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", term)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", "20")
            .build()
        val results = client.get(url, apiHeaders(baseUrl)).parseAs<ApiResponse<SearchResults>>().data
        val mangas = results.data.map { item ->
            manga(item.id, item.title, item.cover?.let(COVER_BASE::resolve)?.toString())
        }.distinctBy { it.url }
        return MangasPage(mangas, page < results.totalPages)
    }

    private fun mangaCards(document: Document, selector: String): List<SManga> = document.select(selector).mapNotNull { card ->
        val title = card.attr("aria-label").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val href = card.attr("href").substringAfter("/works/", "")
        if (href.isBlank()) return@mapNotNull null
        manga(href, title, card.selectFirst("img[src]")?.absUrl("src"))
    }.distinctBy { it.url }

    private fun manga(id: String, title: String, thumbnail: String?): SManga = SManga.create().apply {
        // Search can return old and new slugs for the same work ID.
        url = "/works/${id.substringBefore('-')}"
        this.title = title
        thumbnail_url = thumbnail
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments
        val id = segments.dropWhile { it != "works" }.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        return mangaDetails(manga(id, "", null))
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = SMangaUpdate(
        manga = if (fetchDetails) mangaDetails(manga) else manga,
        chapters = if (fetchChapters) chapterList(manga.url.substringAfterLast('/')) else chapters,
    )

    private suspend fun mangaDetails(manga: SManga): SManga {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SManga.create().apply {
            url = manga.url
            title = document.selectFirst("h1")?.text() ?: throw IOException("搵唔到嬉皮漫畫作品")
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?.let(COVER_BASE::resolve)?.toString() ?: manga.thumbnail_url
            author = document.select("aside a[href*=/author/]").eachText().distinct().joinToString(", ")
            genre = document.select("aside a[href*=/genre/]").eachText().distinct().joinToString(", ")
            description = document.selectFirst("meta[property=og:description]")?.attr("content")
            status = when (document.selectFirst("aside a[href=/ongoing], aside a[href=/completed], aside a[href=/hiatus]")?.attr("href")) {
                "/ongoing" -> SManga.ONGOING
                "/completed" -> SManga.COMPLETED
                "/hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    private suspend fun chapterList(workId: String): List<SChapter> {
        val firstPage = fetchChapterPage(workId, 1)
        val items = buildList {
            addAll(firstPage.items)
            for (page in 2..firstPage.totalPages) addAll(fetchChapterPage(workId, page).items)
        }
        return items.distinctBy { it.hid }.map { chapter ->
            SChapter.create().apply {
                url = "/chapter/${chapter.hid}"
                name = chapter.title?.takeIf(String::isNotBlank) ?: "第${chapter.chapterNumber}話"
                chapter_number = chapter.chapterNumber ?: -1f
                date_upload = chapter.updatedAt?.let { Instant.tryParse(it) } ?: 0L
            }
        }
    }

    private suspend fun fetchChapterPage(workId: String, page: Int): ChapterList {
        val url = "$API_BASE/v1/manga/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("mid", workId)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "50")
            .addQueryParameter("order", "desc")
            .build()
        return client.get(url, apiHeaders(baseUrl)).parseAs<ApiResponse<ChapterList>>().data
    }

    override fun getChapterUrl(chapter: SChapter) = READER_BASE + chapter.url

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val reader = client.get(getChapterUrl(chapter)).asJsoup()
        val container = reader.selectFirst("#chapcontent[data-api-hid][data-chapter-img-base]")
            ?: throw IOException("呢話暫時未能讀取")
        val endpoint = "$API_BASE/v2/chapter".toHttpUrl().newBuilder()
            .addQueryParameter("hid", container.attr("data-api-hid"))
            .build()
        val chapterImages = client.get(endpoint, apiHeaders(READER_BASE)).parseAs<ApiResponse<ChapterImages>>().data
        val images = when (val payload = chapterImages.images) {
            is JsonPrimitive -> Reader.decode(payload.content).parseAs<List<String>>()
            else -> payload.toString().parseAs<List<String>>()
        }
        val imageBaseAttribute = if (chapterImages.line == 9) "data-chapter-img-base-line1s" else "data-chapter-img-base"
        val imageBase = container.attr(imageBaseAttribute).toHttpUrl()
        val urls = Reader.withoutExtraImage(images, chapterImages.orderId, chapterImages.sid)
        if (urls.isEmpty()) throw IOException("搵唔到本話嘅圖片")
        return urls.mapIndexed { index, path ->
            val url = imageBase.resolve(path) ?: throw IOException("嬉皮漫畫圖片網址無效")
            Page(index, imageUrl = url.toString())
        }
    }

    private fun apiHeaders(origin: String): Headers = headers.newBuilder()
        .set("Referer", "$origin/")
        .set("Origin", origin)
        .set("Accept", "application/json")
        .set("Content-Type", "application/json")
        .build()

    private companion object {
        const val API_BASE = "https://hipapi1.s3file.top"
        const val READER_BASE = "https://reader.hipmh.top"
        val COVER_BASE = "https://cover.s3imgs.top".toHttpUrl()
    }
}
