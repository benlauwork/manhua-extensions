package eu.kanade.tachiyomi.extension.zh.manhua1234

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
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class Manhua1234 : KeiSource() {

    // Image requests require the desktop site's Referer. Do not set a desktop User-Agent here:
    // the app WebView must remain free to follow the site's normal redirect to m.wmh1234.com.
    override fun Headers.Builder.configureHeaders() = removeAll("Origin")
        .set("Referer", "$BASE_URL/")

    // Catalog, detail and reader markup is complete on www but incomplete on the mobile host.
    // Limit the desktop User-Agent to background parser requests so it cannot affect WebView.
    private val desktopHeaders by lazy {
        headers.newBuilder()
            .set("User-Agent", DESKTOP_USER_AGENT)
            .build()
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return emptyMangaPage()

        val document = getDesktopDocument("$baseUrl/custom/top")
        val mangas = document.select(".ranking-content.active .ranking-item")
            .mapNotNull { element ->
                mangaFromClickableCard(
                    element = element,
                    titleSelector = ".ranking-comic-title",
                    imageSelector = ".ranking-cover img",
                )
            }
            .distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return emptyMangaPage()

        val document = getDesktopDocument("$baseUrl/custom/update")
        val mangas = document.select(".update-content.active .update-card")
            .mapNotNull { element ->
                mangaFromClickableCard(
                    element = element,
                    titleSelector = ".update-comic-title",
                    imageSelector = ".update-cover",
                )
            }
            .distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)

        val urlBuilder = "$baseUrl/search".toHttpUrl().newBuilder()
            .addPathSegment(query)
        if (page > 1) urlBuilder.addPathSegment(page.toString())

        val document = getDesktopDocument(urlBuilder.build())
        val mangas = document.select("a.comic-card[href]")
            .mapNotNull(::mangaFromComicCard)
            .distinctBy { it.url }
        val hasNextPage = document.selectFirst("a.pagination-btn[href]:matchesOwn(^下一页$)")
            ?.attr("href")
            ?.substringBefore('?')
            ?.endsWith("/${page + 1}")
            ?: false
        return MangasPage(mangas, hasNextPage)
    }

    private fun emptyMangaPage() = MangasPage(emptyList(), false)

    private fun mangaFromClickableCard(
        element: Element,
        titleSelector: String,
        imageSelector: String,
    ): SManga? {
        val path = COMIC_PATH_REGEX.find(element.attr("onclick"))?.groupValues?.get(1)
            ?: return null
        val title = element.selectFirst(titleSelector)?.text()?.takeIf { it.isNotBlank() }
            ?: return null

        return SManga.create().apply {
            this.title = title
            thumbnail_url = element.selectFirst(imageSelector).imageUrl()
            setUrlWithoutDomain(baseUrl + path)
        }
    }

    private fun mangaFromComicCard(anchor: Element): SManga? {
        val title = anchor.selectFirst(".comic-title")?.text()
            ?.ifBlank { anchor.attr("title") }
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return SManga.create().apply {
            this.title = title
            thumbnail_url = anchor.selectFirst("img").imageUrl()
            setUrlWithoutDomain(anchor.absUrl("href"))
        }
    }

    private fun Element?.imageUrl(): String? {
        val image = this ?: return null
        return image.absUrl("data-original")
            .ifBlank { image.absUrl("data-src") }
            .ifBlank { image.absUrl("src") }
            .takeIf { it.isNotBlank() }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host !in SUPPORTED_HOSTS) return null
        val path = COMIC_DETAIL_PATH_REGEX.matchEntire(url.encodedPath)?.value ?: return null

        return fetchMangaUpdate(
            SManga.create().apply { this.url = path },
            emptyList(),
            fetchDetails = true,
            fetchChapters = true,
        ).manga.apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = getDesktopDocument(baseUrl + manga.url)
        val updatedManga = if (fetchDetails) parseMangaDetails(manga, document) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(document) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(manga: SManga, document: Document): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("[data-comic-name]")?.text()
            ?: document.selectFirst(".mint-detail-copy h1")?.text()
            ?: document.metaContent("og:title")
            ?: manga.title
        thumbnail_url = document.metaContent("og:image")
            ?: document.selectFirst(".mint-detail-cover").imageUrl()
            ?: manga.thumbnail_url
        author = document.metaContent("book:author")
            ?: document.selectFirst(".mint-detail-meta > span:matchesOwn(^作者：)")?.text()?.substringAfter("作者：")
        artist = author
        genre = document.metaContent("book:tag")
            ?: document.select(".mint-detail-meta > span").getOrNull(2)?.text()
        description = (
            document.selectFirst(".mint-detail-description")?.text()
                ?: document.metaContent("og:description")
            )
            ?.removePrefix("简介：")
            ?.removePrefix("簡介：")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        status = when (
            document.select(".mint-detail-meta > span").firstOrNull { element ->
                element.text().contains("连载") || element.text().contains("連載") ||
                    element.text().contains("完结") || element.text().contains("完結") ||
                    element.text().contains("休刊")
            }?.text()
        ) {
            "连载", "连载中", "連載", "連載中" -> SManga.ONGOING
            "完结", "已完结", "完結", "已完結" -> SManga.COMPLETED
            "休刊" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    private fun Document.metaContent(property: String): String? = selectFirst("meta[property=$property]")?.attr("content")?.takeIf { it.isNotBlank() }

    private fun parseChapterList(document: Document): List<SChapter> = document.select(".mint-chapters a[href^=/go/]")
        .mapNotNull { anchor ->
            val name = anchor.attr("title").ifBlank { anchor.text() }
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SChapter.create().apply {
                this.name = name
                setUrlWithoutDomain(anchor.absUrl("href"))
            }
        }
        .distinctBy { it.url }
        .asReversed()

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // /go/ returns a JavaScript location.replace page, which OkHttp deliberately does not
        // execute. Its token maps directly to the real reader endpoint.
        val token = chapter.url.substringAfter("/go/", missingDelimiterValue = "")
            .substringBefore('?')
            .takeIf { it.isNotBlank() }
            ?: return emptyList()
        val readerUrl = "$READER_BASE_URL/r".toHttpUrl().newBuilder()
            .addPathSegment(token)
            .build()
        val document = getDesktopDocument(readerUrl)
        return document.select("img.reader-image[data-src]")
            .mapIndexedNotNull { index, image ->
                val imageUrl = image.absUrl("data-src").takeIf { it.isNotBlank() }
                    ?: return@mapIndexedNotNull null
                Page(index, imageUrl = imageUrl)
            }
    }

    private suspend fun getDesktopDocument(url: String): Document = getDesktopDocument(url.toHttpUrl())

    private suspend fun getDesktopDocument(url: HttpUrl): Document = client.get(url, desktopHeaders).asJsoup()

    companion object {
        private const val BASE_URL = "https://www.wmh1234.com"
        private const val READER_BASE_URL = "https://reader.hqread.cc"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

        private val SUPPORTED_HOSTS = setOf("wmh1234.com", "www.wmh1234.com", "m.wmh1234.com")
        private val COMIC_PATH_REGEX = Regex("""['"](/comic/\d+\.html)['"]""")
        private val COMIC_DETAIL_PATH_REGEX = Regex("""/comic/\d+\.html""")
    }
}
