package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.Requests

open class Zippyshare : ExtractorApi() {
    private val app = Requests()
    override val name = "Zippyshare"
    override val mainUrl = "https://zippyshare.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            val doc = app.get(url).document
            
            val downloadLinks = doc.select("a[href*=.mp4], a[href*=.m3u8], #dlbutton, .download, .dlbutton")
            
            for (link in downloadLinks) {
                var href = link.attr("href")
                
                if (href.isNotEmpty() && (href.contains(".mp4") || href.contains(".m3u8") || href.contains("zippyshare"))) {
                    if (!href.startsWith("http")) {
                        href = if (href.startsWith("//")) "https:$href" else "https://zippyshare.com$href"
                    }
                    sources.add(
                        newExtractorLink(name, name, href, if (href.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                    )
                }
            }
             
            // Script extraction
            val scriptData = doc.html()
            val patterns = listOf(
                Regex("""window\.location\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""window\.open\(['"]([^'"]+)['"]"""),
                Regex("""download\(['"]([^'"]+)['"]"""),
                Regex("""url\s*[:=]\s*['"]([^'"]+)['"]"""),
                Regex("""href\s*[:=]\s*['"]([^'"]+)['"]""")
            )

            for (pattern in patterns) {
                val matches = pattern.findAll(scriptData)
                for (match in matches) {
                    var videoLink = match.groupValues[1]
                    if (!videoLink.startsWith("http")) {
                        videoLink = if (videoLink.startsWith("//")) "https:$videoLink" else "https://$videoLink"
                    }
                    if (videoLink.contains("zippyshare") || videoLink.contains(".mp4") || videoLink.contains(".m3u8")) {
                        sources.add(
                            newExtractorLink(name, name, videoLink, if (videoLink.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                        )
                    }
                }
            }

            // Video elements
            val videoElements = doc.select("video source, video")
            for (element in videoElements) {
                val src = element.attr("src")
                if (src.isNotEmpty() && (src.contains(".mp4") || src.contains(".m3u8"))) {
                    sources.add(
                        newExtractorLink(name, name, src, if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                    )
                }
            }
            
            // Download elements with onclick or data attributes
             val downloadElements = doc.select("[data-url], [data-link], [data-download], [onclick*=.mp4]")
            for (element in downloadElements) {
                val dataUrl = element.attr("data-url") + element.attr("data-link") + element.attr("data-download")
                val onclick = element.attr("onclick")
                
                val potentialUrl = if (dataUrl.isNotEmpty()) dataUrl else extractUrlFromOnclick(onclick)
                
                if (potentialUrl.isNotEmpty() && (potentialUrl.contains(".mp4") || potentialUrl.contains(".m3u8"))) {
                     sources.add(
                        newExtractorLink(name, name, potentialUrl, if (potentialUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                    )
                }
            }

        } catch (e: Exception) {
        }
        
        return sources
    }
    
    private fun extractUrlFromOnclick(onclick: String): String {
        if (onclick.isEmpty()) return ""
        val patterns = listOf(
            Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""window\.open\(['"]([^'"]+)['"]"""),
            Regex("""location\.href\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""['"](https?://[^'"]+)['"]""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(onclick)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return ""
    }
}

class ZippyshareCom : Zippyshare() {
    override val name = "Zippyshare"
    override val mainUrl = "https://zippyshare.com"
}
