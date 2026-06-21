package org.koitharu.kotatsu.parsers.site.id

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("KANZENIN", "Kanzenin", "id")
internal class Kanzenin(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KANZENIN, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("kanzenin.info")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isSearchWithFiltersSupported = true,
    )

    private val availableTags = KANZENIN_GENRES.map { (id, title) ->
        MangaTag(
            key = id,
            title = title,
            source = source,
        )
    }.toSet()

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = availableTags,
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
            ContentType.COMICS,
        )
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            val query = filter.queryOrNull
            if (!query.isNullOrEmpty()) {
                append("/page/")
                append(page)
                append("/?s=")
                append(query.urlEncoded())
            } else {
                append("/manga/page/")
                append(page)
                append("/")

                val params = ArrayList<String>()

                if (filter.tags.isNotEmpty()) {
                    for (tag in filter.tags) {
                        params.add("genre%5B%5D=${tag.key}")
                    }
                }

                val status = filter.states.oneOrThrowIfMany()
                if (status != null) {
                    params.add("status=" + when (status) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "completed"
                        MangaState.PAUSED -> "hiatus"
                        else -> ""
                    })
                }

                val contentType = filter.types.oneOrThrowIfMany()
                if (contentType != null) {
                    params.add("type=" + when (contentType) {
                        ContentType.MANGA -> "manga"
                        ContentType.MANHWA -> "manhwa"
                        ContentType.MANHUA -> "manhua"
                        ContentType.COMICS -> "comic"
                        else -> ""
                    })
                }

                val sortOrder = when (order) {
                    SortOrder.POPULARITY -> "popular"
                    SortOrder.NEWEST -> "latest"
                    SortOrder.UPDATED -> "update"
                    SortOrder.ALPHABETICAL -> "title"
                    SortOrder.ALPHABETICAL_DESC -> "titlereverse"
                    else -> ""
                }
                if (sortOrder.isNotEmpty()) {
                    params.add("order=$sortOrder")
                }

                if (params.isNotEmpty()) {
                    append("?")
                    append(params.joinToString("&"))
                }
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        return doc.select(".bsx").map { element ->
            val a = element.selectFirstOrThrow("a")
            val href = a.attrAsRelativeUrl("href")
            val coverUrl = element.selectFirst("img")?.let { img ->
                img.attrAsAbsoluteUrlOrNull("data-src")
                    ?: img.attrAsAbsoluteUrlOrNull("src")
            }
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = element.selectFirstOrThrow(".tt").text().trim(),
                altTitles = emptySet(),
                coverUrl = coverUrl,
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                state = null,
                authors = emptySet(),
                source = source,
                tags = emptySet<MangaTag>(),
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: manga.title
        val description = doc.selectFirst(".entry-content[itemprop=description]")?.text()?.trim()

        val coverUrl = doc.selectFirst(".thumb img")?.let { img ->
            img.attrAsAbsoluteUrlOrNull("data-src")
                ?: img.attrAsAbsoluteUrlOrNull("src")
        } ?: manga.coverUrl

        var author: String? = null
        var artist: String? = null
        var statusText: String? = null

        val infoTable = doc.select(".infotable tr")
        for (row in infoTable) {
            val label = row.selectFirst("td:eq(0)")?.text()?.lowercase(Locale.ENGLISH)?.trim()
            val value = row.selectFirst("td:eq(1)")?.text()?.trim()
            if (label != null && value != null) {
                when {
                    label.contains("author") -> author = value.takeIf { it != "N/A" && it.isNotEmpty() }
                    label.contains("artist") -> artist = value.takeIf { it != "N/A" && it.isNotEmpty() }
                    label.contains("status") -> statusText = value.lowercase(Locale.ENGLISH)
                }
            }
        }

        val state = when {
            statusText?.contains("ongoing") == true -> MangaState.ONGOING
            statusText?.contains("completed") == true -> MangaState.FINISHED
            statusText?.contains("hiatus") == true -> MangaState.PAUSED
            else -> null
        }

        val tags = doc.select(".seriestugenre a").mapToSet { tagEl ->
            MangaTag(
                key = tagEl.attr("href").removeSuffix("/").substringAfterLast("/"),
                title = tagEl.text().trim(),
                source = source,
            )
        }

        val chapters = doc.select("#chapterlist li").mapChapters(reversed = true) { index, li ->
            val a = li.selectFirstOrThrow(".eph-num a")
            val href = a.attrAsRelativeUrl("href")
            val titleElement = a.selectFirst(".chapternum") ?: a
            val dateText = a.selectFirst(".chapterdate")?.text()?.trim().orEmpty()

            MangaChapter(
                id = generateUid(href),
                title = titleElement.text().trim(),
                number = extractChapterNumber(titleElement.text(), index + 1f),
                volume = 0,
                url = href,
                uploadDate = parseDate(dateText),
                scanlator = null,
                branch = null,
                source = source,
            )
        }

        return manga.copy(
            title = title,
            description = description,
            coverUrl = coverUrl,
            state = state,
            authors = setOfNotNull(author, artist?.takeIf { it != author }),
            tags = tags,
            chapters = chapters,
            contentRating = ContentRating.ADULT,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

        // 1. Try to extract from ts_reader script
        val script = doc.select("script").firstOrNull { it.data().contains("ts_reader.run") }
        if (script != null) {
            val jsonStr = script.data().substringAfter("ts_reader.run(").substringBeforeLast(")").trim()
            if (jsonStr.isNotEmpty()) {
                try {
                    val json = JSONObject(jsonStr)
                    val sources = json.optJSONArray("sources")
                    if (sources != null && sources.length() > 0) {
                        val firstSource = sources.getJSONObject(0)
                        val images = firstSource.optJSONArray("images")
                        if (images != null && images.length() > 0) {
                            return (0 until images.length()).map { i ->
                                val url = images.getString(i)
                                MangaPage(
                                    id = generateUid(url),
                                    url = url,
                                    preview = null,
                                    source = source,
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Fallback to HTML parsing if JSON parsing fails
                }
            }
        }

        // 2. Fallback to parsing div#readerarea img
        val imgs = doc.select("#readerarea img")
        if (imgs.isNotEmpty()) {
            return imgs.map { img ->
                val url = img.attrAsAbsoluteUrlOrNull("data-src")
                    ?: img.attrAsAbsoluteUrl("src")
                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
        }

        throw Exception("Images not found")
    }

    private fun extractChapterNumber(title: String, fallback: Float): Float {
        return Regex("""Chapter\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(title)
            ?.groupValues?.getOrNull(1)?.toFloatOrNull()
            ?: Regex("""\b(\d+(?:\.\d+)?)\b""").find(title)
                ?.groupValues?.getOrNull(1)?.toFloatOrNull()
            ?: fallback
    }

    private val dateFormatLong = SimpleDateFormat("MMMM d, yyyy", Locale("id"))
    private val dateFormatShort = SimpleDateFormat("MMM d, yyyy", Locale("id"))
    private val dateFormatLongEn = SimpleDateFormat("MMMM d, yyyy", Locale.US)
    private val dateFormatShortEn = SimpleDateFormat("MMM d, yyyy", Locale.US)

    private fun parseDate(dateText: String): Long {
        val value = dateText.trim().lowercase(Locale("id"))
        if (value.isEmpty()) return 0L

        val cal = Calendar.getInstance()
        val number = Regex("""(\d+)""").find(value)?.value?.toIntOrNull()

        if (number != null) {
            when {
                value.contains("detik") -> cal.add(Calendar.SECOND, -number)
                value.contains("menit") -> cal.add(Calendar.MINUTE, -number)
                value.contains("jam") -> cal.add(Calendar.HOUR, -number)
                value.contains("hari") -> cal.add(Calendar.DAY_OF_MONTH, -number)
                value.contains("minggu") -> cal.add(Calendar.WEEK_OF_YEAR, -number)
                value.contains("bulan") -> cal.add(Calendar.MONTH, -number)
                value.contains("tahun") -> cal.add(Calendar.YEAR, -number)
                else -> return parseAbsoluteDate(dateText)
            }
            return cal.timeInMillis
        }

        if (value.contains("kemarin")) {
            cal.add(Calendar.DAY_OF_MONTH, -1)
            return cal.timeInMillis
        }

        return parseAbsoluteDate(dateText)
    }

    private fun parseAbsoluteDate(dateText: String): Long {
        return dateFormatLong.parseSafe(dateText).takeIf { it != 0L }
            ?: dateFormatShort.parseSafe(dateText).takeIf { it != 0L }
            ?: dateFormatLongEn.parseSafe(dateText).takeIf { it != 0L }
            ?: dateFormatShortEn.parseSafe(dateText).takeIf { it != 0L }
            ?: 0L
    }

    private companion object {
        private val KANZENIN_GENRES = listOf(
            "1607" to "Action",
            "7" to "Ahegao",
            "26" to "Anal",
            "255" to "Blackmail",
            "24" to "Bondage",
            "8" to "Cheating",
            "2" to "Comedy",
            "80" to "Demon",
            "13" to "Drama",
            "68" to "Elf",
            "417" to "Fakku",
            "1601" to "Fantasy",
            "18" to "Femdom",
            "451" to "Futanari",
            "42" to "Gangbang",
            "19" to "Group",
            "34" to "Harem",
            "94" to "Hipnotis",
            "11" to "Incest",
            "32" to "Lolicon",
            "60" to "Maid",
            "38" to "Mature",
            "5" to "Milf",
            "6" to "Mindbreak",
            "50" to "Monster Girl",
            "10" to "Mother",
            "43" to "Netorare",
            "149" to "Office Girl",
            "16" to "Parody",
            "37" to "Pregnant",
            "55" to "Prostitusi",
            "4" to "Rape",
            "147" to "Robot",
            "14" to "Romance",
            "28" to "School Girl",
            "3" to "Shotacon",
            "35" to "Story Arc",
            "45" to "Supernatural",
            "53" to "Teacher",
            "25" to "Threesome",
            "64" to "Vanilla",
            "22" to "Virgin",
            "56" to "Yandere",
            "1693" to "Yuri",
        )
    }
}
