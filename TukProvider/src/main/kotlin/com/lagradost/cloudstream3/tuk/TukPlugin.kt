package com.lagradost.cloudstream3.tuk

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class TukPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(TukProvider())
    }
}
