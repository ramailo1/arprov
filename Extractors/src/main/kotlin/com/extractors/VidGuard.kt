package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.Requests
import java.net.URLDecoder
import android.util.Base64

open class VidGuard : ExtractorApi() {
    private val app = Requests()
    override val name = "VidGuard"
    override val mainUrl = "https://vidguard.to"
    override val requiresReferer = false

    private fun decryptUrl(encryptedUrl: String): String {
        return try {
            if (encryptedUrl.contains("base64,")) {
                val base64Data = encryptedUrl.substringAfter("base64,")
                String(Base64.decode(base64Data, Base64.DEFAULT))
            } else {
                URLDecoder.decode(encryptedUrl, "UTF-8")
            }
        } catch (e: Exception) {
            encryptedUrl
        }
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            val doc = app.get(url).document
            val scriptElements = doc.select("script")
            
            for (script in scriptElements) {
                val scriptData = script.data()
                
                if (scriptData.contains("vidguard") || scriptData.contains("encrypt") || scriptData.contains("decrypt")) {
                    try {
                        val encryptedPatterns = listOf(
                            Regex("""['"]([^'"]*vidguard[^'"]*(?:=|%3D)[^'"]*)['"]"""),
                            Regex("""['"]([^'"]*base64[^'"]*[^'"]*)['"]"""),
                            Regex("""['"]([^'"]*encrypt[^'"]*[^'"]*)['"]"""),
                            Regex("""url['"]\s*=\s*['"]([^'"]+)['"]""")
                        )
                        
                        for (pattern in encryptedPatterns) {
                            val matches = pattern.findAll(scriptData)
                            for (match in matches) {
                                var encryptedUrl = match.groupValues[1]
                                val decryptedUrl = decryptUrl(encryptedUrl)
                                
                                if (decryptedUrl.isNotEmpty() && decryptedUrl.contains("http")) {
                                    sources.add(
                                        newExtractorLink(name, name, decryptedUrl, if (decryptedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
            }
            
            val videoElements = doc.select("video source, video")
            for (element in videoElements) {
                val src = element.attr("src")
                if (src.isNotEmpty() && (src.contains("vidguard") || src.contains(".m3u8") || src.contains(".mp4"))) {
                    sources.add(
                        newExtractorLink(name, name, src, if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                    )
                }
            }
            
            // Special elements with data attributes
            val specialElements = doc.select("[data-url], [data-link], [data-video]")
            for (element in specialElements) {
                 val dataUrl = element.attr("data-url")
                val dataLink = element.attr("data-link")
                val dataVideo = element.attr("data-video")
                val link = dataUrl.ifEmpty { dataLink }.ifEmpty { dataVideo }
                
                if (link.isNotEmpty() && (link.contains("vidguard") || link.contains(".m3u8") || link.contains(".mp4"))) {
                    val finalLink = if (link.contains("=") || link.contains("%3D")) {
                        decryptUrl(link)
                    } else {
                        link
                    }
                    
                    if (finalLink.contains("http")) {
                        sources.add(
                            newExtractorLink(name, name, finalLink, if (finalLink.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                        )
                    }
                }
            }
            
        } catch (e: Exception) {
        }
        
        return sources
    }
}

class VidGuardTo : VidGuard() {
    override val name = "VidGuard"
    override val mainUrl = "https://vidguard.to"
}

class VidGuardVp : VidGuard() {
    override val name = "VidGuard"
    override val mainUrl = "https://vidguard.vp"
}

class VidGuardIo : VidGuard() {
    override val name = "VidGuard"
    override val mainUrl = "https://vidguard.io"
}
