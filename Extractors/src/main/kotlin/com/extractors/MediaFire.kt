package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.Requests

open class MediaFire : ExtractorApi() {
    private val app = Requests()
    override val name = "MediaFire"
    override val mainUrl = "https://mediafire.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            val doc = app.get(url).document
            
            val downloadButton = doc.select("a[href*=.mp4], a[href*=.m3u8], .download_link, #downloadButton, .input[onclick*=.mp4]")
            
            if (downloadButton.isNotEmpty()) {
                val directLink = downloadButton.attr("href")
                
                if (directLink.isNotEmpty() && (directLink.contains(".mp4") || directLink.contains(".m3u8") || directLink.contains("mediafire"))) {
                    sources.add(
                        newExtractorLink(name, name, directLink, if (directLink.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                    )
                }
            }
            
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
        val patterns = listOf(
            Regex("""window\.location\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""window\.open\(['"]([^'"]+)['"]"""),
            Regex("""download\(['"]([^'"]+)['"]"""),
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

class MediaFireCom : MediaFire() {
    override val name = "MediaFire"
    override val mainUrl = "https://mediafire.com"
}
