package eu.kanade.tachiyomi.extension.zh.chenyou

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.UUID

@Source
abstract class Chenyou : HttpSource() {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private val deviceId by lazy {
        preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("device_id", it).apply()
        }
    }

    private val sessionLock = Any()
    private var session: GuestToken? = null

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val original = chain.request()
            if (original.url.host != API_HOST) return@addInterceptor chain.proceed(original)

            val token = guestToken(chain)
            var response = chain.proceed(authenticate(original, token))
            if (!response.isSuccessful) return@addInterceptor response
            var status = response.apiStatus()
            if (status.code == 204) {
                response.close()
                synchronized(sessionLock) {
                    if (session === token) session = null
                }
                response = chain.proceed(authenticate(original, guestToken(chain)))
                if (!response.isSuccessful) return@addInterceptor response
                status = response.apiStatus()
            }
            if (status.code != 200) {
                response.close()
                throw IOException("尘柚漫画：${status.msg}")
            }
            response
        }
        .build()

    private fun authenticate(request: Request, token: GuestToken): Request = request.newBuilder()
        .header("uuid", deviceId)
        .header("version", APP_VERSION)
        .header("token", token.token)
        .build()

    private fun guestToken(chain: Interceptor.Chain): GuestToken = synchronized(sessionLock) {
        session?.takeIf { it.isValid() }?.let { return@synchronized it }
        val request = POST(
            "$API_URL/login/device",
            headers.newBuilder()
                .set("uuid", deviceId)
                .set("version", APP_VERSION)
                .build(),
            DeviceRequest(deviceId, APP_VERSION).toJsonRequestBody(),
        )
        val response = chain.proceed(request)
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("尘柚访客登录失败：HTTP $code")
        }
        val status = response.apiStatus()
        if (status.code != 200) {
            response.close()
            throw IOException("尘柚访客登录失败：${status.msg}")
        }
        response.readData<GuestLogin>().tokenInfo.also { session = it }
    }

    override fun popularMangaRequest(page: Int): Request = POST(
        "$API_URL/book/list",
        headers,
        BrowseRequest(page = page, orderBy = 1).toJsonRequestBody(),
    )

    override fun popularMangaParse(response: Response): MangasPage = response.readData<BookList>().toMangasPage()

    override fun latestUpdatesRequest(page: Int): Request = POST(
        "$API_URL/book/update/list",
        headers,
        PageRequest(page).toJsonRequestBody(),
    )

    override fun latestUpdatesParse(response: Response): MangasPage = response.readData<LatestList>().toMangasPage()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) return popularMangaRequest(page)
        return POST(
            "$API_URL/home/search?type=book",
            headers,
            SearchRequest(page, query).toJsonRequestBody(),
        )
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request = POST(
        "$API_URL/book/detail",
        headers,
        BookRequest(manga.url.toInt()).toJsonRequestBody(),
    )

    override fun mangaDetailsParse(response: Response): SManga = response.readData<Book>().toSManga()

    override fun chapterListRequest(manga: SManga): Request = POST(
        "$API_URL/chapter/catalog",
        headers,
        BookRequest(manga.url.toInt()).toJsonRequestBody(),
    )

    override fun chapterListParse(response: Response): List<SChapter> {
        val buffer = Buffer()
        response.request.body!!.writeTo(buffer)
        val bookId = buffer.readUtf8().parseAs<BookRequest>().bookId
        return response.readData<ChapterList>().list
            .sortedByDescending { it.chapterNum }
            .map { it.toSChapter(bookId) }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val (bookId, chapterId) = chapter.url.split('/')
        return POST(
            "$API_URL/chapter/detail",
            headers,
            ChapterRequest(bookId.toInt(), chapterId.toInt()).toJsonRequestBody(),
        )
    }

    override fun pageListParse(response: Response): List<Page> = response.readData<ChapterContent>().toPages()

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    override fun imageUrlParse(response: Response): String = response.request.url.toString()

    // There is no public web reader; the homepage opens the official app download page.
    override fun getMangaUrl(manga: SManga): String = baseUrl

    override fun getChapterUrl(chapter: SChapter): String = baseUrl

    companion object {
        private const val API_HOST = "api.chenyouapp.com"
        private const val API_URL = "https://$API_HOST/v43"
        private const val APP_VERSION = "1.88.7.0"
    }
}

private fun Response.apiStatus(): ApiStatus = try {
    peekBody(Long.MAX_VALUE).string().parseAs<ApiStatus>()
} catch (exception: Exception) {
    close()
    throw exception
}

// String decoding avoids a dependency on the newer JSON/Okio stream decoder in Tachimanga.
private inline fun <reified T> Response.readData(): T = parseAs<ApiResponse<T>> { it }.data
