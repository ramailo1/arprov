package com.cimaleek

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class CimaLeekPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CimaLeekProvider())
    }
}
