package com.lagradost.cloudstream3.mycima

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class MyCimaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MyCimaProvider())
    }
}
