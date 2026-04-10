package com.missav

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MissAVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MissAVProvider())
    }
}

