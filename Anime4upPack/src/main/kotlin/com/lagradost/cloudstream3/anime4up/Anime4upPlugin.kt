package com.lagradost.cloudstream3.anime4up

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class Anime4upPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Anime4upProvider())
    }
}
