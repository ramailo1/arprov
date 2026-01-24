package com.lagradost.cloudstream3.akwam

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class AkwamPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AkwamProvider())
    }
}
