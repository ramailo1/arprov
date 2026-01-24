package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.Requests

open class Uptobox : ExtractorApi() {
    private val app = Requests()
    override val name = "Uptobox"
    override val mainUrl = "https://uptobox.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            val doc = app.get(url).document
            
            val directLinks = doc.select("a[href*=.mp4], a[href*=.m3u8], a[download], .direct-link")
            
            for (link in directLinks) {
                val href = link.attr("href")
                
                if (href.isNotEmpty() && (href.contains(".mp4") || href.contains(".m3u8") || href.contains("uptobox"))) {
                    var fullUrl = href
                    if (!fullUrl.startsWith("http")) {
                        fullUrl = if (fullUrl.startsWith("//")) "https:$fullUrl" else mainUrl + fullUrl
                    }
                    sources.add(
                        newExtractorLink(name, name, fullUrl, if (fullUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                    )
                }
            }
            
            val scriptData = doc.html()
            val patterns = listOf(
                Regex(""""video_url"\s*:\s*"([^"]+)""""), // JSON pattern
                Regex("""url['"]\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")
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

class UptoboxCom : Uptobox() {
    override val name = "Uptobox"
    override val mainUrl = "https://uptobox.com"
}

class UptoboxNl : Uptobox() {
    override val name = "Uptobox"
    override val mainUrl = "https://uptobox.nl"
}
