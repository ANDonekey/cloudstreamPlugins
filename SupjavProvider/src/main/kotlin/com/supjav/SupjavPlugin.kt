package com.supjav

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.StreamTape

@CloudstreamPlugin
class SupjavPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SupjavProvider())
        registerExtractorAPI(EmturbovidExtractor())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(WatchAdsOnTapeExtractor())
        registerExtractorAPI(Fc2streamExtractor())
    }
}