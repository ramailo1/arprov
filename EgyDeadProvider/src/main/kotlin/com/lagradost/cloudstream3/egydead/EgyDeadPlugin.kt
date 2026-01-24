package com.lagradost.cloudstream3.egydead

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class EgyDeadPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(EgyDeadProvider())
    }
}
