package com.lagradost.cloudstream3.faselhd

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class FaselHDPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FaselHDProvider())
    }
}
