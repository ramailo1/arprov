package com.cima4ushop.Cima4uShop

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class Cima4uShopPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Cima4uShop())
    }
}
