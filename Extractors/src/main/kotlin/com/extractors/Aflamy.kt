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
        println("Aflamy Debug: Loading URL: $url with referer: $referer")
        val sources = mutableListOf<ExtractorLink>()
        val mainDoc = app.get(url, referer = referer).document
        
        // 1. Get all server links
        val serverLinks = mainDoc.select("a.aplr-link").mapNotNull { 
            val href = it.attr("abs:href")
            if (href.isNotBlank()) href else null
        }.toMutableList()
        println("Aflamy Debug: Found ${serverLinks.size} server links: $serverLinks")
        
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
            println("Aflamy Debug: Attempting to add source: $src")
            if (!src.contains("http") || src.contains(".vtt") || src.contains(".js")) return
            val qualityStr = Regex("""[0-9]{3,}""").find(src)?.value
            val qualityValue = qualities[qualityStr] ?: Qualities.Unknown.value
            
            println("Aflamy Debug: Adding source at quality: $qualityValue")
            sources.add(
                newExtractorLink(
                    name,
                    name,
                    src,
                    if (src.contains(".m3u8") || src.contains("playlist")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ).apply {
                    this.quality = qualityValue
                    this.referer = pageUrl
                }
            )
        }

        // 2. Process each server page
        serverLinks.distinct().filter { it.startsWith("http") }.forEach { serverUrl ->
            try {
                println("Aflamy Debug: Processing serverUrl: $serverUrl")
                val serverResponse = app.get(serverUrl, referer = url).text
                
                // Search for direct links in server page first
                hlsPattern.findAll(serverResponse).forEach { addSource(it.groupValues[1], serverUrl) }
                srcPattern.findAll(serverResponse).forEach { addSource(it.groupValues[1], serverUrl) }

                // 3. Follow embedded iframes (e.g. mp4plus.cyou, anafast.cyou)
                val iframeSrcs = serverResponse.let { html ->
                    Regex("""<iframe\s+[^>]*src=["']([^"']+)["']""").findAll(html).map { it.groupValues[1] }.toList()
                }.filter { it.contains(".cyou") || it.contains("embed") || it.contains("player") }
                
                println("Aflamy Debug: Found ${iframeSrcs.size} iframes: $iframeSrcs")

                iframeSrcs.forEach { iframeUrl ->
                    val fixedIframeUrl = fixUrl(iframeUrl, serverUrl)
                    println("Aflamy Debug: Following iframe: $fixedIframeUrl")
                    val iframeResponse = app.get(fixedIframeUrl, referer = serverUrl).text
                    
                    hlsPattern.findAll(iframeResponse).forEach { addSource(it.groupValues[1], fixedIframeUrl) }
                    srcPattern.findAll(iframeResponse).forEach { addSource(it.groupValues[1], fixedIframeUrl) }
                }
            } catch (e: Exception) {
                println("Aflamy Debug: Error processing server $serverUrl: ${e.message}")
            }
        }
        
        println("Aflamy Debug: Returning ${sources.size} sources")
        return sources.distinctBy { it.url }.sortedByDescending { it.url.contains(".m3u8") }
    }
    private fun fixUrl(url: String, baseUrl: String = mainUrl): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return try {
            val base = java.net.URL(baseUrl)
            java.net.URL(base, url).toString()
        } catch (e: Exception) {
            if (url.startsWith("/")) {
                val uri = java.net.URI(baseUrl)
                "${uri.scheme}://${uri.host}$url"
            } else {
                "$baseUrl/$url"
            }
        }
    }
}
