package com.animeblkom.AnimeBlkom

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class AnimeBlkomPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeBlkom())
    }
}
