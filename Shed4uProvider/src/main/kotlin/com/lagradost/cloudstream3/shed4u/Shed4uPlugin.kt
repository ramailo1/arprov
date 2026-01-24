package com.lagradost.cloudstream3.shed4u

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

import com.lagradost.cloudstream3.ristoanime.RistoAnimeProvider

@CloudstreamPlugin
class Shed4uPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Shed4uProvider())
        registerMainAPI(RistoAnimeProvider())
    }
}
