package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.Requests

open class Moshahda : ExtractorApi() {
    private val app = Requests()
    override val name = "Moshahda"
    override val mainUrl = "https://moshahda.net"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            val doc = app.get(url, referer = referer).document
            
            val downloadLinks = mapOf(
                "download_l" to 240,
                "download_n" to 360,
                "download_h" to 480,
                "download_x" to 720,
                "download_o" to 1080
            )
            
            val regcode = """$mainUrl/embed-(\w+)""".toRegex()
            val code = regcode.find(url)?.groupValues?.getOrNull(1)
            
            if (code != null) {
                val baseLink = "$mainUrl/$code.html?"
                
                downloadLinks.forEach { (key, quality) ->
                    val downloadUrl = baseLink + key
                    sources.add(
                        newExtractorLink(name, name, downloadUrl, ExtractorLinkType.VIDEO)
                    )
                }
            }
            
        } catch (e: Exception) {
        }
        
        return sources
    }
}
