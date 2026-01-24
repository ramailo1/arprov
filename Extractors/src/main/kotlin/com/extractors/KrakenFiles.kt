package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.Requests

open class KrakenFiles : ExtractorApi() {
    private val app = Requests()
    override val name = "KrakenFiles"
    override val mainUrl = "https://krakenfiles.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            val doc = app.get(url).document
            
            val downloadElements = doc.select("a[href*=.mp4], a[href*=.m3u8], a[download]")
            
            for (element in downloadElements) {
                val downloadUrl = element.attr("href")
                
                if (downloadUrl.isNotEmpty() && (downloadUrl.contains(".mp4") || downloadUrl.contains(".m3u8"))) {
                    var fullUrl = downloadUrl
                    if (!fullUrl.startsWith("http")) {
                        fullUrl = if (fullUrl.startsWith("//")) "https:$fullUrl" else mainUrl + fullUrl
                    }
                    sources.add(
                        newExtractorLink(name, name, fullUrl, if (fullUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                    )
                }
            }
            
            // Script extraction attempt
            val scriptData = doc.html()
            val patterns = listOf(
                Regex("""url['"]\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""['"](https?://[^'"]*krakenfiles[^'"]*\.(mp4|m3u8)[^'"]*)['"]""")
            )
            
            for (pattern in patterns) {
                val matches = pattern.findAll(scriptData)
                for (match in matches) {
                    var videoLink = match.groupValues[1]
                    if (!videoLink.startsWith("http")) {
                        videoLink = if (videoLink.startsWith("//")) "https:$videoLink" else "https://$videoLink"
                    }
                    sources.add(
                        newExtractorLink(name, name, videoLink, if (videoLink.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                    )
                }
            }
            
        } catch (e: Exception) {
        }
        
        return sources
    }
}

class KrakenFilesCom : KrakenFiles() {
    override val name = "KrakenFiles"
    override val mainUrl = "https://krakenfiles.com"
}
