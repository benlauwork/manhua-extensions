package eu.kanade.tachiyomi.extension.zh.laimanhua

import android.util.Base64
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

@Source
abstract class LaiManhua : KeiSource() {

    override fun Headers.Builder.configureHeaders() = removeAll("Origin")

    // The image network rotates files across several CDN hosts. Retry another host when the
    // selected endpoint is unavailable instead of leaving the whole chapter blank.
    override fun OkHttpClient.Builder.configureClient() = addInterceptor { chain ->
        val originalRequest = chain.request()
        if (originalRequest.url.host !in MODERN_IMAGE_HOSTS) {
            return@addInterceptor chain.proceed(originalRequest)
        }

        val hosts = listOf(originalRequest.url.host) + MODERN_IMAGE_HOSTS.filterNot {
            it == originalRequest.url.host
        }
        var lastException: IOException? = null

        hosts.forEachIndexed { index, host ->
            val request = originalRequest.newBuilder()
                .url(originalRequest.url.newBuilder().host(host).build())
                .build()
            try {
                val response = chain.proceed(request)
                if (response.isSuccessful || index == hosts.lastIndex) {
                    return@addInterceptor response
                }
                response.close()
            } catch (exception: IOException) {
                lastException = exception
                if (index == hosts.lastIndex) throw exception
            }
        }

        throw lastException ?: IOException("No Lai Manhua image host was available")
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return emptyMangaPage()
        val document = getDesktopDocument("$baseUrl/kanmanhua/zaixian_hit.html")
        val mangas = document.select("a.vtip[href][i]")
            .mapNotNull(::mangaFromListAnchor)
            .distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return emptyMangaPage()
        val document = getDesktopDocument("$baseUrl/kanmanhua/zaixian_recent.html")
        val mangas = document.select("a.video[href][i]")
            .mapNotNull(::mangaFromListAnchor)
            .distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)

        // Lai Manhua's legacy search endpoint is now protected by a Cloudflare challenge.
        // Its sister site exposes the same catalogue and stable relative manga URLs.
        val url = "$SEARCH_BASE_URL/statics/searchelxt1e1.aspx".toHttpUrl().newBuilder()
            .addQueryParameter("key", query)
            .addQueryParameter("page", page.toString())
            .build()
        val document = getDesktopDocument(url)
        val mangas = document.select("ul.mh-search-list > li")
            .mapNotNull(::mangaFromSearchElement)
        val hasNextPage = document.selectFirst("a:matchesOwn(^下一页$)") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun emptyMangaPage() = MangasPage(emptyList(), false)

    private fun mangaFromListAnchor(anchor: Element): SManga? {
        val title = anchor.attr("title").ifBlank { anchor.text() }
            .takeIf { it.isNotBlank() } ?: return null
        val thumbnail = anchor.attr("i").takeIf { it.isNotBlank() }

        return SManga.create().apply {
            this.title = title
            thumbnail_url = thumbnail
            setUrlWithoutDomain(anchor.absUrl("href"))
        }
    }

    private fun mangaFromSearchElement(element: Element): SManga? {
        val anchor = element.selectFirst(".mh-works-title h4 > a[href]") ?: return null
        val title = anchor.text().takeIf { it.isNotBlank() } ?: return null

        return SManga.create().apply {
            this.title = title
            thumbnail_url = element.selectFirst(".mh-nlook-w img")?.absUrl("src")
            setUrlWithoutDomain(anchor.absUrl("href"))
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host !in SUPPORTED_HOSTS) return null
        if (url.pathSegments.firstOrNull() != "kanmanhua") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null

        return fetchMangaUpdate(
            SManga.create().apply { this.url = "/kanmanhua/$slug/" },
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
        title = document.metaContent("og:title")
            ?: document.selectFirst("h1")?.text()
            ?: manga.title
        thumbnail_url = document.metaContent("og:image")
            ?: document.selectFirst("#intro_l p.cover img")?.absUrl("src")
            ?: manga.thumbnail_url
        author = document.metaContent("og:novel:author")
        artist = author
        genre = document.metaContent("og:novel:category")
        description = document.metaContent("og:description")
            ?: document.selectFirst("div.introduction")?.text()?.takeIf { it.isNotBlank() }
        status = when (document.metaContent("og:novel:status")) {
            "连载中", "連載中" -> SManga.ONGOING
            "已完结", "已完結", "完结", "完結" -> SManga.COMPLETED
            "休刊" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    private fun Document.metaContent(property: String): String? = selectFirst("meta[property=$property]")?.attr("content")?.takeIf { it.isNotBlank() }

    private fun parseChapterList(document: Document): List<SChapter> = document.select(".plist li > a[href]")
        .mapNotNull { anchor ->
            val name = anchor.attr("title").ifBlank { anchor.ownText() }
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SChapter.create().apply {
                this.name = name
                setUrlWithoutDomain(anchor.absUrl("href"))
            }
        }
        .distinctBy { it.url }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = getDesktopDocument(baseUrl + chapter.url)
        val scripts = document.select("script").joinToString("\n") { it.data() }
        val pageData = PAGE_DATA_REGEX.find(scripts)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val decodedPageData = if (
            pageData.contains(PAGE_SEPARATOR) ||
            pageData.contains("mh160tuku") ||
            pageData.startsWith("http")
        ) {
            pageData
        } else {
            decodeBase64(pageData)
        }
        val chapterId = CHAPTER_ID_REGEX.find(scripts)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        return decodedPageData.split(PAGE_SEPARATOR)
            .filter { it.isNotBlank() }
            .mapIndexedNotNull { index, path ->
                val imageUrl = resolveImageUrl(path, chapterId) ?: return@mapIndexedNotNull null
                Page(index, imageUrl = imageUrl)
            }
    }

    private fun resolveImageUrl(path: String, chapterId: Long): String? = when {
        path.startsWith("//") -> "https:$path"
        path.startsWith("/") -> {
            val imageHost = if (chapterId > LEGACY_CHAPTER_ID_LIMIT) IMAGE_HOST else LEGACY_IMAGE_HOST
            imageHost + path
        }
        path.startsWith("https://") -> path
        path.startsWith("http://") -> path.replaceFirst("http://", "https://")
        else -> null
    }

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .headers(headers)
        .get()
        .build()

    private fun decodeBase64(value: String): String = String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)

    private suspend fun getDesktopDocument(url: String): Document = getDesktopDocument(url.toHttpUrl())

    private suspend fun getDesktopDocument(url: HttpUrl): Document {
        val desktopUrl = url.newBuilder()
            .setQueryParameter("_desktop", "1")
            .build()
        return client.get(desktopUrl, headers).asJsoup()
    }

    companion object {
        private const val PAGE_SEPARATOR = "\$qingtiandy\$"
        private const val LEGACY_CHAPTER_ID_LIMIT = 542724L
        private const val IMAGE_HOST = "https://mhpicwwt.tgmhfc.uk"
        private const val LEGACY_IMAGE_HOST = "https://mhpic6.tgmhfc.uk"
        private const val SEARCH_BASE_URL = "https://www.mh160mh.com"

        private val PAGE_DATA_REGEX = Regex("""var\s+picTree\s*=\s*['"]([^'"]*)""")
        private val CHAPTER_ID_REGEX = Regex("""var\s+currentChapterid\s*=\s*['"](\d+)""")
        private val SUPPORTED_HOSTS = setOf("www.laimanhua88.com", "m.laimanhua88.com")
        private val MODERN_IMAGE_HOSTS = listOf(
            "mhpicwwt.tgmhfc.uk",
            "mhpic789-5.tgmhfc.uk",
            "mhpic5er.tgmhfc.uk",
            "mhpic7fr.tgmhfc.uk",
            "mhpicwt.tgmhfc.uk",
            "mhpicwx.tgmhfc.uk",
        )
    }
}
