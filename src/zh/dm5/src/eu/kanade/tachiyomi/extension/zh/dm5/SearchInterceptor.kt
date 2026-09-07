package eu.kanade.tachiyomi.extension.zh.dm5

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

object SearchInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        if (!originalRequest.url.pathSegments.contains("search")) {
            return chain.proceed(originalRequest)
        }

        var response = chain.proceed(originalRequest)
        if (response.code != 404) return response

        // DM5 returns HTTP 404 instead of an empty result page. Full titles containing
        // punctuation also fail even when a shorter title segment has matching results.
        val originalTitle = originalRequest.url.queryParameter("title").orEmpty()
        val fallbackTitles = originalTitle
            .split(QUERY_SEPARATOR_REGEX)
            .map { it.trim() }
            .filter { it.length >= 2 && it != originalTitle }
            .distinct()
            .sortedByDescending { it.length }

        for (fallbackTitle in fallbackTitles) {
            response.close()
            val fallbackUrl = originalRequest.url.newBuilder()
                .setQueryParameter("title", fallbackTitle)
                .build()
            response = chain.proceed(originalRequest.newBuilder().url(fallbackUrl).build())
            if (response.code != 404) return response
        }

        // Treat a genuine no-result response as an empty search page so clients such as
        // Tachimanga do not surface DM5's misleading HTTP 404 error.
        response.close()
        return response.newBuilder()
            .code(200)
            .message("OK")
            .body("".toResponseBody("text/html".toMediaType()))
            .build()
    }

    private val QUERY_SEPARATOR_REGEX = Regex("""[\p{P}\p{Z}\s]+""")
}
