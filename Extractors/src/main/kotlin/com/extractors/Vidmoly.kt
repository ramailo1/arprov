package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.nicehttp.Requests
import com.lagradost.cloudstream3.utils.Qualities

open class Vidmoly : ExtractorApi() {
    private val app = Requests()
    override val name = "Vidmoly"
    override val mainUrl = "https://vidmoly.to"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val doc = app.get(url).document
        val scriptElements = doc.select("script")
        
        for (script in scriptElements) {
            val scriptData = script.data()
            if (scriptData.contains("sources")) {
                try {
                    val m3u8Pattern = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)"""")
                    val match = m3u8Pattern.find(scriptData)
                    
                    if (match != null) {
                        val m3u8Url = match.groupValues[1]
                        return listOf(
                            newExtractorLink(this.name, this.name, m3u8Url, if (m3u8Url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                        )
                    }
                } catch (e: Exception) {
                }
            }
        }
        
        val videoElements = doc.select("video source")
        for (element in videoElements) {
            val src = element.attr("src")
            if (src.isNotEmpty()) {
                return listOf(
                    newExtractorLink(this.name, this.name, src, if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                )
            }
        }
        
        return emptyList()
    }
}
