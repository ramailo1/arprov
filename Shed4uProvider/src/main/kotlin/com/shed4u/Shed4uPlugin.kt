package com.shed4u.Shed4u

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class Shed4uPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Shed4u())
    }
}
