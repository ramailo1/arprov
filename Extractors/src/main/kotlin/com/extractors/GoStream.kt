package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.Requests

open class GoStream : ExtractorApi() {
    private val app = Requests()
    override val name = "GoStream"
    override val mainUrl = "https://gostream.pro"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            val doc = app.get(url).document
            val scriptElements = doc.select("script")
            
            for (script in scriptElements) {
                val scriptData = script.data()
                
                if (scriptData.contains("sources") && scriptData.contains("file")) {
                    try {
                        val filePattern = Regex("""file:\s*["']([^"']+)"""")
                        val fileMatch = filePattern.find(scriptData)
                        
                        if (fileMatch != null) {
                            val videoUrl = fileMatch.groupValues[1]
                            sources.add(
                                newExtractorLink(name, name, videoUrl, if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                            )
                            return sources
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
            
            val videoElements = doc.select("video source, video")
            for (element in videoElements) {
                val src = element.attr("src")
                if (src.isNotEmpty()) {
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
