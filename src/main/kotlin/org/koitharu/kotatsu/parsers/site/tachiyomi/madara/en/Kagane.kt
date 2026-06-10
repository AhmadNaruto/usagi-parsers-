package org.koitharu.kotatsu.parsers.site.tachiyomi.madara.en

import eu.kanade.tachiyomi.network.interceptor.rateLimit
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.parsers.TachiyomiSource
import org.koitharu.kotatsu.parsers.site.tachiyomi.madara.Madara

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.brotli.BrotliInterceptor
import okio.IOException
import rx.Observable

@TachiyomiSource("KAGANE_EN", "Kagane_EN", "en")
abstract class Kagane_EN : HttpSource(), ConfigurableSource {

    override val name = "Kagane"

    private val domain = "kagane.to"
    private val apiUrl = "https://yuzuki.$domain"
    override val baseUrl = "https://$domain"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val client = network.client.newBuilder()
        .addInterceptor(::refreshTokenInterceptor)
        .apply {
            val index = networkInterceptors().indexOfFirst { it is BrotliInterceptor }
            if (index >= 0) interceptors().add(networkInterceptors().removeAt(index))
        }
        .build()

    private fun refreshTokenInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (!url.queryParameterNames.contains("token")) {
            return chain.proceed(request)
        }

        val chapterId = url.pathSegments[4]

        var response = chain.proceed(
            request.newBuilder()
                .url(url.newBuilder().setQueryParameter("token", accessToken).build())
                .build(),
        )

        if (response.code == 401 || response.code == 403 || response.code == 507) {
            response.close()

            val challenge = getChallengeResponse(chapterId)
            accessToken = challenge.accessToken
            cacheUrl = challenge.cacheUrl

            response = chain.proceed(
                request.newBuilder()
                    .url(url.newBuilder().setQueryParameter("token", accessToken).build())
                    .build(),
            )
        }

        return response
    }

    override fun popularMangaRequest(page: Int) = searchMangaRequest(
        page,
        "",
        FilterList(
            SortFilter(Filter.Sort.Selection(1, false)),
            ContentRatingFilter(preferences.contentRating.toSet()),
            GenresFilter(emptyList()),
        ),
    )

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int) = searchMangaRequest(
        page,
        "",
        FilterList(
            SortFilter(Filter.Sort.Selection(6, false)),
            ContentRatingFilter(preferences.contentRating.toSet()),
            GenresFilter(emptyList()),
        ),
    )

    override fun latestUpdatesParse(response: Response) = searchMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val body = buildJsonObject {
            if (query.isNotBlank()) put("title", query)

            putJsonArray("content_lang") {
                add("en")
            }

            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> {
                        val sortParam = filter.toUriPart()
                        if (sortParam.isNotEmpty()) {
                            put("sort", sortParam)
                        }
                    }
                    else -> {}
                }
            }
        }.toJsonString()
            .toRequestBody("application/json".toMediaType())

        val url = "$apiUrl/api/v2/search/series".toHttpUrl()
            .newBuilder()
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("size", "35")
            .build()

        return POST(url.toString(), headers, body)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<SearchDto>()
        val sources = getSourcesMap()
        val mangas = dto.content.map {
            it.toSManga(apiUrl, preferences.showSource, sources)
        }
        return MangasPage(mangas, dto.hasNextPage())
    }

    private fun getSourcesMap(): Map<String, String> {
        return try {
            getSourcesResponse().use { resp ->
                if (!resp.isSuccessful) return emptyMap()
                resp.parseAs<SourcesDto>().sources
                    .associate { it.sourceId to it.title }
            }
        } catch (e: Exception) {
            Log.w(name, "Failed to load sources", e)
            emptyMap()
        }
    }

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$apiUrl/api/v2/series/${manga.url}", apiHeaders)

    override fun mangaDetailsParse(response: Response): SManga {
        val dto = response.parseAs<DetailsDto>()
        return dto.toSManga(apiUrl, null, baseUrl, false, false)
    }

    override fun chapterListRequest(manga: SManga): Request =
        GET("$apiUrl/api/v2/series/${manga.url}", apiHeaders)

    override fun chapterListParse(response: Response): List<SChapter> {
        val dto = response.parseAs<DetailsDto>()
        return dto.seriesBooks.map { it.toSChapter() }.reversed()
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val chapterId = "$baseUrl${chapter.url}".toHttpUrl().pathSegments.last()
        val challenge = getChallengeResponse(chapterId)

        accessToken = challenge.accessToken
        cacheUrl = challenge.cacheUrl

        val pages = challenge.manifest?.pages.orEmpty().map { page ->
            val url = "$cacheUrl/api/v2/books/page"
                .toHttpUrl()
                .newBuilder()
                .addPathSegment(chapterId)
                .addPathSegment("${page.pageUuid}.${page.ext ?: "jxl"}")
                .addQueryParameter("token", accessToken)
                .build()
                .toString()

            Page(page.pageNumber, url, url)
        }

        return Observable.just(pages)
    }

    override fun imageUrlRequest(page: Page): Request =
        GET(page.imageUrl!!, apiHeaders)

    override fun pageListParse(response: Response): List<Page> =
        throw UnsupportedOperationException()

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    private var cacheUrl = "https://akari.$domain"
    private var accessToken: String = ""

    private fun getChallengeResponse(chapterId: String): ChallengeDto {
        val url = "$apiUrl/api/v2/books/$chapterId"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("is_datasaver", preferences.dataSaver.toString())
            .build()

        val resp = client.newCall(
            POST(url.toString(), apiHeaders, "{}".toRequestBody("application/json".toMediaType()))
        ).execute()

        if (!resp.isSuccessful) {
            throw IOException("Challenge failed ${resp.code}")
        }

        return resp.parseAs()
    }

    private val apiHeaders = headers.newBuilder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")
        .build()

    private val SharedPreferences.contentRating: List<String>
        get() = listOf("safe", "suggestive", "erotica", "pornographic")

    private val SharedPreferences.showSource: Boolean
        get() = false

    private val SharedPreferences.dataSaver: Boolean
        get() = false
}