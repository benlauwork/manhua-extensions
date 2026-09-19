package eu.kanade.tachiyomi.extension.zh.laimanhua

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

@Source
abstract class LaiManhua : HttpSource() {

    override val supportsLatest = true

    // Tachimanga currently uses the Tachiyomi 1.4 source interface. Keep details and chapter
    // callbacks on that interface and use the complete mobile pages for app requests.
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .removeAll("Origin")
        .set("Referer", "$baseUrl/")

    private val mobileHeaders by lazy {
        headers.newBuilder()
            .set("User-Agent", MOBILE_USER_AGENT)
            .set("Referer", "$MOBILE_BASE_URL/")
            .build()
    }

    private val directoryHeaders by lazy {
        headers.newBuilder()
            .set("User-Agent", MOBILE_USER_AGENT)
            .set("Referer", "$DIRECTORY_BASE_URL/")
            .build()
    }

    // The image network rotates files across several CDN hosts. Retry another host when the
    // selected endpoint is unavailable instead of leaving the whole chapter blank.
    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            if (originalRequest.url.host !in IMAGE_HOSTS) {
                return@addInterceptor chain.proceed(originalRequest)
            }

            val hosts = listOf(originalRequest.url.host) + IMAGE_HOSTS.filterNot {
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
        .build()

    override fun popularMangaRequest(page: Int): Request = desktopRequest("$baseUrl/kanmanhua/zaixian_hit.html")

    override fun popularMangaParse(response: Response): MangasPage {
        val mangas = response.asJsoup().select("a.vtip[href][i]")
            .mapNotNull(::mangaFromListAnchor)
            .distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int): Request = desktopRequest("$baseUrl/kanmanhua/zaixian_recent.html")

    override fun latestUpdatesParse(response: Response): MangasPage {
        val mangas = response.asJsoup().select("a.video[href][i]")
            .mapNotNull(::mangaFromListAnchor)
            .distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) return popularMangaRequest(page)

        // Lai Manhua's legacy search endpoint is protected by a Cloudflare challenge. Its sister
        // site exposes the same catalogue and stable relative manga URLs.
        val url = "$SEARCH_BASE_URL/statics/searchelxt1e1.aspx".toHttpUrl().newBuilder()
            .addQueryParameter("key", query)
            .addQueryParameter("page", page.toString())
            .build()
        return desktopRequest(url)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("ul.mh-search-list > li")
            .mapNotNull(::mangaFromSearchElement)
        val hasNextPage = document.selectFirst("a:matchesOwn(^下一页$)") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromListAnchor(anchor: Element): SManga? {
        val title = anchor.attr("title").ifBlank { anchor.text() }
            .takeIf { it.isNotBlank() } ?: return null

        return SManga.create().apply {
            this.title = title
            thumbnail_url = anchor.attr("i").takeIf { it.isNotBlank() }
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

    override fun mangaDetailsRequest(manga: SManga): Request = mobileRequest(manga.url)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.metaContent("og:title")
                ?: document.metaContent("og:novel:book_name")
                ?: document.title()
            thumbnail_url = document.metaContent("og:image")
            author = document.metaContent("og:novel:author")
            artist = author
            genre = document.metaContent("og:novel:category")
            description = document.metaContent("og:description").meaningfulDescription()
                ?: document.selectFirst("div.introduction")?.text().meaningfulDescription()
            status = when (document.metaContent("og:novel:status")) {
                "连载中", "連載中" -> SManga.ONGOING
                "已完结", "已完結", "完结", "完結" -> SManga.COMPLETED
                "休刊" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    private fun Document.metaContent(property: String): String? = selectFirst("meta[property=$property]")?.attr("content")?.takeIf { it.isNotBlank() }

    override fun chapterListRequest(manga: SManga): Request = directoryRequest(manga.url)

    override fun chapterListParse(response: Response): List<SChapter> {
        val responseCode = response.code
        val responseUrl = response.request.url
        val document = response.asJsoup()
        val chapters = document.select(
            "#chapterList li > a[href], " +
                "#chapterList_ul_1 > li > a[href], " +
                ".plist li > a[href]",
        ).mapNotNull { anchor ->
            val name = anchor.attr("title").ifBlank { anchor.text() }
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SChapter.create().apply {
                this.name = name
                setUrlWithoutDomain(anchor.absUrl("href"))
            }
        }.distinctBy { it.url }

        if (chapters.isEmpty()) {
            throw Exception("来漫画目录为空：HTTP $responseCode · $responseUrl · ${document.title()}")
        }
        return chapters
    }

    override fun pageListRequest(chapter: SChapter): Request = mobileRequest(chapter.url)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val scripts = document.select("script").joinToString("\n") { it.data() }

        parseMobilePageList(scripts)?.let { return it }

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

    private fun parseMobilePageList(scripts: String): List<Page>? {
        val path = MHINFO_PATH_REGEX.find(scripts)?.groupValues?.get(1) ?: return null
        val images = MHINFO_IMAGES_REGEX.find(scripts)?.groupValues?.get(1)
            ?.let { value -> IMAGE_NAME_REGEX.findAll(value).map { it.groupValues[1] }.toList() }
            .orEmpty()
        if (images.isEmpty()) return null

        return images.mapIndexed { index, name ->
            Page(index, imageUrl = "$MOBILE_IMAGE_HOST$path$name")
        }
    }

    private fun resolveImageUrl(path: String, chapterId: Long): String? = when {
        path.startsWith("//") -> "https:$path"
        path.startsWith("/") -> {
            val imageHost = if (chapterId > LEGACY_CHAPTER_ID_LIMIT) DESKTOP_IMAGE_HOST else LEGACY_IMAGE_HOST
            imageHost + path
        }
        path.startsWith("https://") -> path
        path.startsWith("http://") -> path.replaceFirst("http://", "https://")
        else -> null
    }

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, mobileHeaders)

    // Lai Manhua pages already expose final image URLs, so this legacy callback is not used.
    override fun imageUrlParse(response: Response): String = response.request.url.toString()

    private fun decodeBase64(value: String): String = String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)

    private fun String?.meaningfulDescription(): String? = this?.trim()
        ?.takeIf { value -> value.isNotEmpty() && value.any { it != '.' && it != '…' } }

    private fun desktopRequest(url: String): Request = desktopRequest(url.toHttpUrl())

    private fun desktopRequest(url: HttpUrl): Request {
        val desktopUrl = url.newBuilder()
            .setQueryParameter("_desktop", "1")
            .build()
        return GET(desktopUrl, headers)
    }

    private fun mobileRequest(path: String): Request {
        val url = if (path.startsWith("http://") || path.startsWith("https://")) {
            path.toHttpUrl().newBuilder()
                .scheme("https")
                .host(MOBILE_BASE_URL.toHttpUrl().host)
                .build()
        } else {
            (MOBILE_BASE_URL + path).toHttpUrl()
        }
        return GET(url, mobileHeaders)
    }

    private fun directoryRequest(path: String): Request {
        val url = if (path.startsWith("http://") || path.startsWith("https://")) {
            path.toHttpUrl().newBuilder()
                .scheme("https")
                .host(DIRECTORY_BASE_URL.toHttpUrl().host)
                .build()
        } else {
            (DIRECTORY_BASE_URL + path).toHttpUrl()
        }
        return GET(url, directoryHeaders)
    }

    companion object {
        private const val PAGE_SEPARATOR = "\$qingtiandy\$"
        private const val LEGACY_CHAPTER_ID_LIMIT = 542724L
        private const val MOBILE_BASE_URL = "https://m.laimanhua88.com"
        private const val DIRECTORY_BASE_URL = "https://m.mh160mh.com"
        private const val SEARCH_BASE_URL = "https://www.mh160mh.com"
        private const val MOBILE_IMAGE_HOST = "https://xwdf.tgmhfc.uk"
        private const val DESKTOP_IMAGE_HOST = "https://mhpicwwt.tgmhfc.uk"
        private const val LEGACY_IMAGE_HOST = "https://mhpic6.tgmhfc.uk"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

        private val PAGE_DATA_REGEX = Regex("""var\s+picTree\s*=\s*['"]([^'"]*)""")
        private val CHAPTER_ID_REGEX = Regex("""var\s+currentChapterid\s*=\s*['"](\d+)""")
        private val MHINFO_PATH_REGEX = Regex(""""path":"([^"]+)"""")
        private val MHINFO_IMAGES_REGEX = Regex(""""images":\[(.*?)]""")
        private val IMAGE_NAME_REGEX = Regex(""""([^"]+)"""")
        private val IMAGE_HOSTS = listOf(
            "xwdf.tgmhfc.uk",
            "mhreswhm.tgmhfc.uk",
            "qwe123.tgmhfc.uk",
            "resmhpic.tgmhfc.uk",
            "reszxc.tgmhfc.uk",
            "mhpic5.tgmhfc.uk",
            "mhpic6.tgmhfc.uk",
            "mhpic88.tgmhfc.uk",
            "mhpicwwx.tgmhfc.uk",
            "mhpicwwt.tgmhfc.uk",
            "mhpic789-5.tgmhfc.uk",
            "mhpic5er.tgmhfc.uk",
            "mhpic7fr.tgmhfc.uk",
            "mhpicwt.tgmhfc.uk",
            "mhpicwx.tgmhfc.uk",
        )
    }
}
