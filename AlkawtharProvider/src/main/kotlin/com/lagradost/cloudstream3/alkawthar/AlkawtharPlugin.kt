package com.lagradost.cloudstream3.alkawthar

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class AlkawtharPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AlkawtharProvider())
    }
}
