package com.lagradost.cloudstream3.animeblkom

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class AnimeBlkomPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeBlkomProvider())
    }
}
