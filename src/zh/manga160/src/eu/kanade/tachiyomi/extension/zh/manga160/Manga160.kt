package eu.kanade.tachiyomi.extension.zh.manga160

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
abstract class Manga160 : HttpSource() {

    override val supportsLatest = true

    // Tachimanga currently uses the Tachiyomi 1.4 source interface. Keep all network and parse
    // callbacks on that interface so manga details and chapter lists reach the app correctly.
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .removeAll("Origin")
        .set("Referer", "$baseUrl/")

    // The site rotates modern chapter images across several CDN hosts. Retry another host when
    // the selected endpoint is unavailable instead of leaving the whole chapter blank.
    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
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

            throw lastException ?: IOException("No Manga 160 image host was available")
        }
        .build()

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/kanmanhua/allhit/"
        } else {
            "$baseUrl/kanmanhua/allhit/$page.html"
        }
        return desktopRequest(url)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response.asJsoup())

    override fun latestUpdatesRequest(page: Int): Request = desktopRequest("$baseUrl/kanmanhua/zaixian_recent.html")

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response.asJsoup())

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) return popularMangaRequest(page)

        val url = "$baseUrl/statics/searchelxt1e1.aspx".toHttpUrl().newBuilder()
            .addQueryParameter("key", query)
            .addQueryParameter("page", page.toString())
            .build()
        return desktopRequest(url)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response.asJsoup())

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select("ul.mh-search-list > li").mapNotNull(::mangaFromElement)
        val hasNextPage = document.selectFirst("a:matchesOwn(^下一页$)") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element): SManga? {
        val anchor = element.selectFirst(".mh-works-title h4 > a[href]") ?: return null
        val title = anchor.text().takeIf { it.isNotBlank() } ?: return null

        return SManga.create().apply {
            this.title = title
            thumbnail_url = element.selectFirst(".mh-nlook-w img")?.absUrl("src")
            setUrlWithoutDomain(anchor.absUrl("href"))
        }
    }

    override fun mangaDetailsRequest(manga: SManga): Request = desktopRequest(baseUrl + manga.url)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst(".mh-date-info-name h4 > a")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: ""
            thumbnail_url = document.selectFirst(".mh-date-bgpic img")?.absUrl("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            author = document.selectFirst("meta[property=og:novel:author]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
            artist = author
            genre = document.selectFirst("meta[property=og:novel:category]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
            description = document.selectFirst("#workint")?.text().meaningfulDescription()
                ?: document.selectFirst("meta[property=og:description]")?.attr("content").meaningfulDescription()
                ?: document.selectFirst("meta[name=description]")?.attr("content").meaningfulDescription()
            status = when (
                document.selectFirst("meta[property=og:novel:status]")?.attr("content")?.lowercase()
            ) {
                "连载中", "連載中" -> SManga.ONGOING
                "已完结", "已完結", "完结", "完結" -> SManga.COMPLETED
                "休刊" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request = desktopRequest(baseUrl + manga.url)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = document.select(".cy_plist ul > li > a[href], .cy_plist a[href$=.html]")
            .mapNotNull { anchor ->
                val name = anchor.selectFirst("p")?.text()
                    ?.takeIf { it.isNotBlank() }
                    ?: anchor.attr("title").takeIf { it.isNotBlank() }
                    ?: anchor.ownText().takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                SChapter.create().apply {
                    this.name = name
                    setUrlWithoutDomain(anchor.absUrl("href"))
                }
            }
            .distinctBy { it.url }

        if (chapters.isEmpty()) {
            throw Exception("漫画160未能读取章节目录，请在扩展页面更新后再试")
        }
        return chapters
    }

    override fun pageListRequest(chapter: SChapter): Request = desktopRequest(baseUrl + chapter.url)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val script = document.selectFirst("script:containsData(qTcms_S_m_murl_e)")?.data()
            ?: return emptyList()
        val encoded = PAGE_DATA_REGEX.find(script)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val paths = decodeBase64(encoded).split(PAGE_SEPARATOR).filter { it.isNotBlank() }
        if (paths.any { it.startsWith("+http://") || it.startsWith("+https://") }) return emptyList()

        val chapterId = CHAPTER_ID_REGEX.find(script)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val mangaId = MANGA_ID_REGEX.find(script)?.groupValues?.get(1).orEmpty()
        val proxyMode = PROXY_MODE_REGEX.find(script)?.groupValues?.get(1).orEmpty()
        val remoteBase = MHTTP_REGEX.find(script)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodeBase64)
            .orEmpty()

        return paths.mapIndexedNotNull { index, path ->
            val imageUrl = resolveImageUrl(path, chapterId, mangaId, proxyMode, remoteBase)
                ?: return@mapIndexedNotNull null
            Page(index, imageUrl = imageUrl)
        }
    }

    private fun resolveImageUrl(
        path: String,
        chapterId: Long,
        mangaId: String,
        proxyMode: String,
        remoteBase: String,
    ): String? = when {
        path.startsWith("/") -> {
            val host = if (chapterId > LEGACY_CHAPTER_ID_LIMIT) IMAGE_HOST else LEGACY_IMAGE_HOST
            host + path
        }
        path.startsWith("--http://") || path.startsWith("--https://") -> null
        path.startsWith("http://") || path.startsWith("https://") -> {
            if (proxyMode == "2") {
                path.replaceFirst("http://", "https://")
            } else {
                val protectedPath = path
                    .replace("?", "a1a1")
                    .replace("&", "b1b1")
                    .replace("%", "c1c1")
                "$baseUrl/statics/pic/".toHttpUrl().newBuilder()
                    .addQueryParameter("p", protectedPath)
                    .addQueryParameter("picid", mangaId)
                    .addQueryParameter("m_httpurl", remoteBase)
                    .build()
                    .toString()
            }
        }
        else -> null
    }

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

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

    companion object {
        private const val PAGE_SEPARATOR = "\$qingtiandy\$"
        private const val LEGACY_CHAPTER_ID_LIMIT = 542724L
        private const val IMAGE_HOST = "https://mhpic789-5.tgmhfc.uk"
        private const val LEGACY_IMAGE_HOST = "https://mhpic6.tgmhfc.uk"

        private val PAGE_DATA_REGEX = Regex("""var qTcms_S_m_murl_e="([^"]*)"""")
        private val CHAPTER_ID_REGEX = Regex("""var qTcms_S_p_id="(\d+)"""")
        private val MANGA_ID_REGEX = Regex("""var qTcms_S_m_id="(\d+)"""")
        private val PROXY_MODE_REGEX = Regex("""var qTcms_Pic_m_if="([^"]*)"""")
        private val MHTTP_REGEX = Regex("""var qTcms_S_m_mhttpurl="([^"]*)"""")
        private val MODERN_IMAGE_HOSTS = listOf(
            "mhpic789-5.tgmhfc.uk",
            "mhpic5er.tgmhfc.uk",
            "mhpic7fr.tgmhfc.uk",
            "mhpicwt.tgmhfc.uk",
            "mhpicwx.tgmhfc.uk",
        )
    }
}
