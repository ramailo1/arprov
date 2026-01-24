package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.Requests

open class MixDrop : ExtractorApi() {
    private val app = Requests()
    override val name = "MixDrop"
    override val mainUrl = "https://mixdrop.co"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            val doc = app.get(url).document
            val scriptElements = doc.select("script")
            
            for (script in scriptElements) {
                val scriptData = script.data()
                
                if (scriptData.contains("MDCore") || scriptData.contains("wurl") || scriptData.contains("mixdrop")) {
                    try {
                        val wurlPattern = Regex("""wurl\s*=\s*['"]([^'"]+)['"]"""")
                        val wurlMatch = wurlPattern.find(scriptData)
                        
                        if (wurlMatch != null) {
                            val wurl = wurlMatch.groupValues[1]
                            val videoLink = if (wurl.startsWith("http")) wurl else "https:$wurl"
                            
                            sources.add(
                                newExtractorLink(name, name, videoLink, ExtractorLinkType.VIDEO)
                            )
                            return sources
                        }
                    } catch (e: Exception) {
                    }
                }
            }
            
            val videoElements = doc.select("video source, video")
            for (element in videoElements) {
                val src = element.attr("src")
                if (src.isNotEmpty() && (src.contains("mixdrop") || src.contains("mp4"))) {
                    sources.add(
                        newExtractorLink(name, name, src, if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                    )
                }
            }
            
        } catch (e: Exception) {
        }
        
        return sources
    }
}

class MixDropAg : MixDrop() {
    override val name = "MixDrop"
    override val mainUrl = "https://mixdrop.ag"
}

class MixDropTo : MixDrop() {
    override val name = "MixDrop"
    override val mainUrl = "https://mixdrop.to"
}

class MixDropCh : MixDrop() {
    override val name = "MixDrop"
    override val mainUrl = "https://mixdrop.ch"
}
