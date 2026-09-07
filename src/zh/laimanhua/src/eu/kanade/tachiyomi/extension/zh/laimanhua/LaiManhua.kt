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
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

@Source
abstract class LaiManhua : KeiSource() {

    // Mobile requests are redirected to a page that omits the image data.
    override fun Headers.Builder.configureHeaders() = removeAll("Origin")
        .set("User-Agent", DESKTOP_USER_AGENT)

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return emptyMangaPage()
        val document = client.get("$baseUrl/kanmanhua/zaixian_hit.html").asJsoup()
        val mangas = document.select("a.vtip[href][i]")
            .mapNotNull(::mangaFromListAnchor)
            .distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return emptyMangaPage()
        val document = client.get("$baseUrl/kanmanhua/zaixian_recent.html").asJsoup()
        val mangas = document.select("a.video[href][i]")
            .mapNotNull(::mangaFromListAnchor)
            .distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)
        if (page > 1) return emptyMangaPage()

        // The site's form and search backend use GBK rather than UTF-8.
        @Suppress("DEPRECATION")
        val encodedQuery = URLEncoder.encode(query, "GBK")
        val body = "key=$encodedQuery".toRequestBody(FORM_MEDIA_TYPE)
        val document = client.post("$baseUrl/s81/search/", headers, body).asJsoup()
        val mangas = document.select(".dmList > ul > li").mapNotNull(::mangaFromSearchElement)
        return MangasPage(mangas, false)
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
        val anchor = element.selectFirst("dl > dt > a[href]") ?: return null
        val title = anchor.attr("title").ifBlank { anchor.text() }
            .takeIf { it.isNotBlank() } ?: return null

        return SManga.create().apply {
            this.title = title
            thumbnail_url = element.selectFirst("p.cover img")?.absUrl("src")
            setUrlWithoutDomain(anchor.absUrl("href"))
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
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
        val document = client.get(baseUrl + manga.url).asJsoup()
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
        val chapterUrl = (baseUrl + chapter.url).toHttpUrl().newBuilder()
            .addQueryParameter("_desktop", "1")
            .build()
        val document = client.get(chapterUrl).asJsoup()
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

    private fun decodeBase64(value: String): String = String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)

    companion object {
        private const val PAGE_SEPARATOR = "\$qingtiandy\$"
        private const val LEGACY_CHAPTER_ID_LIMIT = 542724L
        private const val IMAGE_HOST = "https://mhpicwwt.tgmhfc.uk"
        private const val LEGACY_IMAGE_HOST = "https://mhpic6.tgmhfc.uk"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

        private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
        private val PAGE_DATA_REGEX = Regex("""var\s+picTree\s*=\s*['"]([^'"]*)""")
        private val CHAPTER_ID_REGEX = Regex("""var\s+currentChapterid\s*=\s*['"](\d+)""")
    }
}
