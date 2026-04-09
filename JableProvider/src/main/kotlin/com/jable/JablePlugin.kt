package com.jable

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class JablePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(JableProvider())
    }
}
