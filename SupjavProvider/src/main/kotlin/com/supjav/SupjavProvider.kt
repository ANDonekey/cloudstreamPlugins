package com.supjav

import android.webkit.CookieManager
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URLEncoder

class SupjavProvider : MainAPI() {
    override var mainUrl = "https://supjav.com"
    override var name = "SupJav"
    override var lang = "zh"
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    private val subtitleCatUrl = "https://www.subtitlecat.com"

    override val mainPage = mainPageOf(
        "$mainUrl/zh/category/censored-jav" to "\u6709\u7801",
        "$mainUrl/zh/category/uncensored-jav" to "\u65e0\u7801",
        "$mainUrl/zh/category/amateur" to "\u7d20\u4eba",
        "$mainUrl/zh/category/chinese-subtitles" to "\u4e2d\u6587\u5b57\u5e55",
        "$mainUrl/zh/category/reducing-mosaic" to "\u65e0\u7801\u7834\u89e3",
    )

    private data class PagePayload(
        val text: String,
        val document: Document,
        val cloudflareBlocked: Boolean,
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

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").trim().takeIf { it.isNotBlank() } ?: return null
        val title = sequenceOf(
            anchor.attr("title"),
            selectFirst("h2, h3, h4")?.text(),
            anchor.text(),
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() } ?: return null

        val posterUrl = sequenceOf(
            selectFirst("a > img")?.attr("data-original"),
            selectFirst("a > img")?.attr("data-src"),
            selectFirst("a > img")?.attr("src"),
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW, fix = false) {
            this.posterUrl = posterUrl?.let(::fixUrl)
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        return document.select("div.post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun extractLabeledValue(document: Document, labels: Set<String>): String? {
        val value = document.select("span")
            .firstOrNull { span ->
                labels.any { label -> span.text().trim().equals(label, ignoreCase = true) }
            }
            ?.let { span ->
                val directText = buildString {
                    var sibling = span.nextSibling()
                    while (sibling != null) {
                        when (sibling) {
                            is TextNode -> append(sibling.text())
                            is Element -> append(" ").append(sibling.text())
                        }
                        sibling = sibling.nextSibling()
                    }
                }.trim()

                if (directText.isNotBlank()) {
                    directText
                } else {
                    span.parent()?.text()
                        ?.replace(Regex("^\\s*[^:：]+[:：]\\s*"), "")
                        ?.trim()
                }
            }
            ?.takeIf { it.isNotBlank() }

        return value?.split(",", "/", "|", "，", "、")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.joinToString(", ")
    }

    private fun buildPagedUrl(data: String, page: Int): String {
        val base = if (data.startsWith("http://") || data.startsWith("https://")) {
            data.trimEnd('/')
        } else {
            "$mainUrl/${data.trim('/')}"
        }
        return "$base/page/$page"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = buildPagedUrl(request.data, page)
        val payload = fetchPage(pageUrl)

        if (payload.cloudflareBlocked) {
            Log.i(TAG, "getMainPage blocked data=${request.data} page=$page")
            return newHomePageResponse(request, emptyList(), false)
        }

        val items = parseCards(payload.document)
        Log.i(TAG, "getMainPage data=${request.data} page=$page cards=${items.size}")
        val hasNext = payload.document.select("a[href]").any {
            val href = it.attr("href")
            href.contains("/page/${page + 1}") || href.contains("paged=${page + 1}")
        }

        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = hasNext,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()

        val encoded = encodeQuery(clean)
        val results = mutableListOf<SearchResponse>()

        for (page in 1..3) {
            val payload = fetchPage("$mainUrl/page/$page?s=$encoded")
            if (payload.cloudflareBlocked) continue

            val pageResults = parseCards(payload.document)
            if (pageResults.isEmpty()) break
            results.addAll(pageResults)
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val normalizedUrl = fixUrl(url)
        val payload = fetchPage(normalizedUrl)
        if (payload.cloudflareBlocked) throw ErrorLoadingException("Cloudflare blocked this page")

        val document = payload.document
        val title = sequenceOf(
            document.selectFirst("div.archive-title h1")?.text(),
            document.selectFirst("meta[property='og:title']")?.attr("content"),
            document.selectFirst("head > title")?.text(),
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }
            ?: throw ErrorLoadingException("No title found for $normalizedUrl")

        val poster = fixUrlNull(
            sequenceOf(
                document.selectFirst("div.post-meta img")?.attr("src"),
                document.selectFirst("meta[property='og:image']")?.attr("content"),
            ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() },
        )

        val description = sequenceOf(
            document.selectFirst("div.post-meta h2")?.text(),
            document.selectFirst("meta[name='description']")?.attr("content"),
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }

        val castRaw = extractLabeledValue(document, setOf("Cast :", "Cast:", "演员 :", "演员:"))
        val makerRaw = extractLabeledValue(document, setOf("Maker :", "Maker:", "厂商 :", "厂商:"))

        val actors = castRaw
            ?.split(",", "，", "、", "/", "|")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.map { ActorData(Actor(it)) }
            ?.ifEmpty { null }

        val tags = (
            document.select("div.tags a")
                .map { it.text().trim() }
                .filter { it.isNotBlank() } +
                listOfNotNull(makerRaw?.let { "Maker:$it" })
            ).distinct()

        val recommendations = document
            .select("div.content:contains(You May Also Like) div.posts.clearfix div.post")
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != normalizedUrl }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, normalizedUrl, TvType.NSFW, normalizedUrl) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.actors = actors
            this.recommendations = recommendations
        }
    }

    private suspend fun loadServerLinks(
        document: Document,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var foundAny = false
        val servers = document.select("a.btn-server[data-link]")
        for (server in servers) {
            val encoded = server.attr("data-link").trim()
            if (encoded.isBlank()) continue

            val id = encoded.reversed()
            val fetchUrl = "https://lk1.supremejav.com/supjav.php?c=$id"

            val sourceUrl = app.get(
                url = fetchUrl,
                referer = fetchUrl,
                allowRedirects = false,
            ).headers["location"].orEmpty().trim()

            if (sourceUrl.isBlank()) continue
            Log.i(TAG, "loadLinks sourceUrl=$sourceUrl")

            if (loadExtractor(sourceUrl, referer = "$mainUrl/", subtitleCallback, callback)) {
                foundAny = true
            }
        }
        return foundAny
    }

    private suspend fun loadSubtitleCat(
        document: Document,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        runCatching {
            val title = document.select("head > title").text()
            val javCode = Regex("([a-zA-Z]+-\\d+)", RegexOption.IGNORE_CASE)
                .find(title)
                ?.groupValues
                ?.getOrNull(1)
                ?.uppercase()
                ?: return

            val query = "$subtitleCatUrl/index.php?search=$javCode"
            val subDoc = app.get(query, timeout = 15).document
            val links = subDoc.select("td a")

            links.forEach { item ->
                if (!item.text().contains(javCode, ignoreCase = true)) return@forEach

                val detailUrl = "$subtitleCatUrl/${item.attr("href").trimStart('/')}"
                val detailDoc = app.get(detailUrl, timeout = 10).document
                detailDoc.select(".col-md-6.col-lg-4").forEach { card ->
                    val language = card.select(".sub-single span:nth-child(2)").text()
                    val dl = card.select(".sub-single span:nth-child(3) a")
                        .firstOrNull { it.text().equals("Download", ignoreCase = true) }
                        ?: return@forEach

                    val subUrl = "$subtitleCatUrl${dl.attr("href")}"
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            language.replace("\uD83D\uDC4D \uD83D\uDC4E", "").trim().ifBlank { "SubtitleCat" },
                            subUrl,
                        ),
                    )
                }
            }
        }.onFailure {
            Log.i(TAG, "loadSubtitleCat skipped: ${it.message}")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = fetchPage(data)
        if (payload.cloudflareBlocked) return false

        var foundAny = loadServerLinks(payload.document, subtitleCallback, callback)

        if (!foundAny) {
            val directM3u8 = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""", RegexOption.IGNORE_CASE)
                .findAll(payload.text)
                .map { it.value }
                .distinct()
                .toList()

            directM3u8.forEach { streamUrl ->
                val links = M3u8Helper.generateM3u8(name, streamUrl, "$mainUrl/")
                links.forEach(callback)
                if (links.isNotEmpty()) foundAny = true
            }
        }

        loadSubtitleCat(payload.document, subtitleCallback)
        Log.i(TAG, "loadLinks foundAny=$foundAny")
        return foundAny
    }

    companion object {
        private const val TAG = "SupJavProvider"
    }
}

class WatchAdsOnTapeExtractor : StreamTape() {
    override var mainUrl = "https://watchadsontape.com"
    override var name = "StreamTape"
}

open class EmturbovidExtractor : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val streamUrl = app.get(url, referer = referer ?: "$mainUrl/")
            .document
            .select("#video_player")
            .attr("data-hash")
            .trim()

        if (streamUrl.isBlank()) return emptyList()

        return M3u8Helper.generateM3u8(name, streamUrl, "$mainUrl/")
    }
}

class Fc2streamExtractor : VidhideExtractor() {
    override var mainUrl = "https://fc2stream.tv"
    override val requiresReferer = false
}

