package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.nicehttp.Requests

class Aflamy : ExtractorApi() {
    private val app = Requests()
    override val name = "Aflamy"
    override val mainUrl = "https://w.aflamy.pro"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        val response = app.get(url, referer = referer).text
        
        // Scan for common file/sources patterns in JS
        val filePattern = Regex("""file:\s*["']([^"']+)"""")
        val sourcePattern = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)"""")
        
        filePattern.findAll(response).forEach { match ->
            val videoUrl = match.groupValues[1]
            if (videoUrl.contains("http")) {
                sources.add(
                    newExtractorLink(
                        name,
                        name,
                        videoUrl,
                        if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
            }
        }
        
        sourcePattern.findAll(response).forEach { match ->
            val videoUrl = match.groupValues[1]
            if (videoUrl.contains("http")) {
                sources.add(
                    newExtractorLink(
                        name,
                        name,
                        videoUrl,
                        if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
            }
        }

        return sources
    }
}
