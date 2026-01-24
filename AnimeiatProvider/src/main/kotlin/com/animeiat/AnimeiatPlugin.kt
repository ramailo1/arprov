package com.animeiat.Animeiat

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class AnimeiatPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeiatProvider())
    }
}
