package com.lagradost.cloudstream3.qisat

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class QisatTvPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(QisatTvProvider())
    }
}
