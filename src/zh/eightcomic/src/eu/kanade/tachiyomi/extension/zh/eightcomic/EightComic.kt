package eu.kanade.tachiyomi.extension.zh.eightcomic

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.asJsoup
import keiyoushi.utils.tryParseDate
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import rx.Observable
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Source
abstract class EightComic : HttpSource() {
    override val supportsLatest = true

    // Tachimanga requires the 1.4 callbacks. The reader also requires an 8comic Referer.
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .removeAll("Origin")
        .set("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/comic/h-$page.html", headers)

    override fun popularMangaParse(response: Response): MangasPage = mangaList(response.asJsoup())

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/comic/u-$page.html", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val url = query.trim().toHttpUrlOrNull()
        if (url != null && url.host in setOf(baseUrl.toHttpUrl().host, baseUrl.toHttpUrl().host.removePrefix("www."), READER_URL.toHttpUrl().host)) {
            val id = MANGA_PATH.matchEntire(url.encodedPath)?.groupValues?.get(1)
                ?: READER_PATH.matchEntire(url.encodedPath)?.groupValues?.get(1)
                ?: return Observable.just(MangasPage(emptyList(), false))
            val manga = SManga.create().apply { this.url = "/html/$id.html" }
            return fetchMangaDetails(manga).map { details ->
                details.url = manga.url
                details.initialized = true
                MangasPage(listOf(details), false)
            }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) return popularMangaRequest(page)
        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("key", query)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    private fun mangaList(document: Document): MangasPage {
        val mangas = document.select("a.comicpic_col6[href^=/html/], .cat2_list > a[href^=/html/]").map { anchor ->
            SManga.create().apply {
                setUrlWithoutDomain(anchor.absUrl("href"))
                title = anchor.selectFirst(".comicpic_col6_name, .cat2_list_name")!!.text()
                thumbnail_url = anchor.selectFirst("img")?.absUrl("src")
            }
        }.distinctBy { it.url }
        return MangasPage(mangas, document.selectFirst(".pager a:has(.mdi-skip-next)") != null)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga = mangaDetails(response.asJsoup())

    private fun mangaDetails(document: Document): SManga = SManga.create().apply {
        val id = document.selectFirst("meta[name=id]")!!.attr("content")
        url = "/html/$id.html"
        title = document.selectFirst(".item_content_box > .h2")!!.text()
        thumbnail_url = document.selectFirst(".item-cover img")?.absUrl("src")
        author = document.selectFirst(".item-info-author")?.text()?.substringAfter(':')?.trim()
        genre = document.select(".item-topbar a[href^=/comic/]").joinToString { it.text() }
        description = document.selectFirst(".item_info_detail")?.ownText()
        status = when (document.selectFirst(".item_comic_eps + .item-info-status")?.text()) {
            "連載中" -> SManga.ONGOING
            "已完結", "完結" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> = chapterList(response.asJsoup())

    private fun chapterList(document: Document): List<SChapter> {
        val result = document.select("#chapters a[onclick*=cview]").map { anchor ->
            val match = CHAPTER_LINK.find(anchor.attr("onclick"))!!
            SChapter.create().apply {
                url = "/online/new-${match.groupValues[1]}.html?ch=${match.groupValues[2]}"
                name = anchor.text()
            }
        }.distinctBy { it.url }.asReversed()
        result.firstOrNull()?.date_upload = DATE_FORMAT.tryParseDate(
            document.selectFirst(".item-info-date")?.text(),
            ZoneId.of("Asia/Taipei"),
        )
        return result
    }

    override fun getChapterUrl(chapter: SChapter): String = READER_URL + chapter.url

    override fun pageListRequest(chapter: SChapter): Request = GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val chapter = response.request.url.queryParameter("ch")!!
        val document = response.asJsoup()
        val script = document.selectFirst("script:containsData(var chs=)")?.data() ?: return emptyList()
        val id = document.selectFirst("meta[name=itemid]")!!.attr("content")
        return Reader.imageUrls(script, id, chapter).mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    companion object {
        private const val READER_URL = "https://articles.onemoreplace.tw"
        private val MANGA_PATH = Regex("/html/(\\d+)\\.html")
        private val READER_PATH = Regex("/online/new-(\\d+)\\.html")
        private val CHAPTER_LINK = Regex("""cview\(['"](\d+)-(\d+[a-z]?)\.html['"]""")
        private val DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
