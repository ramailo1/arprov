package com.lagradost.cloudstream3.cima4u

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class Cima4UPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Cima4UProvider())
    }
}
