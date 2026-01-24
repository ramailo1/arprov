package com.cima4ushop

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class Cima4uShopPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Cima4uShopProvider())
    }
}
