package com.ristoanime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class RistoAnimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(RistoAnimeProvider())
    }
}
