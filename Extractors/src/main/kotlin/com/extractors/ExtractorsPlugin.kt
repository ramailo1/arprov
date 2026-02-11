package com.extractors

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class ExtractorsPlugin : BasePlugin() {
    override fun load() {
        // تسجيل جميع الاستخراجات الأساسية
        registerExtractorAPI(GoStream())
        registerExtractorAPI(Govad())
        registerExtractorAPI(JWPlayer())
        registerExtractorAPI(LinkBox())
        registerExtractorAPI(Moshahda())
        registerExtractorAPI(MyVid())
        registerExtractorAPI(VidHD())
        registerExtractorAPI(VoeSx())
        registerExtractorAPI(Aflamy())
    }
}
