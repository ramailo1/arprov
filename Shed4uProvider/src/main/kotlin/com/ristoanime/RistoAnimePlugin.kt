package com.ristoanime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class RistoAnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RistoAnimeProvider())
    }
}
