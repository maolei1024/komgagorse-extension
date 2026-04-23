package eu.kanade.tachiyomi.extension.all.komgagorse

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.extension.all.komgagorse.dto.AuthorDto
import eu.kanade.tachiyomi.extension.all.komgagorse.dto.BookDto
import eu.kanade.tachiyomi.extension.all.komgagorse.dto.CollectionDto
import eu.kanade.tachiyomi.extension.all.komgagorse.dto.LibraryDto
import eu.kanade.tachiyomi.extension.all.komgagorse.dto.PageDto
import eu.kanade.tachiyomi.extension.all.komgagorse.dto.PageWrapperDto
import eu.kanade.tachiyomi.extension.all.komgagorse.dto.ReadListDto
import eu.kanade.tachiyomi.extension.all.komgagorse.dto.SeriesDto
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.apache.commons.text.StringSubstitutor
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

open class KomgaGorse(private val suffix: String = "") :
    HttpSource(),
    ConfigurableSource,
    UnmeteredSource {

    internal val preferences: SharedPreferences by getPreferencesLazy()

    private val displayName by lazy { preferences.getString(PREF_DISPLAY_NAME, "")!! }

    override val name by lazy {
        val displayNameSuffix = displayName
            .ifBlank { suffix }
            .let { if (it.isNotBlank()) " ($it)" else "" }

        "Komga Gorse$displayNameSuffix"
    }

    override val lang = "all"

    override val baseUrl by lazy { preferences.getString(PREF_ADDRESS, "")!!.removeSuffix("/") }

    override val supportsLatest = true

    // keep the previous ID when lang was "en", so that preferences and manga bindings are not lost
    override val id by lazy {
        val key = "komga-gorse${if (suffix.isNotBlank()) " ($suffix)" else ""}/en/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    private val username by lazy { preferences.getString(PREF_USERNAME, "")!! }

    private val password by lazy { preferences.getString(PREF_PASSWORD, "")!! }

    private val apiKey by lazy { preferences.getString(PREF_API_KEY, "")!! }

    private val completeThreshold: Int get() = preferences.getString(PREF_COMPLETE_THRESHOLD, "100")?.toIntOrNull() ?: 100

    private val defaultLibraries
        get() = preferences.getStringSet(PREF_DEFAULT_LIBRARIES, emptySet())!!

    private var appContext: android.content.Context? = null

    private val json: Json by injectLazy()

    // 阅读进度追踪: key=bookId, value=Pair(totalPages, maxPageFetched)
    private val readingTracker = ConcurrentHashMap<String, Pair<Int, Int>>()

    // Komga page progress: 记录已发送的最大页码，持续更新
    private val lastSentPage = ConcurrentHashMap<String, Int>()

    // 已标记完成的 book，避免重复发送 completed 和弹出 Toast
    private val completedBooks = ConcurrentHashMap.newKeySet<String>()

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", "TachiyomiKomga/${AppInfo.getVersionName()}")
        .also { builder ->
            if (apiKey.isNotBlank()) {
                builder.set("X-API-Key", apiKey)
            }
        }

    override val client: OkHttpClient =
        network.cloudflareClient.newBuilder()
            .authenticator { _, response ->
                if (apiKey.isNotBlank() || response.request.header("Authorization") != null) {
                    null // Give up if API key is set or we've already failed to authenticate.
                } else {
                    response.request.newBuilder()
                        .addHeader("Authorization", Credentials.basic(username, password))
                        .build()
                }
            }
            .dns(Dns.SYSTEM) // don't use DNS over HTTPS as it breaks IP addressing
            .build()

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/api/v1/series/recommended".toHttpUrl().newBuilder()
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("size", "20")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = processSeriesPage(response, baseUrl)

    override fun latestUpdatesRequest(page: Int): Request = searchMangaRequest(
        page,
        "",
        FilterList(
            SeriesSort(Filter.Sort.Selection(3, false)),
        ),
    )

    override fun latestUpdatesParse(response: Response): MangasPage = processSeriesPage(response, baseUrl)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val collectionId = (filters.find { it is CollectionSelect } as? CollectionSelect)?.let {
            it.collections[it.state].id
        }

        val type = when {
            collectionId != null -> "collections/$collectionId/series"
            filters.find { it is TypeSelect }?.state == 1 -> "readlists"
            filters.find { it is TypeSelect }?.state == 2 -> "books"
            else -> "series"
        }

        val url = "$baseUrl/api/v1".toHttpUrl().newBuilder()
            .addPathSegments(type)
            .addQueryParameter("search", query)
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("deleted", "false")

        val filterList = filters.ifEmpty { getFilterList() }
        val defaultLibraries = defaultLibraries

        if (filterList.filterIsInstance<LibraryFilter>().isEmpty() && defaultLibraries.isNotEmpty()) {
            url.addQueryParameter("library_id", defaultLibraries.joinToString(","))
        }

        filterList.forEach { filter ->
            when (filter) {
                is UriFilter -> filter.addToUri(url)

                is Filter.Sort -> {
                    val state = filter.state ?: return@forEach

                    val sortCriteria = when (state.index) {
                        0 -> "relevance"
                        1 -> if (type == "series") "metadata.titleSort" else "name"
                        2 -> "createdDate"
                        3 -> "lastModifiedDate"
                        4 -> "random"
                        else -> return@forEach
                    } + "," + if (state.ascending) "asc" else "desc"

                    url.addQueryParameter("sort", sortCriteria)
                }

                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = processSeriesPage(response, baseUrl)

    private fun processSeriesPage(response: Response, baseUrl: String): MangasPage {
        val data = if (response.isFromReadList()) {
            response.parseAs<PageWrapperDto<ReadListDto>>()
        } else if (response.isFromBook()) {
            response.parseAs<PageWrapperDto<BookDto>>()
        } else {
            response.parseAs<PageWrapperDto<SeriesDto>>()
        }

        return MangasPage(data.content.map { it.toSManga(baseUrl) }, !data.last)
    }

    override fun getMangaUrl(manga: SManga) = manga.url.replace("/api/v1", "")

    override fun mangaDetailsRequest(manga: SManga) = GET(manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga = if (response.isFromReadList()) {
        response.parseAs<ReadListDto>().toSManga(baseUrl)
    } else if (response.isFromBook()) {
        response.parseAs<BookDto>().toSManga(baseUrl)
    } else {
        response.parseAs<SeriesDto>().toSManga(baseUrl)
    }

    private val chapterNameTemplate
        get() = preferences.getString(PREF_CHAPTER_NAME_TEMPLATE, PREF_CHAPTER_NAME_TEMPLATE_DEFAULT)!!

    override fun getChapterUrl(chapter: SChapter) = chapter.url.replace("/api/v1/books", "/book")

    override fun chapterListRequest(manga: SManga): Request = when {
        manga.url.isFromBook() -> GET("${manga.url}?unpaged=true&media_status=READY&deleted=false", headers)
        else -> GET("${manga.url}/books?unpaged=true&media_status=READY&deleted=false", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        if (response.isFromBook()) {
            val book = response.parseAs<BookDto>()
            return listOf(
                SChapter.create().apply {
                    chapter_number = 1F
                    url = "$baseUrl/api/v1/books/${book.id}"
                    name = book.getChapterName(chapterNameTemplate, isFromReadList = true)
                    scanlator = book.metadata.authors
                        .filter { it.role == "translator" }
                        .joinToString { it.name }
                    date_upload = when {
                        book.metadata.releaseDate != null -> parseDate(book.metadata.releaseDate)
                        book.created != null -> parseDateTime(book.created)
                        else -> parseDateTime(book.fileLastModified)
                    }
                },
            )
        }
        val page = response.parseAs<PageWrapperDto<BookDto>>().content
        val isFromReadList = response.isFromReadList()
        val chapterNameTemplate = chapterNameTemplate

        return page
            .filter {
                it.media.mediaProfile != "EPUB" || it.media.epubDivinaCompatible
            }
            .mapIndexed { index, book ->
                SChapter.create().apply {
                    chapter_number = if (!isFromReadList) book.metadata.numberSort else index + 1F
                    url = "$baseUrl/api/v1/books/${book.id}"
                    name = book.getChapterName(chapterNameTemplate, isFromReadList)
                    scanlator = book.metadata.authors
                        .filter { it.role == "translator" }
                        .joinToString { it.name }
                    date_upload = when {
                        book.metadata.releaseDate != null -> parseDate(book.metadata.releaseDate)

                        book.created != null -> parseDateTime(book.created)

                        // XXX: `Book.fileLastModified` actually uses the server's running timezone,
                        // not UTC, even if the timestamp ends with a Z! We cannot determine the
                        // server's timezone, which is why this is a last resort option.
                        else -> parseDateTime(book.fileLastModified)
                    }
                }
            }
            .sortedByDescending { it.chapter_number }
    }

    override fun pageListRequest(chapter: SChapter) = GET("${chapter.url}/pages", headers)

    override fun pageListParse(response: Response): List<Page> {
        val pages = response.parseAs<List<PageDto>>()

        // 从 URL 提取 bookId: .../api/v1/books/{bookId}/pages
        val segments = response.request.url.pathSegments
        val booksIdx = segments.indexOf("books")
        if (booksIdx >= 0 && booksIdx + 1 < segments.size) {
            val bookId = segments[booksIdx + 1]
            readingTracker[bookId] = Pair(pages.size, 0)
            Log.d(logTag, "Tracking book $bookId with ${pages.size} pages")
        }

        return pages.map {
            val url = "${response.request.url}/${it.number}" +
                if (!SUPPORTED_IMAGE_TYPES.contains(it.mediaType)) {
                    "?convert=png"
                } else {
                    ""
                }

            Page(it.number, imageUrl = url)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val url = page.imageUrl!!

        // 从 URL 提取 bookId 和 pageNumber 追踪阅读进度
        // URL 格式: {baseUrl}/api/v1/books/{bookId}/pages/{pageNumber}
        try {
            val segments = url.toHttpUrl().pathSegments
            val booksIdx = segments.indexOf("books")
            if (booksIdx >= 0 && booksIdx + 3 < segments.size) {
                val bookId = segments[booksIdx + 1]
                val pageNumber = segments[booksIdx + 3].substringBefore("?").toIntOrNull()
                if (pageNumber != null) {
                    trackReadProgress(bookId, pageNumber)
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error tracking read progress", e)
        }

        return GET(url, headers = headersBuilder().add("Accept", "image/*,*/*;q=0.8").build())
    }

    /**
     * 追踪阅读进度:
     * - 达到最后一页时发送 completed 标记（触发 Komga 端 GorseEventListener 自动发送 Gorse feedback）
     * - 持续更新页码进度（每 10% 增量或到达最后一页时发送）
     */
    private fun trackReadProgress(bookId: String, pageNumber: Int) {
        if (completedBooks.contains(bookId)) return
        val (totalPages, maxPage) = readingTracker[bookId] ?: return
        val newMax = maxOf(maxPage, pageNumber)
        readingTracker[bookId] = Pair(totalPages, newMax)
        if (totalPages <= 0) return

        val progressPct = (newMax.toDouble() / totalPages * 100).toInt()

        // Komga page progress: 每 10% 增量发送，或达到完成阈值时立即发送
        val previousSentPage = lastSentPage[bookId] ?: 0
        val pageIncrement = (totalPages * 0.1).toInt().coerceAtLeast(1)
        val isComplete = progressPct >= completeThreshold && bookId !in completedBooks
        val shouldSend = (newMax > previousSentPage && (newMax - previousSentPage) >= pageIncrement) || isComplete
        if (shouldSend) {
            lastSentPage[bookId] = newMax
            scope.launch {
                try {
                    // 达到完成阈值时标记 completed，否则只更新页码
                    val requestBody = if (isComplete) {
                        """{"completed":true}"""
                    } else {
                        """{"page":$newMax}"""
                    }
                        .toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url("$baseUrl/api/v1/books/$bookId/read-progress")
                        .patch(requestBody)
                        .headers(headers)
                        .build()
                    client.newCall(request).execute().close()
                    if (isComplete) {
                        completedBooks.add(bookId)
                        Log.i(logTag, "Book $bookId marked as completed ($newMax/$totalPages, $progressPct%)")
                        showToast("\u2713 已标记阅读完成 ($newMax/$totalPages)")
                    } else {
                        Log.i(logTag, "Read progress sent for book $bookId: page $newMax/$totalPages ($progressPct%)")
                    }
                } catch (e: Exception) {
                    lastSentPage[bookId] = previousSentPage // 失败时回退
                    Log.e(logTag, "Failed to send read progress for book $bookId", e)
                    if (isComplete) {
                        showToast("\u2717 标记阅读完成失败，请检查网络")
                    }
                }
            }
        }
    }

    private fun getToastContext(): android.content.Context? {
        appContext?.let { return it }
        return try {
            uy.kohesive.injekt.Injekt.get<android.app.Application>()
        } catch (_: Exception) {
            null
        }
    }

    private fun showToast(message: String) {
        try {
            val ctx = getToastContext()
            if (ctx != null) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.w(logTag, "Toast skipped (no context): $message")
            }
        } catch (e: Exception) {
            Log.e(logTag, "Toast failed: $message", e)
        }
    }

    override fun getFilterList(): FilterList {
        fetchFilterOptions()

        val filters = mutableListOf<Filter<*>>(
            UnreadFilter(),
            InProgressFilter(),
            ReadFilter(),
            TypeSelect(),
            CollectionSelect(
                buildList {
                    add(CollectionFilterEntry("None"))
                    collections.forEach {
                        add(CollectionFilterEntry(it.name, it.id))
                    }
                },
            ),
            LibraryFilter(libraries, defaultLibraries),
            UriMultiSelectFilter(
                "Status",
                "status",
                listOf("Ongoing", "Ended", "Abandoned", "Hiatus").map {
                    UriMultiSelectOption(it, it.uppercase(Locale.ROOT))
                },
            ),
            UriMultiSelectFilter(
                "Genres",
                "genre",
                genres.map { UriMultiSelectOption(it) },
            ),
            UriMultiSelectFilter(
                "Tags",
                "tag",
                tags.map { UriMultiSelectOption(it) },
            ),
            UriMultiSelectFilter(
                "Publishers",
                "publisher",
                publishers.map { UriMultiSelectOption(it) },
            ),
        ).apply {
            if (fetchFilterStatus != FetchFilterStatus.FETCHED) {
                val message = if (fetchFilterStatus == FetchFilterStatus.NOT_FETCHED && fetchFiltersAttempts >= 3) {
                    "Failed to fetch filtering options from the server"
                } else {
                    "Press 'Reset' to show filtering options"
                }

                add(0, Filter.Header(message))
                add(1, Filter.Separator())
            }

            addAll(authors.map { (role, authors) -> AuthorGroup(role, authors.map { AuthorFilter(it) }) })
            add(SeriesSort())
        }

        return FilterList(filters)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        fetchFilterOptions()
        appContext = screen.context.applicationContext

        if (suffix.isEmpty()) {
            ListPreference(screen.context).apply {
                key = PREF_EXTRA_SOURCES_COUNT
                title = "Number of extra sources"
                summary = "Number of additional sources to create. There will always be at least one Komga source."
                entries = PREF_EXTRA_SOURCES_ENTRIES
                entryValues = PREF_EXTRA_SOURCES_ENTRIES

                setDefaultValue(PREF_EXTRA_SOURCES_DEFAULT)
                setOnPreferenceChangeListener { _, _ ->
                    Toast.makeText(screen.context, "Restart Tachiyomi to apply new setting.", Toast.LENGTH_LONG).show()
                    true
                }
            }.also(screen::addPreference)
        }

        screen.addEditTextPreference(
            title = "Source display name",
            default = suffix,
            summary = displayName.ifBlank { "Here you can change the source displayed suffix" },
            key = PREF_DISPLAY_NAME,
            restartRequired = true,
        )
        screen.addEditTextPreference(
            title = "Address",
            default = "",
            summary = baseUrl.ifBlank { "The server address" },
            dialogMessage = "The address must not end with a forward slash.",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            validate = { it.toHttpUrlOrNull() != null && !it.endsWith("/") },
            validationMessage = "The URL is invalid, malformed, or ends with a slash",
            key = PREF_ADDRESS,
            restartRequired = true,
        )
        // API key preference (takes precedence over username/password)
        screen.addEditTextPreference(
            title = "API key",
            default = "",
            summary = if (apiKey.isBlank()) "Optional: Use an API key for authentication" else "*".repeat(apiKey.length),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            key = PREF_API_KEY,
            restartRequired = true,
        )
        // Only show username/password if API key is not set
        if (apiKey.isBlank()) {
            screen.addEditTextPreference(
                title = "Username",
                default = "",
                summary = username.ifBlank { "The user account email" },
                key = PREF_USERNAME,
                restartRequired = true,
            )
            screen.addEditTextPreference(
                title = "Password",
                default = "",
                summary = if (password.isBlank()) "The user account password" else "*".repeat(password.length),
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                key = PREF_PASSWORD,
                restartRequired = true,
            )
        }

        MultiSelectListPreference(screen.context).apply {
            key = PREF_DEFAULT_LIBRARIES
            title = "Default libraries"
            summary = buildString {
                append("Show content from selected libraries by default.")

                if (libraries.isEmpty()) {
                    append(" Exit and enter the settings menu to load options.")
                }
            }
            entries = libraries.map { it.name }.toTypedArray()
            entryValues = libraries.map { it.id }.toTypedArray()
            setDefaultValue(emptySet<String>())
        }.also(screen::addPreference)

        screen.addEditTextPreference(
            title = "阅读完成阈值 (%)",
            default = "100",
            summary = "阅读到该百分比时自动标记为已完成并同步到 Komga",
            dialogMessage = "请输入 50 到 100 之间的一个数值。",
            inputType = InputType.TYPE_CLASS_NUMBER,
            validate = {
                val num = it.toIntOrNull()
                num != null && num in 50..100
            },
            validationMessage = "数值无效，必须在 50 并且小于 100 之间",
            key = PREF_COMPLETE_THRESHOLD,
        )

        val values = hashMapOf(
            "title" to "",
            "seriesTitle" to "",
            "number" to "",
            "createdDate" to "",
            "releaseDate" to "",
            "size" to "",
            "sizeBytes" to "",
        )
        val stringSubstitutor = StringSubstitutor(values, "{", "}").apply {
            isEnableUndefinedVariableException = true
        }

        screen.addEditTextPreference(
            key = PREF_CHAPTER_NAME_TEMPLATE,
            title = "Chapter title format",
            summary = "Customize how chapter names appear. Chapters in read lists will always be prefixed by the series' name.",
            inputType = InputType.TYPE_CLASS_TEXT,
            default = PREF_CHAPTER_NAME_TEMPLATE_DEFAULT,
            dialogMessage = """
            |Supported placeholders:
            |- {title}: Chapter name
            |- {seriesTitle}: Series name
            |- {number}: Chapter number
            |- {createdDate}: Chapter creation date
            |- {releaseDate}: Chapter release date
            |- {size}: Chapter file size (formatted)
            |- {sizeBytes}: Chapter file size (in bytes)
            |If you wish to place some text between curly brackets, place the escape character "$"
            |before the opening curly bracket, e.g. ${'$'}{series}.
            """.trimMargin(),
            validate = {
                try {
                    stringSubstitutor.replace(it)
                    true
                } catch (e: IllegalArgumentException) {
                    false
                }
            },
            validationMessage = "Invalid chapter title format",
        )
    }

    private var libraries = emptyList<LibraryDto>()
    private var collections = emptyList<CollectionDto>()
    private var genres = emptySet<String>()
    private var tags = emptySet<String>()
    private var publishers = emptySet<String>()
    private var authors = emptyMap<String, List<AuthorDto>>() // roles to list of authors

    private var fetchFilterStatus = FetchFilterStatus.NOT_FETCHED
    private var fetchFiltersAttempts = 0
    private val scope = CoroutineScope(Dispatchers.IO)

    private fun fetchFilterOptions() {
        if (baseUrl.isBlank() || fetchFilterStatus != FetchFilterStatus.NOT_FETCHED || fetchFiltersAttempts >= 3) {
            return
        }

        fetchFilterStatus = FetchFilterStatus.FETCHING
        fetchFiltersAttempts++

        scope.launch {
            try {
                libraries = client.newCall(GET("$baseUrl/api/v1/libraries", headers)).await().parseAs()
                collections = client
                    .newCall(GET("$baseUrl/api/v1/collections?unpaged=true", headers))
                    .await()
                    .parseAs<PageWrapperDto<CollectionDto>>()
                    .content
                genres = client.newCall(GET("$baseUrl/api/v1/genres", headers)).await().parseAs()
                tags = client.newCall(GET("$baseUrl/api/v1/tags", headers)).await().parseAs()
                publishers = client.newCall(GET("$baseUrl/api/v1/publishers", headers)).await().parseAs()
                authors = client
                    .newCall(GET("$baseUrl/api/v1/authors", headers))
                    .await()
                    .parseAs<List<AuthorDto>>()
                    .groupBy { it.role }
                fetchFilterStatus = FetchFilterStatus.FETCHED
            } catch (e: Exception) {
                fetchFilterStatus = FetchFilterStatus.NOT_FETCHED
                Log.e(logTag, "Failed to fetch filtering options", e)
            }
        }
    }

    fun String.isFromReadList() = contains("/api/v1/readlists")

    fun String.isFromBook() = contains("/api/v1/books")

    fun Response.isFromReadList() = request.url.toString().isFromReadList()

    fun Response.isFromBook() = request.url.toString().isFromBook()

    private inline fun <reified T> Response.parseAs(): T = json.decodeFromString(body.string())

    private val logTag by lazy { "komga-gorse${if (suffix.isNotBlank()) ".$suffix" else ""}" }

    companion object {
        internal const val PREF_EXTRA_SOURCES_COUNT = "Number of extra sources"
        internal const val PREF_EXTRA_SOURCES_DEFAULT = "2"

        internal const val TYPE_SERIES = "Series"
        internal const val TYPE_READLISTS = "Read lists"
        internal const val TYPE_BOOKS = "Books"
    }
}

private enum class FetchFilterStatus {
    NOT_FETCHED,
    FETCHING,
    FETCHED,
}

private val PREF_EXTRA_SOURCES_ENTRIES = (0..10).map { it.toString() }.toTypedArray()

private const val PREF_DISPLAY_NAME = "Source display name"
private const val PREF_ADDRESS = "Address"
private const val PREF_USERNAME = "Username"
private const val PREF_PASSWORD = "Password"
private const val PREF_API_KEY = "API key"
private const val PREF_DEFAULT_LIBRARIES = "Default libraries"
private const val PREF_COMPLETE_THRESHOLD = "pref_complete_threshold"
private const val PREF_CHAPTER_NAME_TEMPLATE = "Chapter name template"
private const val PREF_CHAPTER_NAME_TEMPLATE_DEFAULT = "{number} - {title} ({size})"

private val SUPPORTED_IMAGE_TYPES = listOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/jxl", "image/heif", "image/avif")
