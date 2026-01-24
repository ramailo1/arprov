package com.arabseed.ArabSeed

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class ArabSeedPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ArabSeed())
    }
}
