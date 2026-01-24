package com.lagradost.cloudstream3.fajershow

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class FajerShowPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FajerShowProvider())
    }
}
