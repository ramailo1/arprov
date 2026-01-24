package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.Requests

open class StreamTape : ExtractorApi() {
    private val app = Requests()
    override val name = "StreamTape"
    override val mainUrl = "https://streamtape.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            val doc = app.get(url).document
            val scriptElements = doc.select("script")
            
            for (script in scriptElements) {
                val scriptData = script.data()
                
                if (scriptData.contains("videolink") || scriptData.contains("robotlink")) {
                    try {
                        val linkPattern = Regex("""robotlink['"]\s*=\s*['"]([^'"]+)"""")
                        val linkMatch = linkPattern.find(scriptData)
                        
                        if (linkMatch != null) {
                            val videoLink = "https:" + linkMatch.groupValues[1]
                            sources.add(
                                newExtractorLink(name, name, videoLink, ExtractorLinkType.VIDEO)
                            )
                        }
                    } catch (e: Exception) {
                    }
                }
            }
            
        } catch (e: Exception) {
        }
        
        return sources
    }
}

class StreamTapeNet : StreamTape() {
    override val name = "StreamTape"
    override val mainUrl = "https://streamtape.net"
}

class StreamTapeCom : StreamTape() {
    override val name = "StreamTape"
    override val mainUrl = "https://streamtape.com"
}

class StreamTapeTo : StreamTape() {
    override val name = "StreamTape"
    override val mainUrl = "https://streamtape.to"
}
