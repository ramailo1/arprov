package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.Requests

open class MegaUp : ExtractorApi() {
    private val app = Requests()
    override val name = "MegaUp"
    override val mainUrl = "https://megaup.net"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            val doc = app.get(url).document
            val scriptElements = doc.select("script")
            
            for (script in scriptElements) {
                val scriptData = script.data()
                
                if (scriptData.contains("megaupload") || scriptData.contains("download") || scriptData.contains("megaup")) {
                    try {
                        val linkPatterns = listOf(
                            Regex("""['"](https?://[^'"]*megaup[^'"]*\.(mp4|m3u8)[^'"]*)['"]"""),
                            Regex("""['"](https?://[^'"]*download[^'"]*\.(mp4|m3u8)[^'"]*)['"]"""),
                            Regex("""download['"]\s*=\s*['"]([^'"]+)['"]"""),
                            Regex("""url['"]\s*=\s*['"]([^'"]+)['"]""")
                        )
                        
                        for (pattern in linkPatterns) {
                            val match = pattern.find(scriptData)
                            if (match != null) {
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
                }
            }
            
            val downloadLinks = doc.select("a[href*=.mp4], a[href*=.m3u8], a[href*=download]")
            for (link in downloadLinks) {
                val href = link.attr("href")
                if (href.isNotEmpty()) {
                    var fullUrl = href
                    if (!fullUrl.startsWith("http")) {
                        fullUrl = if (fullUrl.startsWith("//")) "https:$fullUrl" else mainUrl + fullUrl
                    }
                    sources.add(
                        newExtractorLink(name, name, fullUrl, if (fullUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                    )
                }
            }
            
        } catch (e: Exception) {
        }
        
        return sources
    }
}

class MegaUpCo : MegaUp() {
    override val name = "MegaUp"
    override val mainUrl = "https://megaup.co"
}

class MegaUpIo : MegaUp() {
    override val name = "MegaUp"
    override val mainUrl = "https://megaup.io"
}
