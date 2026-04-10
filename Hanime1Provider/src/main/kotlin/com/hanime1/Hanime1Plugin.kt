package com.hanime1

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Hanime1Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Hanime1Provider())
    }
}

