package com.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.Requests

open class PixelDrain : ExtractorApi() {
    private val app = Requests()
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            val fileId = extractFileId(url)
            
            if (fileId.isNotEmpty()) {
                val downloadUrl = "https://pixeldrain.com/api/file/$fileId"
                sources.add(
                    newExtractorLink(name, name, downloadUrl, ExtractorLinkType.VIDEO)
                )
            }
            
        } catch (e: Exception) {
        }
        
        return sources
    }
    
    private fun extractFileId(url: String): String {
        val patterns = listOf(
            Regex("""pixeldrain\.com/u/([a-zA-Z0-9]+)"""),
            Regex("""pixeldrain\.com/api/file/([a-zA-Z0-9]+)"""),
            Regex("""([a-zA-Z0-9]{8,})""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return ""
    }
}

class PixelDrainCom : PixelDrain() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.com"
}
