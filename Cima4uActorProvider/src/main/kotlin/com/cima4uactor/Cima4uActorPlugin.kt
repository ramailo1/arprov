package com.cima4uactor.Cima4uActor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class Cima4uActorPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Cima4uActor())
    }
}
