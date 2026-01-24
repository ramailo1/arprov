package com.movizland.Movizland

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class MovizlandPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Movizland())
    }
}
