package com.lagradost.cloudstream3.gogoanime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class GogoAnimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(GogoAnimeProvider())
    }
}
