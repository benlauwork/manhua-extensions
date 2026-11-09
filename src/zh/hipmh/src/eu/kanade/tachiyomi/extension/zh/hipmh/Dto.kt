package eu.kanade.tachiyomi.extension.zh.hipmh

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal class ApiResponse<T>(val data: T)

@Serializable
internal class SearchResults(
    val data: List<SearchItem>,
    @SerialName("total_pages") val totalPages: Int,
)

@Serializable
internal class SearchItem(
    val id: String,
    val title: String,
    @SerialName("vertical_image_url") private val verticalImageUrl: String? = null,
    @SerialName("cover_image_url") private val coverImageUrl: String? = null,
) {
    val cover get() = verticalImageUrl?.takeIf(String::isNotBlank) ?: coverImageUrl
}

@Serializable
internal class ChapterList(
    val items: List<ChapterItem>,
    @SerialName("total_pages") val totalPages: Int,
)

@Serializable
internal class ChapterItem(
    val hid: String,
    val title: String? = null,
    @SerialName("chapter_number") val chapterNumber: Float? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
internal class ChapterImages(
    val images: JsonElement,
    val line: Int? = null,
    @SerialName("order_id") val orderId: Long? = null,
    val sid: Long? = null,
)
