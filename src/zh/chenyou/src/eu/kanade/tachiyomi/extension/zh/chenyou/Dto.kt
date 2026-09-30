package eu.kanade.tachiyomi.extension.zh.chenyou

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import java.io.IOException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
class ApiStatus(val code: Int, val msg: String)

@Serializable
class ApiResponse<T>(val data: T)

@Serializable
class GuestLogin(@SerialName("token_info") val tokenInfo: GuestToken)

@Serializable
class GuestToken(val token: String, private val expire: Long) {
    @Transient
    private val validUntil = System.currentTimeMillis() / 1000 + expire - 60

    fun isValid(): Boolean = System.currentTimeMillis() / 1000 < validUntil
}

@Serializable
class DeviceRequest(val uuid: String, @SerialName("app_version") val appVersion: String)

@Serializable
class PageRequest(val page: Int)

@Serializable
class BrowseRequest(val page: Int, @SerialName("order_by") val orderBy: Int)

@Serializable
class SearchRequest(val page: Int, val keyword: String)

@Serializable
class BookRequest(@SerialName("book_id") val bookId: Int)

@Serializable
class ChapterRequest(@SerialName("book_id") val bookId: Int, @SerialName("chapter_id") val chapterId: Int)

@Serializable
class BookList(
    private val data: List<Book>,
    private val page: Int,
    @SerialName("last_page") private val lastPage: Int,
) {
    fun toMangasPage(): MangasPage = MangasPage(data.map { it.toSManga() }, page < lastPage)
}

@Serializable
class LatestList(
    private val page: Int,
    @SerialName("last_page") private val lastPage: Int,
    private val list: List<UpdateDay>,
) {
    fun toMangasPage(): MangasPage = MangasPage(
        list.flatMap { it.data }.map { it.toSManga() }.distinctBy { it.url },
        page < lastPage,
    )
}

@Serializable
class UpdateDay(val data: List<Book>)

@Serializable
class Book(
    private val id: Int? = null,
    @SerialName("book_id") private val bookId: Int? = null,
    @SerialName("book_name") private val bookName: String,
    @SerialName("vertical_img") private val verticalImg: String? = null,
    private val author: String? = null,
    private val brief: String? = null,
    @SerialName("end_status") private val endStatus: Int? = null,
    @SerialName("class_name") private val className: String? = null,
    @SerialName("category_name") private val categoryName: String? = null,
    @SerialName("tag_name_str") private val tagName: String? = null,
) {
    fun toSManga(): SManga = SManga.create().apply {
        url = requireNotNull(bookId ?: id) { "尘柚漫画缺少书籍编号" }.toString()
        title = bookName
        thumbnail_url = verticalImg?.replaceFirst("http://", "https://")
        author = this@Book.author
        description = brief
        genre = listOfNotNull(className, categoryName, tagName).filter { it.isNotBlank() }.distinct().joinToString()
        status = when (endStatus) {
            0 -> SManga.ONGOING
            1 -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class ChapterList(val list: List<Chapter>)

@Serializable
class Chapter(
    private val id: Int,
    @SerialName("chapter_name") private val chapterName: String?,
    @SerialName("chapter_num") val chapterNum: Float,
    @SerialName("create_time") private val createTime: String? = null,
) {
    fun toSChapter(bookId: Int): SChapter = SChapter.create().apply {
        url = "$bookId/$id"
        name = chapterName?.takeIf { it.isNotBlank() } ?: "第${chapterNum.toString().removeSuffix(".0")}话"
        chapter_number = chapterNum
        date_upload = DATE_FORMAT.tryParseDate(createTime, TIME_ZONE)
    }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val TIME_ZONE = ZoneId.of("Asia/Shanghai")
    }
}

@Serializable
class ChapterContent(
    private val key: String? = null,
    @SerialName("encrypt_content") private val encryptContent: String? = null,
) {
    fun toPages(): List<Page> {
        val encrypted = encryptContent?.decodeBase64()?.toByteArray()
            ?: throw IOException("尘柚漫画没有返回章节图片")
        val secret = key?.decodeHex()?.toByteArray()
            ?: throw IOException("尘柚漫画没有返回章节密钥")
        // The response includes its own key and a 16-byte IV prefix, even on ad-gated chapters.
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), IvParameterSpec(encrypted, 0, 16))
        val content = String(cipher.doFinal(encrypted, 16, encrypted.size - 16), Charsets.UTF_8)
        return content.split(',').filter { it.isNotBlank() }.mapIndexed { index, url ->
            Page(index, imageUrl = url.trim().replaceFirst("http://", "https://"))
        }
    }
}
