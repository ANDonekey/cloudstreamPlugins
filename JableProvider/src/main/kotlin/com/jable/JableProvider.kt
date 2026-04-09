package com.jable

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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Element
import java.net.URLEncoder

class JableProvider : MainAPI() {
    override var mainUrl = "https://jable.tv"
    override var name = "Jable"
    override var lang = "zh"
    override val supportedTypes = setOf(TvType.Movie)
    override val hasMainPage = true

    private data class Section(
        val path: String,
        val title: String,
    )

    override val mainPage = mainPageOf(
        *listOf(
            Section("latest-updates", "最近更新"),
            Section("hot", "熱門影片"),
            Section("new-release", "全新上市"),
            Section("categories/chinese-subtitle", "中文字幕"),
            Section("categories/uniform", "制服誘惑"),
            Section("categories/roleplay", "角色劇情"),
            Section("categories/pantyhose", "絲襪美腿"),
            Section("categories/pov", "第一視角"),
            Section("categories/private-cam", "私拍偷拍"),
            Section("categories/groupsex", "多P群交"),
            Section("categories/insult", "凌辱快感"),
            Section("categories/bdsm", "主奴調教"),
            Section("categories/uncensored", "無碼"),
            Section("tags/big-tits", "巨乳"),
            Section("tags/beautiful-leg", "美腿"),
            Section("tags/black-pantyhose", "黑絲"),
            Section("tags/creampie", "中出"),
            Section("tags/wife", "人妻"),
            Section("models/yua-mikami", "三上悠亞"),
            Section("models/arina-hashimoto", "橋本有菜"),
            Section("models/saika-kawakita", "河北彩花"),
            Section("models/kaede-karen", "楓可憐"),
            Section("models/kirara-asuka", "明日花綺羅"),
        ).map { it.path to it.title }.toTypedArray()
    )

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun normalizeVideoUrl(url: String): String {
        return fixUrl(url).replace("/s0/videos/", "/videos/")
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleAnchor = selectFirst("h6.title a") ?: select("a[href*=\"/videos/\"]").lastOrNull()
        val href = titleAnchor?.attr("href")?.takeIf { it.contains("/videos/") } ?: return null
        val title = titleAnchor.text().trim().ifBlank { return null }
        val poster = selectFirst("img[data-src]")?.attr("data-src")
            ?: selectFirst("img[src]")?.attr("src")

        return newMovieSearchResponse(
            name = title,
            url = normalizeVideoUrl(href),
            type = TvType.Movie,
        ) {
            this.posterUrl = poster?.let(::fixUrl)
        }
    }

    private suspend fun fetchListing(url: String): List<SearchResponse> {
        return app.get(url).document
            .select("div.video-img-box.mb-e-20")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    private fun buildPagedUrl(path: String, page: Int): String {
        val cleanPath = path.trim('/').ifBlank { return mainUrl }
        return if (page <= 1) {
            "$mainUrl/$cleanPath/"
        } else {
            "$mainUrl/$cleanPath/$page/"
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val rawQuery = query.trim()
        if (rawQuery.isBlank()) return emptyList()

        val lowerQuery = rawQuery.lowercase()
        val routedPath = when {
            lowerQuery.startsWith("tag:") -> "tags/${encodePathSegment(rawQuery.substringAfter(':').trim())}"
            lowerQuery.startsWith("category:") -> "categories/${encodePathSegment(rawQuery.substringAfter(':').trim())}"
            lowerQuery.startsWith("model:") -> "models/${encodePathSegment(rawQuery.substringAfter(':').trim())}"
            else -> null
        }

        return if (routedPath != null) {
            fetchListing(buildPagedUrl(routedPath, 1))
        } else {
            val encodedQuery = encodePathSegment(rawQuery)
            fetchListing("$mainUrl/search/$encodedQuery/")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPagedUrl(request.data, page)
        val document = app.get(url).document
        val results = document.select("div.video-img-box.mb-e-20")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
        val hasNext = document.select("ul.pagination a.page-link[href]")
            .any { it.attr("href").contains("/${request.data}/${page + 1}/") }
        return newHomePageResponse(request.name, results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        val document = response.document

        val title = document.selectFirst("section.video-info h4")?.text()?.trim()
            ?: throw ErrorLoadingException("No title found for $url")
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("video[poster]")?.attr("poster")
        val plot = document.selectFirst("h5.desc")?.text()?.trim()
        val tags = document.select("h5.tags a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val actors = document.select("div.models a.model span[title]")
            .mapNotNull { span ->
                val actorName = span.attr("title").trim().ifBlank { span.text().trim() }
                actorName.takeIf { it.isNotBlank() }?.let { ActorData(Actor(it)) }
            }
            .ifEmpty { null }
        val recommendations = document.select("div.video-img-box.mb-e-20")
            .mapNotNull { it.toSearchResponse() }
            .filter { it.url != normalizeVideoUrl(url) }
            .distinctBy { it.url }

        return newMovieLoadResponse(
            name = title,
            url = normalizeVideoUrl(url),
            type = TvType.Movie,
            dataUrl = normalizeVideoUrl(url),
        ) {
            this.posterUrl = posterUrl?.let(::fixUrl)
            this.plot = plot
            this.tags = tags
            this.actors = actors
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = app.get(data).text
        val hlsUrl = Regex("""var\s+hlsUrl\s*=\s*'([^']+)';""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return false

        M3u8Helper.generateM3u8(
            source = name,
            streamUrl = hlsUrl,
            referer = data,
        ).forEach(callback)

        return true
    }
}
