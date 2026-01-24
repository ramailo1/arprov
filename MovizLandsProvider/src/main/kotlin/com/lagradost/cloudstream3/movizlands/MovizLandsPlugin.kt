package com.lagradost.cloudstream3.movizlands

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class MovizLandsPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MovizLandsProvider())
    }
}
