package com.cimanow.CimaNow

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class CimaNowPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CimaNow())
    }
}
