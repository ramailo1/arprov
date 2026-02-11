package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.Requests

class Aflamy : ExtractorApi() {
    private val app = Requests()
    override val name = "Aflamy"
    override val mainUrl = "https://w.aflamy.pro"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        val response = app.get(url, referer = referer).text
        
        if (response.isBlank()) {
            return emptyList()
        }

        val hlsPattern = Regex("""(?:hls|playlist):\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val srcPattern = Regex("""src["']:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val filePattern = Regex("""file:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        
        val qualities = mapOf(
            "1080" to Qualities.P1080.value,
            "720" to Qualities.P720.value,
            "480" to Qualities.P480.value,
            "360" to Qualities.P360.value
        )

        suspend fun addSource(src: String) {
            if (!src.contains("http") || src.contains(".vtt")) return
            val qualityStr = Regex("""[0-9]{3,}""").find(src)?.value
            val qualityValue = qualities[qualityStr] ?: Qualities.Unknown.value
            
            sources.add(
                newExtractorLink(
                    name,
                    name,
                    src,
                    if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ).apply {
                    this.quality = qualityValue
                    this.referer = url
                }
            )
        }

        hlsPattern.findAll(response).forEach { addSource(it.groupValues[1]) }
        srcPattern.findAll(response).forEach { addSource(it.groupValues[1]) }
        filePattern.findAll(response).forEach { addSource(it.groupValues[1]) }

        return sources.distinctBy { it.url }.sortedByDescending { it.url.contains(".m3u8") }
    }
}
