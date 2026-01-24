package com.lagradost.cloudstream3.shahid4u

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class Shahid4uPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Shahid4uProvider())
    }
}
