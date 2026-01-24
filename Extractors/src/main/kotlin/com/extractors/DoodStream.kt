package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.Requests

open class DoodStream : ExtractorApi() {
    private val app = Requests()
    override val name = "DoodStream"
    override val mainUrl = "https://doodstream.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            val doc = app.get(url).document
            
            // Ø§Ù„Ø¨Ø­Ø« Ø¹Ù† Ø±Ø§Ø¨Ø· Ø§Ù„ÙÙŠØ¯ÙŠÙˆ ÙÙŠ Ø§Ù„Ø³ÙƒØ±ÙŠØ¨ØªØ§Øª
            val scriptElements = doc.select("script")
            
            for (script in scriptElements) {
                val scriptData = script.data()
                
                // Ø§Ù„Ø¨Ø­Ø« Ø¹Ù† Ø§Ù„Ø¨Ù†ÙŠØ© Ø§Ù„Ø®Ø§ØµØ© Ø¨Ù€ DoodStream
                if (scriptData.contains("dsplayer") || scriptData.contains("dood")) {
                    try {
                        // Ø§Ø³ØªØ®Ø±Ø§Ø¬ Ø§Ù„Ø±Ø§Ø¨Ø· Ø¨Ø§Ø³ØªØ®Ø¯Ø§Ù… regex
                        val linkPattern = Regex("""dsplayer['"]\s*=\s*['"]([^'"]+)"""")
                        val linkMatch = linkPattern.find(scriptData)
                        
                        if (linkMatch != null) {
                            val videoLink = linkMatch.groupValues[1]
                            
                            sources.add(
                                newExtractorLink(name, name, videoLink, if (videoLink.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                            )
                            return sources
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
            
            // Ø§Ù„Ø¨Ø­Ø« Ø¹Ù† Ø¹Ù†Ø§ØµØ± Ø§Ù„Ù ÙŠØ¯ÙŠÙˆ Ø§Ù„Ù…Ø¨Ø§Ø´Ø±Ø©
            val videoElements = doc.select("video source, video")
            for (element in videoElements) {
                val src = element.attr("src")
                if (src.isNotEmpty() && (src.contains("dood") || src.contains("mp4"))) {
                    sources.add(
                        newExtractorLink(name, name, src, if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                    )
                }
            }
            
        } catch (e: Exception) {
            // ÙŠÙ…ÙƒÙ† Ø¥Ø¶Ø§ÙØ© logging Ù‡Ù†Ø§
        }
        
        return sources
    }
}

// Ø£Ù†ÙˆØ§Ø¹ Ù…Ø®ØªÙ„ÙØ© Ù…Ù† DoodStream
class DoodWs : DoodStream() {
    override val name = "DoodStream"
    override val mainUrl = "https://dood.ws"
}

class DoodTo : DoodStream() {
    override val name = "DoodStream"
    override val mainUrl = "https://dood.to"
}

class DoodWatch : DoodStream() {
    override val name = "DoodStream"
    override val mainUrl = "https://dood.watch"
}

