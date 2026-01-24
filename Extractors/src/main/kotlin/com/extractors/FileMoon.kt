package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.Requests

open class FileMoon : ExtractorApi() {
    private val app = Requests()
    override val name = "FileMoon"
    override val mainUrl = "https://filemoon.sx"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            val doc = app.get(url).document
            val scriptElements = doc.select("script")
            
            for (script in scriptElements) {
                val scriptData = script.data()
                
                if (scriptData.contains(".m3u8") || scriptData.contains("hls") || scriptData.contains("filemoon")) {
                    try {
                        val m3u8Pattern = Regex("""['"]([^'"]*\.m3u8[^'"]*)['"]"""")
                        val m3u8Matches = m3u8Pattern.findAll(scriptData)
                        
                        for (match in m3u8Matches) {
                            var m3u8Url = match.groupValues[1]
                            if (!m3u8Url.startsWith("http")) {
                                 m3u8Url = if (m3u8Url.startsWith("//")) "https:$m3u8Url" else "https://$m3u8Url"
                            }
                            sources.add(
                                newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8)
                            )
                        }
                        
                        val qualityPattern = Regex("""['"]([^'"]*filemoon[^'"]*(?:1080|720|480|360)[^'"]*)['"]"""")
                        val qualityMatches = qualityPattern.findAll(scriptData)
                        
                        for (match in qualityMatches) {
                            var qualityUrl = match.groupValues[1]
                            if (!qualityUrl.startsWith("http")) {
                                 qualityUrl = if (qualityUrl.startsWith("//")) "https:$qualityUrl" else "https://$qualityUrl"
                            }
                            sources.add(
                                newExtractorLink(name, name, qualityUrl, if (qualityUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
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

class FileMoonTo : FileMoon() {
    override val name = "FileMoon"
    override val mainUrl = "https://filemoon.to"
}

class FileMoonIn : FileMoon() {
    override val name = "FileMoon"
    override val mainUrl = "https://filemoon.in"
}

class FileMoonNl : FileMoon() {
    override val name = "FileMoon"
    override val mainUrl = "https://filemoon.nl"
}
