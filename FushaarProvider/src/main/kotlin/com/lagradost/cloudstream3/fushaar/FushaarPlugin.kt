package com.lagradost.cloudstream3.fushaar

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class FushaarPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FushaarProvider())
    }
}
