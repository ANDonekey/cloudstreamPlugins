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
import com.lagradost.cloudstream3.mainPage
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class JableProvider : MainAPI() {
    override var mainUrl = "https://jable.tv"
    override var name = "Jable"
    override var lang = "zh"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    private data class Section(
        val path: String,
        val title: String,
        val horizontalImages: Boolean = true,
    )

    private data class MenuLink(
        val title: String,
        val url: String,
        val posterUrl: String? = null,
    )

    private data class ListingVideo(
        val title: String,
        val url: String,
        val posterUrl: String? = null,
        val durationLabel: String? = null,
        val durationSeconds: Int? = null,
    )

    private val menuCategoriesPath = "__menu__/categories"
    private val listingPrefixes = listOf("/categories/", "/tags/", "/models/", "/search/")
    private val imageHeaders = mapOf("Referer" to "$mainUrl/")

    override val mainPage = mainPageOf(
        *listOf(
            Section("latest-updates", "最近更新"),
            Section("hot", "熱門影片"),
            Section("new-release", "全新上市"),
            Section(menuCategoriesPath, "主題分類"),
        ).map { mainPage(it.path, it.title, it.horizontalImages) }.toTypedArray()
    )

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun normalizeVideoUrl(url: String): String {
        return fixUrl(url).replace("/s0/videos/", "/videos/")
    }

    private fun createMenuResponse(title: String, url: String, posterUrl: String? = null): SearchResponse {
        return newMovieSearchResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            fix = false,
        ) {
            this.posterUrl = posterUrl
            this.posterHeaders = this@JableProvider.imageHeaders
        }
    }

    private fun parseDurationToSeconds(label: String?): Int? {
        val parts = label?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.split(":")
            ?.mapNotNull { it.toIntOrNull() }
            ?: return null

        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> null
        }
    }

    private fun Element.toListingVideo(): ListingVideo? {
        val titleAnchor = selectFirst("h6.title a") ?: select("a[href*=\"/videos/\"]").lastOrNull()
        val href = titleAnchor?.attr("href")?.takeIf { it.contains("/videos/") } ?: return null
        val title = titleAnchor.text().trim().ifBlank { return null }
        val poster = selectFirst("img[data-src]")?.attr("data-src")
            ?: selectFirst("img[data-original]")?.attr("data-original")
            ?: selectFirst("img[data-srcset]")?.attr("data-srcset")?.substringBefore(",")?.trim()?.substringBefore(" ")
            ?: selectFirst("img[srcset]")?.attr("srcset")?.substringBefore(",")?.trim()?.substringBefore(" ")
            ?: selectFirst("img[src]")?.attr("src")
        val durationLabel = selectFirst(".absolute-bottom-right .label, span.label")?.text()?.trim()

        return ListingVideo(
            title = title,
            url = normalizeVideoUrl(href),
            posterUrl = poster?.let(::fixUrl),
            durationLabel = durationLabel,
            durationSeconds = parseDurationToSeconds(durationLabel),
        )
    }

    private fun parseVideoCards(document: Document): List<SearchResponse> {
        return document.select("div.video-img-box.mb-e-20")
            .mapNotNull { it.toListingVideo()?.toSearchResponse() }
            .distinctBy { it.url }
    }

    private suspend fun fetchCategoryItems(): List<MenuLink> {
        val document = app.get("$mainUrl/categories/").document

        return document.select("#list_categories_video_categories_list .video-img-box a[href]")
            .mapNotNull { anchor ->
                val title = anchor.selectFirst("h4")?.text()?.trim().orEmpty()
                val href = anchor.attr("href").trim()
                val poster = anchor.selectFirst("img[src]")?.attr("src")?.trim().orEmpty()
                if (title.isBlank() || href.isBlank()) {
                    null
                } else {
                    MenuLink(
                        title = title,
                        url = fixUrl(href),
                        posterUrl = poster.takeIf { it.isNotBlank() }?.let(::fixUrl),
                    )
                }
            }
            .distinctBy { it.url }
    }

    private fun ListingVideo.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
        ) {
            this.posterUrl = posterUrl
            this.posterHeaders = this@JableProvider.imageHeaders
        }
    }

    private suspend fun fetchListing(url: String): List<SearchResponse> {
        return parseVideoCards(app.get(url).document)
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
        if (request.data == menuCategoriesPath) {
            if (page > 1) {
                return newHomePageResponse(request, emptyList(), false)
            }

            val items = fetchCategoryItems().map { item ->
                createMenuResponse(item.title, item.url, item.posterUrl)
            }
            return newHomePageResponse(request, items, false)
        }

        val url = buildPagedUrl(request.data, page)
        val document = app.get(url).document
        val results = parseVideoCards(document)
        val hasNext = document.select("ul.pagination a.page-link[href]")
            .any { it.attr("href").contains("/${request.data}/${page + 1}/") }
        return newHomePageResponse(request, results, hasNext)
    }

    private suspend fun loadListingPage(url: String): LoadResponse {
        val normalizedUrl = fixUrl(url)
        val document = app.get(normalizedUrl).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore("|")
            ?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("h2.h3-md, h4.h3-md, h1, h2, h3")?.text()?.trim()
            ?: throw ErrorLoadingException("No listing title found for $url")
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")?.let(::fixUrl)
        val plot = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val videos = document.select("div.video-img-box.mb-e-20")
            .mapNotNull { it.toListingVideo() }
            .distinctBy { it.url }
        val episodes = videos.mapIndexed { index, item ->
            newEpisode(
                url = item.url,
                initializer = {
                    this.name = item.title
                    this.posterUrl = item.posterUrl
                    this.season = 1
                    this.episode = index + 1
                    this.description = item.durationLabel
                    this.runTime = item.durationSeconds
                },
                fix = false,
            )
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = normalizedUrl,
            type = TvType.NSFW,
            episodes = episodes,
        ) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = listOf("分類頁")
        }
    }

    override suspend fun load(url: String): LoadResponse {
        if (listingPrefixes.any { url.contains(it) }) {
            return loadListingPage(url)
        }

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
            .mapNotNull { it.toListingVideo()?.toSearchResponse() }
            .filter { it.url != normalizeVideoUrl(url) }
            .distinctBy { it.url }

        return newMovieLoadResponse(
            name = title,
            url = normalizeVideoUrl(url),
            type = TvType.NSFW,
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
