package com.starter

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType

class StarterProvider : MainAPI() {
    // TODO: Replace with the real site domain.
    override var mainUrl = "https://example.com"

    // TODO: Replace with the provider display name shown in Cloudstream.
    override var name = "Starter Provider"

    // TODO: Adjust language and supported types to match the target site.
    override var lang = "zh"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val hasMainPage = false

    override suspend fun search(query: String): List<SearchResponse> {
        // TODO: Implement site search parsing here.
        return emptyList()
    }
}
