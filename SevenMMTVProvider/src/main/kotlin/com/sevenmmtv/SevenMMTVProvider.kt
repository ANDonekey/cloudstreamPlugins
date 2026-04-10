package com.sevenmmtv

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
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPage
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SevenMMTVProvider : MainAPI() {
    override var mainUrl = "https://7mmtv.sx"
    override var name = "7mmtv.sx"
    override var lang = "zh"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        mainPage("$mainUrl/zh/censored_list/all/1.html", "有碼AV", horizontalImages = true),
        mainPage("$mainUrl/zh/uncensored_list/all/1.html", "無碼AV", horizontalImages = true),
        mainPage("$mainUrl/zh/amateurjav_list/all/1.html", "素人AV", horizontalImages = true),
        mainPage("$mainUrl/zh/chinese_list/all/1.html", "中字AV", horizontalImages = true),
        mainPage("$mainUrl/zh/reducing-mosaic_list/all/1.html", "無碼破解", horizontalImages = true),
        mainPage("$mainUrl/zh/amateur_list/all/1.html", "國產影片", horizontalImages = true),
    )

    private data class PagePayload(
        val text: String,
        val document: Document,
        val cloudflareBlocked: Boolean,
    )

    private data class VideoMeta(
        val title: String,
        val description: String?,
        val posterUrl: String?,
        val code: String?,
        val date: String?,
        val tags: List<String>,
        val actors: List<String>,
    )

    private fun encodePathSegment(raw: String): String {
        return URLEncoder.encode(raw, "UTF-8").replace("+", "%20")
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
            headers = mapOf("Accept-Language" to "zh-CN,zh;q=0.9"),
        )
        if (isCloudflareChallenge(response.text)) {
            val (cookie, ua) = solveCloudflare(
                url = url,
                headers = mapOf("Accept-Language" to "zh-CN,zh;q=0.9"),
            )
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
        return PagePayload(
            text = response.text,
            document = response.document,
            cloudflareBlocked = isCloudflareChallenge(response.text),
        )
    }

    private fun withPage(url: String, page: Int): String {
        if (page <= 1) return url
        return url.replace(Regex("""/(\d+)\.html$"""), "/$page.html")
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("h3.video-title a[href], figure.video-preview a[href]") ?: return null
        val href = anchor.attr("href").trim().takeIf { it.isNotBlank() } ?: return null

        val title = sequenceOf(
            selectFirst("h3.video-title a")?.text(),
            anchor.attr("title"),
            selectFirst("img")?.attr("alt"),
            anchor.text(),
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() } ?: return null

        val poster = sequenceOf(
            selectFirst("img.lazyload")?.attr("data-src"),
            selectFirst("img")?.attr("data-src"),
            selectFirst("img")?.attr("src"),
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW, fix = false) {
            this.posterUrl = poster?.let(::fixUrl)
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        return document.select("div.col-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun parseLdMeta(document: Document): VideoMeta? {
        fun collect(json: JSONObject): VideoMeta? {
            val type = json.optString("@type").lowercase()
            if (!type.contains("video")) return null

            val title = json.optString("name").trim()
            if (title.isBlank()) return null

            val desc = json.optString("description").trim().ifBlank { null }
            val poster = sequenceOf(
                json.optString("image"),
                json.optString("thumbnailUrl"),
            ).map { it.trim() }.firstOrNull { it.isNotBlank() }

            val code = json.optString("identifier").trim().ifBlank { null }
            val date = json.optString("uploadDate").trim().ifBlank { null }?.take(10)

            val tags = mutableListOf<String>()
            val genres = json.opt("genre")
            when (genres) {
                is JSONArray -> {
                    for (idx in 0 until genres.length()) {
                        genres.optString(idx).trim().ifBlank { null }?.let(tags::add)
                    }
                }
                is String -> genres.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach(tags::add)
            }

            val actors = mutableListOf<String>()
            val actorAny = json.opt("actor")
            when (actorAny) {
                is JSONArray -> {
                    for (idx in 0 until actorAny.length()) {
                        val obj = actorAny.optJSONObject(idx)
                        obj?.optString("name")?.trim()?.ifBlank { null }?.let(actors::add)
                    }
                }
                is JSONObject -> actorAny.optString("name").trim().ifBlank { null }?.let(actors::add)
            }

            return VideoMeta(
                title = title,
                description = desc,
                posterUrl = poster?.let(::fixUrl),
                code = code,
                date = date,
                tags = tags.distinct(),
                actors = actors.distinct(),
            )
        }

        document.select("script[type=application/ld+json]").forEach { script ->
            val raw = script.data().trim()
            if (raw.isBlank()) return@forEach
            runCatching {
                if (raw.startsWith("[")) {
                    val arr = JSONArray(raw)
                    for (index in 0 until arr.length()) {
                        val obj = arr.optJSONObject(index) ?: continue
                        collect(obj)?.let { return it }
                    }
                } else {
                    collect(JSONObject(raw))?.let { return it }
                }
            }
        }
        return null
    }

    private fun extractLabeledValue(document: Document, label: String): String? {
        return document.select("div.fullvideo-attr.row, div.fullvideo-attr")
            .asSequence()
            .map { it.text().trim() }
            .firstOrNull { it.contains(label, ignoreCase = true) }
            ?.let { text ->
                val afterCn = text.substringAfter("：", text).trim()
                val afterEn = afterCn.substringAfter(":", afterCn).trim()
                afterEn.ifBlank { null }
            }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val payload = fetchPage(withPage(request.data, page))
        if (payload.cloudflareBlocked) return newHomePageResponse(request, emptyList(), false)

        val cards = parseCards(payload.document)
        val hasNext = payload.document.select("a[href]").any {
            it.attr("href").contains("/${page + 1}.html")
        }
        return newHomePageResponse(request, cards, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val searchUrl = "$mainUrl/zh/searchall_search/all/${encodePathSegment(clean)}/1.html"
        val payload = fetchPage(searchUrl)
        if (payload.cloudflareBlocked) return emptyList()
        return parseCards(payload.document)
    }

    override suspend fun load(url: String): LoadResponse {
        val normalizedUrl = fixUrl(url)
        val payload = fetchPage(normalizedUrl)
        if (payload.cloudflareBlocked) throw ErrorLoadingException("Cloudflare blocked this page")

        val doc = payload.document
        val ld = parseLdMeta(doc)

        val title = sequenceOf(
            ld?.title,
            doc.selectFirst("h1.fullvideo-title")?.text(),
            doc.selectFirst("meta[property='og:title']")?.attr("content"),
            doc.selectFirst("head > title")?.text()?.substringBefore(" - "),
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }
            ?: throw ErrorLoadingException("No title found for $normalizedUrl")

        val poster = fixUrlNull(
            sequenceOf(
                ld?.posterUrl,
                doc.selectFirst(".content_main_cover img")?.attr("src"),
                doc.selectFirst(".content_main_cover img")?.attr("data-src"),
                doc.selectFirst("meta[property='og:image']")?.attr("content"),
            ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() },
        )

        val code = sequenceOf(
            ld?.code,
            doc.select("div.d-flex.mb-4 span.text-muted").firstOrNull()?.text()?.trim(),
            extractLabeledValue(doc, "番號"),
            extractLabeledValue(doc, "番号"),
        ).firstOrNull { !it.isNullOrBlank() }

        val date = sequenceOf(
            ld?.date,
            doc.select("div.d-flex.mb-4 span.text-muted").getOrNull(1)?.text()?.trim(),
        ).firstOrNull { !it.isNullOrBlank() }

        val actorsRaw = (
            (ld?.actors ?: emptyList()) +
                doc.select("div.fullvideo-idol span a")
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() }
            ).distinct()

        val tags = (
            (ld?.tags ?: emptyList()) +
                doc.select("div.categories a")
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() } +
                listOfNotNull(code?.let { "番号:$it" })
            ).distinct()

        val plot = buildString {
            ld?.description?.takeIf { it.isNotBlank() }?.let { append(it) }
            if (!date.isNullOrBlank()) {
                if (isNotEmpty()) append("\n")
                append("日期: ").append(date)
            }
            if (!code.isNullOrBlank()) {
                if (isNotEmpty()) append("\n")
                append("番號: ").append(code)
            }
        }.ifBlank { null }

        val recommendations = parseCards(doc)
            .filter { it.url != normalizedUrl }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, normalizedUrl, TvType.NSFW, normalizedUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.actors = actorsRaw.map { ActorData(Actor(it)) }.ifEmpty { null }
            this.recommendations = recommendations
        }
    }

    private fun extractLdLinks(document: Document): List<String> {
        val links = mutableListOf<String>()

        fun collect(json: JSONObject) {
            listOf("contentUrl", "embedUrl", "url").forEach { key ->
                json.optString(key).trim().ifBlank { null }?.let(links::add)
            }
            val video = json.opt("video")
            when (video) {
                is JSONObject -> collect(video)
                is JSONArray -> {
                    for (idx in 0 until video.length()) {
                        video.optJSONObject(idx)?.let(::collect)
                    }
                }
            }
        }

        document.select("script[type=application/ld+json]").forEach { script ->
            val raw = script.data().trim()
            if (raw.isBlank()) return@forEach
            runCatching {
                if (raw.startsWith("[")) {
                    val arr = JSONArray(raw)
                    for (idx in 0 until arr.length()) {
                        arr.optJSONObject(idx)?.let(::collect)
                    }
                } else {
                    collect(JSONObject(raw))
                }
            }
        }

        return links
            .map { if (it.startsWith("//")) "https:$it" else it }
            .map(::fixUrl)
            .filter { it.startsWith("http", ignoreCase = true) }
            .distinct()
    }

    private fun extractDirectM3u8(html: String): List<String> {
        return Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value.trim() }
            .distinct()
            .toList()
    }

    private data class EncodedServerEntry(
        val encoded: String,
        val baseUrl: String,
        val suffix: String,
    )

    private fun parseIntVar(html: String, name: String): Int? {
        return Regex("""\b$name\s*=\s*(\d+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun parseStringVar(html: String, name: String): String? {
        return Regex("""\b$name\s*=\s*'([^']+)'""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.ifBlank { null }
    }

    private fun parseEncodedServers(html: String): List<EncodedServerEntry> {
        // Example:
        // mvarr['37_1']=[['id','<enc>','<iframe...>','https://mmsi01.com/e/','','></iframe>','<a...>'],];
        val entryRegex = Regex(
            """mvarr\['[^']+'\]\s*=\s*\[\['[^']*','([^']+)','[^']*','([^']+)','([^']*)','[^']*','[^']*'\],\];""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        return entryRegex.findAll(html)
            .mapNotNull { match ->
                val encoded = match.groupValues.getOrNull(1)?.trim().orEmpty()
                val baseUrl = match.groupValues.getOrNull(2)?.trim().orEmpty()
                val suffix = match.groupValues.getOrNull(3)?.trim().orEmpty()
                if (encoded.isBlank() || baseUrl.isBlank()) return@mapNotNull null

                EncodedServerEntry(
                    encoded = encoded,
                    baseUrl = baseUrl,
                    suffix = suffix,
                )
            }
            .distinctBy { "${it.baseUrl}|${it.encoded}|${it.suffix}" }
            .toList()
    }

    private fun decodeSun(
        encoded: String,
        xorKey: Int,
        rawBase: Int,
    ): String {
        val base = if (rawBase <= 25) rawBase else rawBase % 25
        if (base < 2) return ""
        val splitChar = (base + 97).toChar().toString()
        return encoded.split(splitChar)
            .filter { it.isNotBlank() }
            .joinToString("") { part ->
                val parsed = part.lowercase().toInt(base)
                ((parsed xor xorKey).toChar()).toString()
            }
    }

    private fun decryptAesCbcPkcs7(
        encryptedBase64: String,
        key: String,
        iv: String,
    ): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        val raw = android.util.Base64.decode(encryptedBase64, android.util.Base64.DEFAULT)
        return cipher.doFinal(raw).toString(Charsets.UTF_8)
    }

    private fun extractDecodedServerUrls(html: String): List<String> {
        val xorKey = parseIntVar(html, "hadeedg252") ?: return emptyList()
        val base = parseIntVar(html, "hcdeedg252") ?: return emptyList()
        val key = parseStringVar(html, "argdeqweqweqwe") ?: return emptyList()
        val iv = parseStringVar(html, "hdddedg252") ?: return emptyList()

        val entries = parseEncodedServers(html)
        if (entries.isEmpty()) return emptyList()

        val urls = entries.mapNotNull { item ->
            runCatching {
                val encrypted = decodeSun(item.encoded, xorKey, base)
                val decrypted = decryptAesCbcPkcs7(encrypted, key, iv).trim()
                if (decrypted.isBlank()) return@mapNotNull null

                val normalizedBase = when {
                    item.baseUrl.startsWith("//") -> "https:${item.baseUrl}"
                    else -> item.baseUrl
                }

                if (normalizedBase.equals("https://emturbovid.com/t/", ignoreCase = true)) {
                    fixUrl(decrypted)
                } else {
                    fixUrl("$normalizedBase$decrypted${item.suffix}")
                }
            }.getOrNull()
        }.distinct()

        Log.i(TAG, "decodedServers entries=${entries.size} urls=${urls.size} hosts=${urls.mapNotNull { runCatching { URI(it).host }.getOrNull() }.distinct().joinToString()}")
        return urls
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
        val referer = "$mainUrl/"
        val triedHosts = mutableSetOf<String>()
        val successHosts = mutableSetOf<String>()
        val expectedHosts = setOf(
            "tapewithadblock.org",
            "mmsi01.com",
            "mmvh01.com",
            "emturbovid.com",
            "7mmtv.upns.live",
            "7mmtv.sx",
        )
        val blockedAdHosts = setOf(
            "edge-hls.saawsedge.com",
            "media-hls.saawsedge.com",
        )

        fun hostOf(url: String): String? {
            return runCatching { URI(url).host?.lowercase() }.getOrNull()
        }

        val hostPriority = listOf(
            "mmsi01.com",
            "mmvh01.com",
            "7mmtv.upns.live",
            "tapewithadblock.org",
            "emturbovid.com",
            "7mmtv.sx",
        )

        fun sortByHostPriority(links: List<String>): List<String> {
            return links.distinct().sortedBy { url ->
                val host = hostOf(url).orEmpty()
                val index = hostPriority.indexOfFirst { host.endsWith(it) }
                if (index == -1) Int.MAX_VALUE else index
            }
        }

        suspend fun probeExtractorLinks(links: List<String>, label: String) {
            sortByHostPriority(links).forEach { link ->
                val host = hostOf(link)
                host?.let(triedHosts::add)
                val ok = runCatching {
                    loadExtractor(link, referer, subtitleCallback, callback)
                }.getOrDefault(false)
                Log.i(TAG, "loadLinks $label host=$host ok=$ok url=$link")
                if (ok) {
                    foundAny = true
                    host?.let(successHosts::add)
                }
            }
        }

        probeExtractorLinks(extractLdLinks(payload.document), "ldLink")

        val decodedServerUrls = extractDecodedServerUrls(payload.text)
        probeExtractorLinks(decodedServerUrls, "decoded")

        extractDirectM3u8(payload.text).forEach { streamUrl ->
            val host = hostOf(streamUrl)
            host?.let(triedHosts::add)
            if (host != null && blockedAdHosts.any { host.endsWith(it) }) {
                Log.i(TAG, "skip ad m3u8 host=$host url=$streamUrl")
                return@forEach
            }
            val links = M3u8Helper.generateM3u8(name, streamUrl, referer)
            links.forEach(callback)
            if (links.isNotEmpty()) {
                foundAny = true
                host?.let(successHosts::add)
            }
        }

        val enoughTrustedSources = successHosts.any { it.endsWith("mmsi01.com") } &&
            successHosts.any { it.endsWith("mmvh01.com") }

        if (enoughTrustedSources) {
            val missed = expectedHosts
                .filter { expected -> triedHosts.none { it.endsWith(expected) } }
                .joinToString(", ")
                .ifBlank { "none" }
            Log.i(TAG, "skip webview: enough trusted sources success=${successHosts.joinToString()}")
            Log.i(TAG, "WebView captured=0 tried=${triedHosts.joinToString()} success=${successHosts.joinToString()} missed=$missed")
            Log.i(TAG, "loadLinks foundAny=$foundAny")
            return foundAny
        }

        val resolver = WebViewResolver(
            interceptUrl = Regex(".^"),
            additionalUrls = listOf(
                Regex(""".*\.m3u8.*""", RegexOption.IGNORE_CASE),
                Regex(""".*tapewithadblock\.org/.*""", RegexOption.IGNORE_CASE),
                Regex(""".*mmsi01\.com/.*""", RegexOption.IGNORE_CASE),
                Regex(""".*mmvh01\.com/.*""", RegexOption.IGNORE_CASE),
                Regex(""".*emturbovid\.com/.*""", RegexOption.IGNORE_CASE),
                Regex(""".*upns\.live/.*""", RegexOption.IGNORE_CASE),
                Regex(""".*/assets/js/play/play\.php\?id=.*""", RegexOption.IGNORE_CASE),
            ),
            userAgent = null,
            useOkhttp = false,
            timeout = 15_000L,
        )

        val captured = runCatching {
            val (_, requests) = resolver.resolveUsingWebView(
                url = data,
                headers = mapOf("Referer" to referer),
            )
            requests
                .map { it.url.toString() }
                .filter { it.startsWith("http", ignoreCase = true) }
                .distinct()
        }.getOrElse { emptyList() }

        val capturedExtractorUrls = mutableListOf<String>()
        captured.forEach { reqUrl ->
            val host = hostOf(reqUrl)
            host?.let(triedHosts::add)
            if (host != null && blockedAdHosts.any { host.endsWith(it) }) {
                Log.i(TAG, "skip ad captured host=$host url=$reqUrl")
                return@forEach
            }
            if (reqUrl.contains(".m3u8", ignoreCase = true)) {
                if (host != null && expectedHosts.none { host.endsWith(it) }) {
                    Log.i(TAG, "skip unknown m3u8 host=$host url=$reqUrl")
                    return@forEach
                }
                val links = M3u8Helper.generateM3u8(name, reqUrl, referer)
                links.forEach(callback)
                if (links.isNotEmpty()) {
                    foundAny = true
                    host?.let(successHosts::add)
                }
            } else {
                capturedExtractorUrls += reqUrl
            }
        }

        probeExtractorLinks(capturedExtractorUrls, "captured")

        val missed = expectedHosts
            .filter { expected -> triedHosts.none { it.endsWith(expected) } }
            .joinToString(", ")
            .ifBlank { "none" }

        Log.i(TAG, "WebView captured=${captured.size} tried=${triedHosts.joinToString()} success=${successHosts.joinToString()} missed=$missed")
        Log.i(TAG, "loadLinks foundAny=$foundAny")
        return foundAny
    }

    companion object {
        private const val TAG = "SevenMMTVProvider"
    }
}
