@file:Suppress("DEPRECATION_ERROR")

package com.hanime1

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Hanime1Provider : MainAPI() {
    override var mainUrl = "https://hanime1.me"
    override var name = "Hanime1.me"
    override var lang = "zh"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        mainPage("$mainUrl/search?sort=%E6%9C%80%E6%96%B0%E4%B8%8A%E5%B8%82", "最新上市", horizontalImages = true),
        mainPage("$mainUrl/search?sort=%E6%9C%80%E6%96%B0%E4%B8%8A%E5%82%B3", "最新上傳", horizontalImages = true),
        mainPage("$mainUrl/search?genre=%E8%A3%8F%E7%95%AA&sort=%E6%9C%80%E6%96%B0%E4%B8%8A%E5%82%B3", "裏番", horizontalImages = false),
        mainPage("$mainUrl/search?genre=%E6%B3%A1%E9%BA%B5%E7%95%AA&sort=%E6%9C%80%E6%96%B0%E4%B8%8A%E5%82%B3", "泡麵番", horizontalImages = false),
        mainPage("$mainUrl/search?genre=Motion+Anime&sort=%E6%9C%80%E6%96%B0%E4%B8%8A%E5%82%B3", "Motion Anime", horizontalImages = true),
        mainPage("$mainUrl/search?genre=3DCG&sort=%E6%9C%80%E6%96%B0%E4%B8%8A%E5%82%B3", "3DCG", horizontalImages = true),
        mainPage("$mainUrl/search?genre=2.5D&sort=%E6%9C%80%E6%96%B0%E4%B8%8A%E5%82%B3", "2.5D", horizontalImages = true),
        mainPage("$mainUrl/search?genre=2D%E5%8B%95%E7%95%AB&sort=%E6%9C%80%E6%96%B0%E4%B8%8A%E5%82%B3", "2D動畫", horizontalImages = true),
        mainPage("$mainUrl/search?genre=AI%E7%94%9F%E6%88%90&sort=%E6%9C%80%E6%96%B0%E4%B8%8A%E5%82%B3", "AI生成", horizontalImages = true),
        mainPage("$mainUrl/search?genre=MMD&sort=%E6%9C%80%E6%96%B0%E4%B8%8A%E5%82%B3", "MMD", horizontalImages = true),
        mainPage("$mainUrl/search?genre=Cosplay&sort=%E6%9C%80%E6%96%B0%E4%B8%8A%E5%82%B3", "Cosplay", horizontalImages = true),
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

    private fun encodeQuery(query: String): String {
        return URLEncoder.encode(query, "UTF-8").replace("+", "%20")
    }

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
            headers = mapOf(
                "Accept-Language" to "zh-CN,zh;q=0.9,zh-TW;q=0.8",
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
            ),
        )

        if (isCloudflareChallenge(response.text)) {
            val (cookie, ua) = solveCloudflare(
                url = url,
                headers = mapOf(
                    "Accept-Language" to "zh-CN,zh;q=0.9,zh-TW;q=0.8",
                    "Referer" to "$mainUrl/",
                ),
            )
            if (!cookie.isNullOrBlank()) {
                response = app.get(
                    url = url,
                    headers = mapOf(
                        "Accept-Language" to "zh-CN,zh;q=0.9,zh-TW;q=0.8",
                        "User-Agent" to (ua ?: USER_AGENT),
                        "Referer" to "$mainUrl/",
                        "Cookie" to cookie,
                    ),
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

    private fun buildPageUrl(url: String, page: Int): String {
        if (page <= 1) return url
        val connector = if (url.contains("?")) "&" else "?"
        return "$url${connector}page=$page"
    }

    private fun cleanupTitle(raw: String?): String? {
        return raw
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.ifBlank { null }
    }

    private fun Element.toListingCard(): ListingCard? {
        val anchor = selectFirst("a.video-link[href*=\"watch?v=\"]")
            ?: selectFirst("a.overlay[href*=\"watch?v=\"]")
            ?: return null
        val href = anchor.attr("href").trim().ifBlank { return null }
        val fixedUrl = fixUrl(href).substringBefore("#")

        val title = cleanupTitle(
            sequenceOf(
                selectFirst(".title")?.text(),
                attr("title"),
                anchor.attr("title"),
                anchor.selectFirst("img[alt]")?.attr("alt"),
                anchor.text(),
            ).firstOrNull { !it.isNullOrBlank() },
        ) ?: return null

        val poster = sequenceOf(
            anchor.selectFirst("img.main-thumb")?.attr("src"),
            anchor.selectFirst("img.main-thumb")?.attr("data-src"),
            selectFirst("img.main-thumb")?.attr("src"),
            selectFirst("img.main-thumb")?.attr("data-src"),
            selectFirst("img[src]")?.attr("src"),
        )
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() && !it.contains("icon/", ignoreCase = true) }

        return ListingCard(
            title = title,
            url = fixedUrl,
            posterUrl = poster?.let(::fixUrl),
        )
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val fromVideoItemContainer = document.select("div.video-item-container")
            .mapNotNull { it.toListingCard() }

        val fromWatchAnchors = document.select("a[href*='watch?v=']")
            .mapNotNull { anchor ->
                val href = anchor.attr("href").trim().ifBlank { return@mapNotNull null }
                if (!href.contains("hanime1.me/watch?v=") && !href.startsWith("/watch?v=")) return@mapNotNull null

                val parent = anchor.parent()
                val title = cleanupTitle(
                    sequenceOf(
                        anchor.attr("title"),
                        anchor.selectFirst("img[alt]")?.attr("alt"),
                        parent?.selectFirst(".title")?.text(),
                        parent?.selectFirst(".home-rows-videos-title")?.text(),
                        parent?.selectFirst(".card-mobile-title")?.text(),
                        parent?.attr("title"),
                        anchor.text(),
                    ).firstOrNull { !it.isNullOrBlank() },
                ) ?: return@mapNotNull null

                val poster = sequenceOf(
                    anchor.selectFirst("img[src]")?.attr("src"),
                    parent?.selectFirst("img[src]")?.attr("src"),
                )
                    .mapNotNull { it?.trim() }
                    .firstOrNull { it.isNotBlank() && !it.contains("icon/", ignoreCase = true) }

                ListingCard(
                    title = title,
                    url = fixUrl(href).substringBefore("#"),
                    posterUrl = poster?.let(::fixUrl),
                )
            }

        return (fromVideoItemContainer + fromWatchAnchors)
            .distinctBy { it.url }
            .map { card ->
                newMovieSearchResponse(card.title, card.url, TvType.NSFW, fix = false) {
                    this.posterUrl = card.posterUrl
                }
            }
    }

    private fun parseHasNext(pageHtml: String, currentPage: Int, cardsCount: Int): Boolean {
        val totalPage = Regex("""/\s*&nbsp;\s*(\d+)""")
            .find(pageHtml)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (totalPage != null) return currentPage < totalPage
        return cardsCount >= 20
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val payload = fetchPage(url)
        if (payload.cloudflareBlocked) {
            return newHomePageResponse(request, emptyList(), false)
        }

        val cards = parseCards(payload.document)
        val hasNext = parseHasNext(payload.text, page, cards.size)
        return newHomePageResponse(request, cards, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val payload = fetchPage("$mainUrl/search?query=${encodeQuery(clean)}")
        if (payload.cloudflareBlocked) return emptyList()
        return parseCards(payload.document)
    }

    private fun extractDateText(document: Document): String? {
        return Regex("""(\d{4}-\d{2}-\d{2})""")
            .find(document.text())
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun extractYearFromDate(date: String?): Int? {
        return date?.take(4)?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val normalizedUrl = fixUrl(url).substringBefore('#')
        val payload = fetchPage(normalizedUrl)
        if (payload.cloudflareBlocked) throw ErrorLoadingException("Cloudflare blocked this page")

        val doc = payload.document
        val title = cleanupTitle(
            sequenceOf(
                doc.selectFirst("h3#shareBtn-title")?.text(),
                doc.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" - Hanime1"),
                doc.selectFirst("head > title")?.text()?.substringBefore(" - "),
            ).firstOrNull { !it.isNullOrBlank() },
        ) ?: throw ErrorLoadingException("No title found for $normalizedUrl")

        val poster = sequenceOf(
            doc.selectFirst("video#player")?.attr("poster"),
            doc.selectFirst("meta[property='og:image']")?.attr("content"),
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }?.let(::fixUrl)

        val description = cleanupTitle(
            sequenceOf(
                doc.selectFirst(".video-caption-text")?.text(),
                doc.selectFirst("meta[property='og:description']")?.attr("content"),
                doc.selectFirst("meta[name='description']")?.attr("content"),
            ).firstOrNull { !it.isNullOrBlank() },
        )

        val artist = cleanupTitle(doc.selectFirst("#video-artist-name")?.text())
        val date = extractDateText(doc)
        val year = extractYearFromDate(date)

        val tags = doc.select(".single-video-tag a[href*='/search?']")
            .mapNotNull { a ->
                cleanupTitle(
                    a.text()
                        .replace("#", "")
                        .replace(Regex("""\(\d+\)"""), "")
                        .trim(),
                )
            }
            .filter { it.lowercase() !in setOf("add", "remove") }
            .distinct()

        val plot = buildString {
            if (!description.isNullOrBlank()) append(description)
            if (!date.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("日期: ").append(date)
            }
            if (!artist.isNullOrBlank()) {
                if (isNotEmpty()) append("\n")
                append("作者: ").append(artist)
            }
        }.ifBlank { null }

        val recommendations = parseCards(doc)
            .filter { it.url != normalizedUrl }
            .distinctBy { it.url }
            .take(40)

        return newMovieLoadResponse(title, normalizedUrl, TvType.NSFW, normalizedUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
            this.actors = artist?.let { listOf(ActorData(Actor(it))) }
            this.recommendations = recommendations
        }
    }

    private fun parseSourceQuality(source: Element): Int {
        val fromAttr = source.attr("size").toIntOrNull()
        if (fromAttr != null) return fromAttr
        val fromUrl = Regex("""-(\d{3,4})p""", RegexOption.IGNORE_CASE)
            .find(source.attr("src"))
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return fromUrl ?: 720
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = fetchPage(data)
        if (payload.cloudflareBlocked) return false

        var foundAny = false

        payload.document.select("video#player source[src]").forEach { source ->
            val src = source.attr("src").trim().takeIf { it.isNotBlank() } ?: return@forEach
            val streamUrl = fixUrl(src)
            val quality = parseSourceQuality(source)
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name ${quality}p",
                    url = streamUrl,
                    referer = "$mainUrl/",
                    quality = quality,
                    isM3u8 = false,
                ),
            )
            foundAny = true
        }

        val m3u8Links = Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""", RegexOption.IGNORE_CASE)
            .findAll(payload.text)
            .map { it.value.trim() }
            .distinct()
            .toList()

        m3u8Links.forEach { m3u8 ->
            val links = M3u8Helper.generateM3u8(name, m3u8, "$mainUrl/")
            links.forEach(callback)
            if (links.isNotEmpty()) foundAny = true
        }

        Log.i(TAG, "loadLinks foundAny=$foundAny mp4=${foundAny && m3u8Links.isEmpty()} m3u8=${m3u8Links.size}")
        return foundAny
    }

    companion object {
        private const val TAG = "Hanime1Provider"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    }
}
