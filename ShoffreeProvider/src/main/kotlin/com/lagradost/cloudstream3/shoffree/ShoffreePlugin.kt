package com.lagradost.cloudstream3.shoffree

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class ShoffreePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ShoffreeProvider())
    }
}