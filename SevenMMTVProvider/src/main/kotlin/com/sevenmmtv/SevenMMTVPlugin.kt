package com.sevenmmtv

import android.content.Context
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.app

@CloudstreamPlugin
class SevenMMTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SevenMMTVProvider())
        registerExtractorAPI(TapewithAdblockExtractor())
        registerExtractorAPI(Mmsi01Extractor())
        registerExtractorAPI(Mmvh01Extractor())
        registerExtractorAPI(UpnsLiveExtractor())
        registerExtractorAPI(EmturbovidExtractor())
        registerExtractorAPI(StreamTape())
    }
}

class TapewithAdblockExtractor : StreamTape() {
    override var name = "ST"
    override var mainUrl = "https://tapewithadblock.org"
}

class Mmsi01Extractor : VidhideExtractor() {
    override var name = "SW"
    override var mainUrl = "https://mmsi01.com"
    override val requiresReferer = false
}

class Mmvh01Extractor : VidhideExtractor() {
    override var name = "VH"
    override var mainUrl = "https://mmvh01.com"
    override val requiresReferer = false
}

class UpnsLiveExtractor : ExtractorApi() {
    override var name = "US"
    override var mainUrl = "https://7mmtv.upns.live"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val text = app.get(url, referer = referer ?: "$mainUrl/").text
        val m3u8 = Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.value
            ?.trim()
            ?: return emptyList()
        return M3u8Helper.generateM3u8(name, m3u8, referer ?: "$mainUrl/")
    }
}

class EmturbovidExtractor : ExtractorApi() {
    override var name = "TV"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val streamUrl = app.get(url, referer = referer ?: "$mainUrl/")
            .document
            .select("#video_player")
            .attr("data-hash")
            .trim()

        if (streamUrl.isBlank()) return emptyList()
        return M3u8Helper.generateM3u8(name, streamUrl, referer ?: "$mainUrl/")
    }
}
