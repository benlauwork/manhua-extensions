package eu.kanade.tachiyomi.extension.zh.manga160

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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class Manga160 : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage {
        val pageUrl = if (page == 1) {
            "$baseUrl/kanmanhua/allhit/"
        } else {
            "$baseUrl/kanmanhua/allhit/$page.html"
        }
        return parseMangaList(client.get(pageUrl).asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        return parseMangaList(client.get("$baseUrl/kanmanhua/zaixian_recent.html").asJsoup())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)

        val url = "$baseUrl/statics/searchelxt1e1.aspx".toHttpUrl().newBuilder()
            .addQueryParameter("key", query)
            .addQueryParameter("page", page.toString())
            .build()
        return parseMangaList(client.get(url).asJsoup())
    }

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
        title = document.selectFirst(".mh-date-info-name h4 > a")?.text() ?: manga.title
        thumbnail_url = document.selectFirst(".mh-date-bgpic img")?.absUrl("src") ?: manga.thumbnail_url
        author = document.selectFirst("meta[property=og:novel:author]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
        artist = author
        genre = document.selectFirst("meta[property=og:novel:category]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
        description = document.selectFirst("#workint")?.text()
            ?.takeIf { it.isNotBlank() && it != "..." && it != "......" }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
        status = when (
            document.selectFirst("meta[property=og:novel:status]")?.attr("content")?.lowercase()
        ) {
            "连载中", "連載中" -> SManga.ONGOING
            "已完结", "已完結", "完结", "完結" -> SManga.COMPLETED
            "休刊" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapterList(document: Document): List<SChapter> = document.select(".cy_plist ul > li > a[href]")
        .mapNotNull { anchor ->
            val name = anchor.selectFirst("p")?.text()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            SChapter.create().apply {
                this.name = name
                setUrlWithoutDomain(anchor.absUrl("href"))
            }
        }
        .distinctBy { it.url }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(baseUrl + chapter.url).asJsoup()
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

    private fun decodeBase64(value: String): String = String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)

    companion object {
        private const val PAGE_SEPARATOR = "\$qingtiandy\$"
        private const val LEGACY_CHAPTER_ID_LIMIT = 542724L
        private const val IMAGE_HOST = "https://mhpicwt.tgmhfc.uk"
        private const val LEGACY_IMAGE_HOST = "https://mhpic6.tgmhfc.uk"

        private val PAGE_DATA_REGEX = Regex("""var qTcms_S_m_murl_e="([^"]*)"""")
        private val CHAPTER_ID_REGEX = Regex("""var qTcms_S_p_id="(\d+)"""")
        private val MANGA_ID_REGEX = Regex("""var qTcms_S_m_id="(\d+)"""")
        private val PROXY_MODE_REGEX = Regex("""var qTcms_Pic_m_if="([^"]*)"""")
        private val MHTTP_REGEX = Regex("""var qTcms_S_m_mhttpurl="([^"]*)"""")
    }
}
