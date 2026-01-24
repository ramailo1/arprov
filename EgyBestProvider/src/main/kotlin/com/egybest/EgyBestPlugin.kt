package com.egybest.EgyBest

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class EgyBestPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(EgyBest())
    }
}
