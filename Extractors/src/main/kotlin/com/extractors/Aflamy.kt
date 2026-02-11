package com.extractors

import com.lagradost.cloudstream3.fixUrl
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
        val mainDoc = app.get(url, referer = referer).document
        
        // 1. Get all server links
        val serverLinks = mainDoc.select("a.aplr-link").mapNotNull { 
            val href = it.attr("href")
            if (href.isNotBlank()) fixUrl(href, url) else null
        }.toMutableList()
        
        // If no server links, add the current URL as a default server
        if (serverLinks.isEmpty()) serverLinks.add(url)

        val hlsPattern = Regex("""(?:hls|playlist|file):\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val srcPattern = Regex("""src["']:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        
        val qualities = mapOf(
            "1080" to Qualities.P1080.value,
            "720" to Qualities.P720.value,
            "480" to Qualities.P480.value,
            "360" to Qualities.P360.value
        )

        suspend fun addSource(src: String, pageUrl: String) {
            if (!src.contains("http") || src.contains(".vtt") || src.contains(".js")) return
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
                    this.referer = pageUrl
                }
            )
        }

        // 2. Process each server page
        serverLinks.distinct().forEach { serverUrl ->
            val serverResponse = app.get(serverUrl, referer = url).text
            
            // Search for direct links in server page first
            hlsPattern.findAll(serverResponse).forEach { addSource(it.groupValues[1], serverUrl) }
            srcPattern.findAll(serverResponse).forEach { addSource(it.groupValues[1], serverUrl) }

            // 3. Follow embedded iframes (e.g. mp4plus.cyou, anafast.cyou)
            val iframeSrcs = Regex("""<iframe\s+[^>]*src=["']([^"']+)["']""").findAll(serverResponse)
                .map { it.groupValues[1] }
                .filter { it.contains(".cyou") || it.contains("embed") }
                .toList()

            iframeSrcs.forEach { iframeUrl ->
                val fixedIframeUrl = fixUrl(iframeUrl, serverUrl)
                val iframeResponse = app.get(fixedIframeUrl, referer = serverUrl).text
                
                hlsPattern.findAll(iframeResponse).forEach { addSource(it.groupValues[1], fixedIframeUrl) }
                srcPattern.findAll(iframeResponse).forEach { addSource(it.groupValues[1], fixedIframeUrl) }
            }
        }


        return sources.distinctBy { it.url }.sortedByDescending { it.url.contains(".m3u8") }
    }
    private fun fixUrl(url: String, baseUrl: String = mainUrl): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) {
            val uri = java.net.URI(baseUrl)
            return "${uri.scheme}://${uri.host}$url"
        }
        return "$baseUrl/$url"
    }
}
