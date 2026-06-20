package org.koitharu.kotatsu.parsers.site.id

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.*

@MangaSourceParser("KOMIKASO", "Komikaso", "id")
internal class Komikaso(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KOMIKASO, pageSize = 20),
	MangaParserAuthProvider,
	Interceptor {

	override val authUrl: String
		get() = "https://$domain/"

	override suspend fun isAuthorized(): Boolean {
		return context.cookieJar.getCookies(domain).any {
			it.name == "comicaso_public_sid"
		}
	}

	override suspend fun getUsername(): String {
		val url = "https://$domain/api/me.php".toHttpUrl()
		val response = webClient.httpGet(url)
		if (!response.isSuccessful) {
			response.close()
			throw AuthRequiredException(source)
		}
		val json = response.parseJson()
		if (!json.getBooleanOrDefault("authenticated", false)) {
			throw AuthRequiredException(source)
		}
		val data = json.optJSONObject("data") ?: throw AuthRequiredException(source)
		return data.optString("name").takeIf { it.isNotBlank() } ?: "User"
	}

	override val configKeyDomain: ConfigKey.Domain
		get() = ConfigKey.Domain("v3.comicaso.pro")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = false,
			isSearchWithFiltersSupported = true,
		)

	private val availableTags = suspendLazy(initializer = ::fetchAvailableTags)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags.get(),
		availableStates = EnumSet.of(MangaState.FINISHED, MangaState.ONGOING),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
		availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val offset = (page - 1) * pageSize
		val params = HttpUrl.Builder()
			.scheme("https")
			.host(domain)
			.encodedPath("/api/home.php")
			.addQueryParameter("limit", pageSize.toString())
			.addQueryParameter("offset", offset.toString())

		if (!filter.query.isNullOrEmpty()) {
			params.addQueryParameter("q", filter.query)
		} else {
			params.addQueryParameter("q", "")
		}

		var sourceQuery = "all"
		val tag = filter.tags.oneOrThrowIfMany()
		if (tag != null) {
			if (tag.key.startsWith("source:")) {
				sourceQuery = tag.key.substringAfter("source:")
				params.addQueryParameter("genre", "")
			} else {
				params.addQueryParameter("genre", tag.key)
			}
		} else {
			params.addQueryParameter("genre", "")
		}

		val contentRatingFilter = filter.contentRating.oneOrThrowIfMany()
		if (contentRatingFilter != null) {
			sourceQuery = when (contentRatingFilter) {
				ContentRating.SAFE -> "comicazen"
				ContentRating.ADULT -> "medusa"
				else -> sourceQuery
			}
		}
		params.addQueryParameter("source", sourceQuery)

		val type = filter.types.oneOrThrowIfMany()
		val typeStr = when (type) {
			ContentType.MANGA -> "manga"
			ContentType.MANHWA -> "manhwa"
			ContentType.MANHUA -> "manhua"
			else -> "all"
		}
		params.addQueryParameter("type", typeStr)

		val state = filter.states.oneOrThrowIfMany()
		val mode = when {
			state == MangaState.FINISHED -> "completed"
			order == SortOrder.NEWEST -> "new"
			else -> "update"
		}
		params.addQueryParameter("mode", mode)

		val response = webClient.httpGet(params.build()).parseJson()
		val dataArray = response.optJSONArray("data") ?: JSONArray()
		var mangas = (0 until dataArray.length()).map { i ->
			val jo = dataArray.getJSONObject(i)
			val slug = jo.getString("slug")
			val title = jo.getString("title")
			val thumbnail = jo.optString("thumbnail")
			val status = jo.optString("status")
			val mangaSource = jo.optString("source")

			val genresSet = jo.optJSONArray("genres")?.toStringSet() ?: emptySet()
			val isNsfw = mangaSource == "medusa" || genresSet.any {
				it.equals("mature", ignoreCase = true) ||
					it.equals("smut", ignoreCase = true) ||
					it.equals("yaoi(bl)", ignoreCase = true) ||
					it.equals("yaoi", ignoreCase = true)
			}

			val relativeUrl = "/komik/$slug?source=$mangaSource"

			Manga(
				id = generateUid(relativeUrl),
				url = relativeUrl,
				publicUrl = "https://$domain/komik/$slug/",
				title = title,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				contentRating = if (isNsfw) ContentRating.ADULT else ContentRating.SAFE,
				coverUrl = thumbnail,
				tags = emptySet(),
				state = when (status) {
					"on-going" -> MangaState.ONGOING
					"end" -> MangaState.FINISHED
					else -> null
				},
				authors = jo.optString("author").takeIf { it.isNotBlank() }?.let { setOf(it) } ?: emptySet(),
				source = source,
			)
		}

		if (state == MangaState.ONGOING) {
			mangas = mangas.filter { it.state == MangaState.ONGOING }
		}

		return mangas
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val relativePath = manga.url.let { if (it.startsWith("/")) it else "/$it" }
		val httpUrl = "https://$domain$relativePath".toHttpUrl()
		val slug = httpUrl.pathSegments.lastOrNull { it.isNotEmpty() } ?: ""
		val sourceQuery = httpUrl.queryParameter("source")

		val data = getMangaData(slug, sourceQuery)
		val mangaSource = data.getString("manga_source_name")

		val title = data.getString("title")
		val synopsis = data.optString("synopsis").takeIf { it.isNotBlank() }
		val alternative = data.optString("alternative").takeIf { it.isNotBlank() }

		val description = buildString {
			if (synopsis != null) append(synopsis)
			if (alternative != null) {
				if (isNotEmpty()) append("\n\n")
				append("Alternative: $alternative")
			}
		}.trim().takeIf { it.isNotEmpty() }

		val status = data.optString("status")
		val state = when (status) {
			"on-going" -> MangaState.ONGOING
			"end" -> MangaState.FINISHED
			else -> null
		}

		val genresArray = data.optJSONArray("genres")
		val tags = if (genresArray != null) {
			val result = LinkedHashSet<MangaTag>(genresArray.length())
			for (i in 0 until genresArray.length()) {
				val genre = genresArray.getString(i)
				result.add(
					MangaTag(
						key = genre.lowercase(Locale.ROOT),
						title = genre,
						source = source,
					)
				)
			}
			result
		} else {
			emptySet()
		}

		val author = data.optString("author").takeIf { it.isNotBlank() }
		val artist = data.optString("artist").takeIf { it.isNotBlank() }
		val authors = setOfNotNull(
			author,
			artist?.takeIf { it != author }
		)

		val isNsfw = mangaSource == "medusa" || tags.any {
			val lower = it.title.lowercase(Locale.ROOT)
			lower == "mature" || lower == "smut" || lower == "yaoi" || lower == "adult" || lower == "ecchi" || lower == "yaoi(bl)"
		}
		val contentRating = if (isNsfw) ContentRating.ADULT else ContentRating.SAFE

		val chaptersArray = data.optJSONArray("chapters") ?: JSONArray()
		val chapters = ArrayList<MangaChapter>(chaptersArray.length())
		for (i in 0 until chaptersArray.length()) {
			val ch = chaptersArray.getJSONObject(i)
			val chSlug = ch.getString("slug")
			val chTitle = ch.getString("title")
			val chDate = ch.optLong("date").takeIf { it != 0L }?.times(1000L) ?: 0L
			val token = ch.optString("chapter_token")

			val chUrl = "/komik/$slug/$chSlug?source=$mangaSource" + if (token.isNotEmpty()) "&token=$token" else ""

			chapters.add(
				MangaChapter(
					id = generateUid(chUrl),
					title = chTitle,
					number = extractChapterNumber(chTitle),
					volume = 0,
					url = chUrl,
					scanlator = null,
					uploadDate = chDate,
					branch = null,
					source = source,
				)
			)
		}

		return manga.copy(
			title = title,
			description = description,
			coverUrl = data.optString("thumbnail").takeIf { it.isNotBlank() } ?: manga.coverUrl,
			tags = tags,
			state = state,
			authors = authors,
			contentRating = contentRating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val relativePath = chapter.url.let { if (it.startsWith("/")) it else "/$it" }
		val httpUrl = "https://$domain$relativePath".toHttpUrl()

		val slug = httpUrl.pathSegments.getOrNull(1) ?: ""
		val chSlug = httpUrl.pathSegments.getOrNull(2) ?: ""
		val mangaSource = httpUrl.queryParameter("source") ?: "comicazen"
		val token = httpUrl.queryParameter("token")

		val params = HttpUrl.Builder()
			.scheme("https")
			.host(domain)
			.encodedPath("/api/chapter.php")
			.addQueryParameter("source", mangaSource)
			.addQueryParameter("manga", slug)
			.addQueryParameter("chapter", chSlug)
			.addQueryParameter("platform", "web")

		if (!token.isNullOrEmpty()) {
			params.addQueryParameter("token", token)
		}

		val response = webClient.httpGet(params.build())
		if (response.code == 403) {
			val json = response.parseJson()
			if (json.getBooleanOrDefault("locked", false) || json.optString("message").contains("Login wajib")) {
				throw AuthRequiredException(source)
			}
			throw Exception(json.optString("message").ifEmpty { "HTTP 403 Forbidden" })
		}
		if (!response.isSuccessful) {
			response.close()
			throw Exception("HTTP ${response.code}")
		}

		val json = response.parseJson()
		val chapterObj = json.optJSONObject("data") ?: json.optJSONObject("chapter") ?: json
		val imagesArray = chapterObj.optJSONArray("images") ?: JSONArray()

		val list = ArrayList<MangaPage>(imagesArray.length())
		for (i in 0 until imagesArray.length()) {
			val url = imagesArray.getString(i)
			list.add(
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			)
		}
		return list
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val newRequest = request.newBuilder()
			.removeHeader("Referer")
			.addHeader("Referer", "https://$domain/")
			.apply {
				if (request.header("User-Agent").isNullOrEmpty()) {
					removeHeader("User-Agent")
					addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
				}
			}
			.build()
		return chain.proceed(newRequest)
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val url = "https://$domain/api/genres.php?source=all".toHttpUrl()
		val json = webClient.httpGet(url).parseJson()
		val jsonArray = json.optJSONArray("data") ?: JSONArray()
		val tags = LinkedHashSet<MangaTag>()
		tags.add(MangaTag(key = "source:all", title = "Semua (All)", source = source))
		tags.add(MangaTag(key = "source:comicazen", title = "Comicazen (Umum)", source = source))
		tags.add(MangaTag(key = "source:medusa", title = "Medusa (Dewasa)", source = source))
		jsonArray.mapJSONTo(tags) { jo ->
			val key = jo.getString("genre_slug")
			val title = jo.getString("genre")
			MangaTag(key = key, title = title, source = source)
		}
		return tags
	}

	private suspend fun getMangaData(slug: String, sourceName: String?): JSONObject {
		val sources = if (sourceName != null) {
			listOf(sourceName)
		} else {
			listOf("comicazen", "medusa")
		}

		for (src in sources) {
			val url = "https://$domain/api/manga.php?source=$src&slug=$slug&platform=web".toHttpUrl()
			val response = webClient.httpGet(url)
			if (response.isSuccessful) {
				try {
					val json = response.parseJson()
					if (json.getBooleanOrDefault("ok", false)) {
						val data = json.optJSONObject("data") ?: json
						data.put("manga_source_name", src)
						return data
					}
				} catch (_: Exception) {
					// try next source
				}
			} else {
				response.close()
			}
		}
		throw Exception("Manga tidak ditemukan")
	}

	private fun extractChapterNumber(title: String): Float {
		return Regex("""[\d]+(?:[.,]\d+)?""").find(title)
			?.value?.replace(',', '.')?.toFloatOrNull() ?: 0f
	}
}
