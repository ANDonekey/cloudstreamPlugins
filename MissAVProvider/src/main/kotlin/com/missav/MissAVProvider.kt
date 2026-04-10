package com.missav

import android.webkit.CookieManager
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPage
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class MissAVProvider : MainAPI() {
    override var mainUrl = "https://missav.live"
    override var name = "MissAV"
    override var lang = "zh"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    private val dmVideoUrlRegex =
        Regex("""^https?://missav\.(?:ws|live)/dm\d+/[A-Za-z0-9\-]+(?:[?#].*)?$""", RegexOption.IGNORE_CASE)

    private val blockedVideoSlugs = setOf(
        "new", "release", "uncensored-leak",
        "today-hot", "weekly-hot", "monthly-hot",
        "chinese-subtitle", "english-subtitle", "subtitled-chinese", "subtitled-english",
        "fc2", "heyzo", "tokyohot", "1pondo",
        "caribbeancom", "caribbeancompr", "10musume", "pacopacomama", "gachinco",
        "xxxav", "marriedslash", "naughty4610", "naughty0930",
        "siro", "luxu", "gana", "maan", "scute", "ara", "madou", "twav", "furuke",
    )

    private val blockedFirstPathSegments = setOf(
        "cn", "actresses", "genres", "makers", "saved", "playlists", "history",
        "vip", "klive", "clive", "site", "dm",
    )

    override val mainPage = mainPageOf(
        mainPage("$mainUrl/dm590/cn/release", "新作上市", horizontalImages = true),
        mainPage("$mainUrl/dm515/cn/new", "最近更新", horizontalImages = true),
        mainPage("$mainUrl/dm150/cn/fc2", "无码影片", horizontalImages = true),
    )

    private data class PagePayload(
        val text: String,
        val document: Document,
        val cloudflareBlocked: Boolean,
    )

    private data class ListingCard(
        val title: String,
        val url: String,
        val posterUrl: String?,
    )

    private data class LdMeta(
        val title: String? = null,
        val description: String? = null,
        val releaseDate: String? = null,
        val actors: List<String> = emptyList(),
        val genres: List<String> = emptyList(),
    )

    private fun encodeQuery(query: String): String =
        URLEncoder.encode(query, "UTF-8").replace("+", "%20")

    private fun isCloudflareChallenge(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("<title>just a moment") ||
            lower.contains("enable javascript and cookies to continue") ||
            lower.contains("cf-browser-verification") ||
            lower.contains("challenge-error-text")
    }

    private fun getWebViewCookie(url: String): String? {
        return runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf { it.contains("cf_clearance") }
    }

    private suspend fun solveCloudflare(
        url: String,
        headers: Map<String, String>,
    ): Pair<String?, String?> {
        val resolver = WebViewResolver(
            interceptUrl = Regex(".^"),
            additionalUrls = listOf(Regex(".")),
            userAgent = null,
            useOkhttp = false,
            timeout = 45_000L,
        )
        resolver.resolveUsingWebView(url = url, headers = headers) {
            !getWebViewCookie(url).isNullOrBlank()
        }
        return getWebViewCookie(url) to WebViewResolver.webViewUserAgent
    }

    private suspend fun fetchPage(url: String): PagePayload {
        var response = app.get(
            url = url,
            headers = mapOf("Accept-Language" to "zh-CN,zh;q=0.9"),
        )
        if (isCloudflareChallenge(response.text)) {
            val (cookie, ua) = solveCloudflare(
                url = url,
                headers = mapOf("Accept-Language" to "zh-CN,zh;q=0.9"),
            )
            Log.i(TAG, "fetchPage cf url=$url cookie=${!cookie.isNullOrBlank()} ua=${!ua.isNullOrBlank()}")
            if (!cookie.isNullOrBlank()) {
                response = app.get(
                    url = url,
                    headers = mapOf(
                        "Accept-Language" to "zh-CN,zh;q=0.9",
                        "Cookie" to cookie,
                    ) + (ua?.let { mapOf("User-Agent" to it) } ?: emptyMap()),
                )
            }
        }
        val blocked = isCloudflareChallenge(response.text)
        Log.i(TAG, "fetchPage resolved url=$url blocked=$blocked len=${response.text.length}")
        return PagePayload(
            text = response.text,
            document = response.document,
            cloudflareBlocked = blocked,
        )
    }

    private fun extractPoster(element: Element): String? {
        val raw = sequenceOf(
            element.selectFirst("img")?.attr("data-src"),
            element.selectFirst("img")?.attr("data-original"),
            element.selectFirst("img")?.attr("data-srcset")?.substringBefore(",")?.substringBefore(" "),
            element.selectFirst("img")?.attr("srcset")?.substringBefore(",")?.substringBefore(" "),
            element.selectFirst("img")?.attr("src"),
        )
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() && !it.contains("/flags/") }

        return raw?.let(::fixUrl)
    }

    private fun looksLikeVideoCode(slug: String): Boolean {
        if (slug in blockedVideoSlugs) return false
        if (!slug.contains("-")) return false
        if (!slug.any { it.isDigit() }) return false
        return true
    }

    private fun isVideoUrl(url: String): Boolean {
        val normalized = url.substringBefore('#').substringBefore('?')
        if (dmVideoUrlRegex.matches(normalized)) {
            val slug = normalized.substringAfterLast('/').lowercase()
            return looksLikeVideoCode(slug)
        }

        val uri = runCatching { URI(normalized) }.getOrNull() ?: return false
        if (!uri.host.orEmpty().contains("missav.", ignoreCase = true)) return false
        val parts = uri.path.split('/').filter { it.isNotBlank() }
        if (parts.size != 2 || !parts[0].equals("cn", ignoreCase = true)) return false

        val slug = parts[1].lowercase()
        if (slug in blockedFirstPathSegments) return false
        return looksLikeVideoCode(slug)
    }

    private fun parseHtmlCards(document: Document): List<ListingCard> {
        return document.select("a[href]")
            .mapNotNull { anchor ->
                val href = fixUrl(anchor.attr("href")).substringBefore('#')
                if (!isVideoUrl(href)) return@mapNotNull null

                val title = sequenceOf(
                    anchor.attr("title"),
                    anchor.attr("data-title"),
                    anchor.selectFirst("img")?.attr("alt"),
                    anchor.selectFirst("h3, h4, h5, h6")?.text(),
                    anchor.text(),
                ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() } ?: return@mapNotNull null

                if (title in setOf("简体中文", "中文字幕", "English Subtitle", "中文")) return@mapNotNull null

                ListingCard(title, href, extractPoster(anchor))
            }
            .distinctBy { it.url }
    }

    private fun cardsToSearchResponses(cards: List<ListingCard>): List<SearchResponse> {
        return cards.map { card ->
            newMovieSearchResponse(card.title, card.url, TvType.NSFW, fix = false) {
                this.posterUrl = card.posterUrl
            }
        }
    }

    private fun withPage(url: String, page: Int): String {
        if (page <= 1) return url
        return if (url.contains("?")) "$url&page=$page" else "$url?page=$page"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val payload = fetchPage(withPage(request.data, page))
        if (payload.cloudflareBlocked) {
            Log.i(TAG, "getMainPage blocked data=${request.data} page=$page")
            return newHomePageResponse(request, emptyList(), false)
        }

        val cards = parseHtmlCards(payload.document)
        Log.i(TAG, "getMainPage data=${request.data} page=$page cards=${cards.size}")
        val hasNext = payload.document.select("a[href]").any {
            it.attr("href").contains("page=${page + 1}") || it.attr("href").contains("/page/${page + 1}")
        }
        return newHomePageResponse(request, cardsToSearchResponses(cards), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val encoded = encodeQuery(cleanQuery)
        val candidates = listOf(
            "$mainUrl/search/$encoded",
            "$mainUrl/dm265/cn/search/$encoded",
        )

        for (url in candidates) {
            val payload = fetchPage(url)
            if (payload.cloudflareBlocked) continue
            val cards = parseHtmlCards(payload.document)
            if (cards.isNotEmpty()) return cardsToSearchResponses(cards)
        }
        return emptyList()
    }

    private fun extractActorsFromJson(value: Any?): List<String> {
        return when (value) {
            is JSONObject -> listOfNotNull(value.optString("name").trim().ifBlank { null })
            is JSONArray -> (0 until value.length()).flatMap { extractActorsFromJson(value.opt(it)) }
            is String -> listOf(value.trim()).filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    private fun extractGenresFromJson(value: Any?): List<String> {
        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).trim().ifBlank { null } }
            is String -> value.split(",").map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    private fun parseLdMeta(document: Document): LdMeta {
        val titles = mutableListOf<String>()
        val descriptions = mutableListOf<String>()
        val releaseDates = mutableListOf<String>()
        val actors = mutableSetOf<String>()
        val genres = mutableSetOf<String>()

        fun collect(obj: JSONObject) {
            val type = obj.optString("@type").lowercase()
            val relevant = type.contains("movie") || type.contains("video")
            if (relevant) {
                obj.optString("name").trim().takeIf { it.isNotBlank() }?.let(titles::add)
                obj.optString("description").trim().takeIf { it.isNotBlank() }?.let(descriptions::add)
                obj.optString("datePublished").trim().takeIf { it.isNotBlank() }?.let(releaseDates::add)
                obj.optString("uploadDate").trim().takeIf { it.isNotBlank() }?.let(releaseDates::add)
                actors.addAll(extractActorsFromJson(obj.opt("actor")))
                actors.addAll(extractActorsFromJson(obj.opt("actors")))
                genres.addAll(extractGenresFromJson(obj.opt("genre")))
            }

            val graph = obj.optJSONArray("@graph")
            if (graph != null) {
                for (i in 0 until graph.length()) {
                    (graph.opt(i) as? JSONObject)?.let(::collect)
                }
            }
        }

        document.select("script[type=application/ld+json]").forEach { script ->
            val raw = script.data().trim()
            if (raw.isBlank()) return@forEach
            runCatching {
                if (raw.startsWith("[")) {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        (arr.opt(i) as? JSONObject)?.let(::collect)
                    }
                } else {
                    collect(JSONObject(raw))
                }
            }
        }

        return LdMeta(
            title = titles.firstOrNull(),
            description = descriptions.firstOrNull(),
            releaseDate = releaseDates.firstOrNull(),
            actors = actors.toList(),
            genres = genres.toList(),
        )
    }

    private fun extractLabeledValue(document: Document, labels: List<String>): String? {
        return document.select("div.text-secondary, span.text-secondary, p.text-secondary, li")
            .asSequence()
            .map { it.text().trim() }
            .firstOrNull { text ->
                labels.any { label -> text.contains(label, ignoreCase = true) } && text.length <= 120
            }
            ?.let { text ->
                val afterCn = text.substringAfter("：", text).trim()
                val afterEn = afterCn.substringAfter(":", afterCn).trim()
                afterEn.ifBlank { null }
            }
    }

    private fun isBadDetailTitle(value: String): Boolean {
        val lower = value.lowercase()
        return lower.contains("登入你的账户") ||
            lower.contains("登录你的账户") ||
            lower.contains("sign in") ||
            lower.contains("log in") ||
            lower.contains("login")
    }

    override suspend fun load(url: String): LoadResponse {
        val normalizedUrl = fixUrl(url).substringBefore('#')
        val payload = fetchPage(normalizedUrl)
        if (payload.cloudflareBlocked) throw ErrorLoadingException("Cloudflare blocked this page")

        val doc = payload.document
        val ldMeta = parseLdMeta(doc)
        val codeFromUrl = Regex("""/cn/([a-z0-9\-]+)$""", RegexOption.IGNORE_CASE)
            .find(normalizedUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.uppercase()

        val titleCandidates = listOfNotNull(
            doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("|")?.trim(),
            doc.selectFirst("meta[name=twitter:title]")?.attr("content")?.substringBefore("|")?.trim(),
            ldMeta.title?.trim(),
            doc.selectFirst("h1.text-base, h1, h2")?.text()?.trim(),
            codeFromUrl,
        )
        val title = titleCandidates.firstOrNull { !it.isNullOrBlank() && !isBadDetailTitle(it) }
            ?: throw ErrorLoadingException("No valid title found for $normalizedUrl")

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("img[alt][src]")?.attr("src")?.trim()?.ifBlank { null }

        val basePlot = sequenceOf(
            ldMeta.description,
            doc.selectFirst("div.my-4 div.text-secondary")?.text(),
            doc.selectFirst("div.mb-4 div.text-secondary")?.text(),
            doc.selectFirst("meta[name=description]")?.attr("content"),
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }

        val ogActors = doc.select("meta[property='og:video:actor']")
            .map { it.attr("content").trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actresses = ogActors

        val releaseDate = sequenceOf(
            extractLabeledValue(doc, listOf("发行日期", "發行日期", "release date", "published")),
            ldMeta.releaseDate,
            doc.selectFirst("time")?.text()?.trim()?.ifBlank { null },
        ).firstOrNull { !it.isNullOrBlank() }

        val codeFromLabel = extractLabeledValue(
            doc,
            listOf("番号", "番號", "品番", "车牌", "識別碼", "识别码", "code"),
        )?.uppercase()

        val code = codeFromLabel ?: codeFromUrl

        val plot = buildString {
            if (!basePlot.isNullOrBlank()) append(basePlot)
            if (!releaseDate.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("发行日期: ").append(releaseDate)
            }
            if (!code.isNullOrBlank()) {
                if (isNotEmpty()) append("\n")
                append("番号: ").append(code)
            }
            if (actresses.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("女优: ").append(actresses.joinToString(", "))
            }
        }.ifBlank { null }

        val tags = (
            doc.select("a[href*='/genres/'], a[href*='/makers/']")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .toMutableList()
                .apply {
                    addAll(ldMeta.genres)
                    if (!code.isNullOrBlank()) add("番号:$code")
                }
            )
            .distinct()
            .filterNot {
                it.equals("中文字幕", ignoreCase = true) ||
                    it.equals("简体中文", ignoreCase = true)
            }

        val year = releaseDate?.take(4)?.toIntOrNull()
        val actors = actresses.map { ActorData(Actor(it)) }.ifEmpty { null }

        val recommendations = cardsToSearchResponses(parseHtmlCards(doc))
            .filter { it.url != normalizedUrl }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, normalizedUrl, TvType.NSFW, normalizedUrl) {
            this.posterUrl = poster?.let(::fixUrl)
            this.plot = plot
            this.tags = tags
            this.year = year
            this.actors = actors
            this.recommendations = recommendations
        }
    }

    private fun extractM3u8Links(rawHtml: String): List<String> {
        val unescaped = rawHtml.replace("\\u002F", "/").replace("\\/", "/").replace("&amp;", "&")
        val m3u8Regexes = listOf(
            Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""", RegexOption.IGNORE_CASE),
            Regex("""hlsUrl\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""file\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""source\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
        )
        return m3u8Regexes.flatMap { regex ->
            regex.findAll(unescaped).map { m ->
                if (m.groupValues.size > 1) m.groupValues[1] else m.value
            }.toList()
        }.map { it.trim().trim('"', '\'') }
            .filter { it.startsWith("http", ignoreCase = true) }
            .distinct()
    }

    private fun extractIframeLinks(rawHtml: String): List<String> {
        val unescaped = rawHtml.replace("\\/", "/")
        val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        return iframeRegex.findAll(unescaped)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)?.let(::fixUrl) }
            .distinct()
            .toList()
    }

    private fun extractPlaylistUrls(html: String): List<String> {
        val unescaped = html.replace("\\u002F", "/").replace("\\/", "/").replace("&amp;", "&")
        val uuidPattern = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"

        val direct = Regex(
            """https?://surrit\.com/$uuidPattern/playlist\.m3u8""",
            RegexOption.IGNORE_CASE,
        ).findAll(unescaped).map { it.value.trim() }.toList()

        val fromUuid = Regex(
            """/($uuidPattern)/playlist\.m3u8""",
            RegexOption.IGNORE_CASE,
        ).findAll(unescaped).mapNotNull { match ->
            match.groupValues.getOrNull(1)?.let { uuid ->
                "https://surrit.com/$uuid/playlist.m3u8"
            }
        }.toList()

        val uuidOnly = Regex(uuidPattern, RegexOption.IGNORE_CASE)
            .findAll(unescaped)
            .map { it.value.lowercase() }
            .distinct()
            .toList()
            .map { "https://surrit.com/$it/playlist.m3u8" }

        return (direct + fromUuid + uuidOnly).distinct()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var response = app.get(
            url = data,
            headers = mapOf(
                "Accept-Language" to "zh-CN,zh;q=0.9",
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
            ),
        )

        if (isCloudflareChallenge(response.text)) {
            val (cookie, ua) = solveCloudflare(
                url = data,
                headers = mapOf(
                    "Accept-Language" to "zh-CN,zh;q=0.9",
                    "Referer" to "$mainUrl/",
                ),
            )
            if (!cookie.isNullOrBlank()) {
                response = app.get(
                    url = data,
                    headers = mapOf(
                        "Accept-Language" to "zh-CN,zh;q=0.9",
                        "Referer" to "$mainUrl/",
                        "Cookie" to cookie,
                    ) + (ua?.let { mapOf("User-Agent" to it) } ?: emptyMap()),
                )
            }
        }

        val html = response.text
        if (isCloudflareChallenge(html)) return false

        var foundAny = false
        val m3u8Headers = mapOf(
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
        )

        val unpacked = runCatching { getAndUnpack(html) }.getOrElse { "" }
        val playlistCandidates = (extractPlaylistUrls(html) + extractPlaylistUrls(unpacked)).distinct()
        Log.i(TAG, "loadLinks candidates=${playlistCandidates.size} unpackedLen=${unpacked.length}")

        playlistCandidates.forEach { streamUrl ->
            val links = M3u8Helper.generateM3u8(name, streamUrl, "$mainUrl/", headers = m3u8Headers)
            links.forEach(callback)
            if (links.isNotEmpty()) foundAny = true
        }

        extractM3u8Links(html).forEach { link ->
            M3u8Helper.generateM3u8(name, link, data, headers = m3u8Headers).forEach {
                foundAny = true
                callback(it)
            }
        }

        extractIframeLinks(html).forEach { iframe ->
            if (loadExtractor(iframe, data, subtitleCallback, callback)) foundAny = true
        }

        if (!foundAny) {
            val resolver = WebViewResolver(
                interceptUrl = Regex(".^"),
                additionalUrls = listOf(Regex(""".*\.m3u8.*""", RegexOption.IGNORE_CASE)),
                userAgent = null,
                useOkhttp = false,
                timeout = 45_000L,
            )
            val (_, requests) = resolver.resolveUsingWebView(
                url = data,
                headers = mapOf("Referer" to "$mainUrl/"),
            )
            requests.map { it.url.toString() }
                .filter { it.contains(".m3u8", ignoreCase = true) }
                .distinct()
                .forEach { streamUrl ->
                    val links = M3u8Helper.generateM3u8(name, streamUrl, "$mainUrl/", headers = m3u8Headers)
                    links.forEach(callback)
                    if (links.isNotEmpty()) foundAny = true
                }
        }

        Log.i(TAG, "loadLinks foundAny=$foundAny")
        return foundAny
    }

    companion object {
        private const val TAG = "MissAVProvider"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    }
}
