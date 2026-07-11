package com.lagradost.cloudstream3.ifilmtv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class IfilmtvPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(IfilmtvArProvider())
        registerMainAPI(IfilmtvEnProvider())
        registerMainAPI(IfilmtvFaProvider())
    }
}
