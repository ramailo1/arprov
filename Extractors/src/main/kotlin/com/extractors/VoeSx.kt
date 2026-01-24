package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.nicehttp.Requests

open class VoeSx : ExtractorApi() {
    private val app = Requests()
    override val name = "VoeSx"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val doc = app.get(url).document
        val script = doc.select("script").map { it.data() }.first { it.contains("sources") }
        val m3u8 = script.substringAfter("'hls': '").substringBefore("'")
        val mp4 = script.substringAfter("'mp4': '").substringBefore("'")
        
        val sources = mutableListOf<ExtractorLink>()
        if (m3u8.isNotEmpty()) {
             sources.add(newExtractorLink("Voe.sx m3u8", this.name, m3u8, ExtractorLinkType.M3U8))
        }
        if (mp4.isNotEmpty()) {
             sources.add(newExtractorLink("Voe.sx mp4", this.name, mp4, ExtractorLinkType.VIDEO))
        }
        return sources
    }
}
