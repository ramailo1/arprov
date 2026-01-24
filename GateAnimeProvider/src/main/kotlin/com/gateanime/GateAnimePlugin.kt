package com.gateanime.GateAnime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class GateAnimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(GateAnime())
    }
}
