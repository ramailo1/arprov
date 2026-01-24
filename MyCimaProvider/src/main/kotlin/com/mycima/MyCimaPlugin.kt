package com.mycima.MyCima

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class MyCimaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MyCima())
    }
}
