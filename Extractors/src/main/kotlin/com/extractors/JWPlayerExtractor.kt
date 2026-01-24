package com.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.nicehttp.Requests
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName

open class JWPlayer : ExtractorApi() {
    private val app = Requests()
    override val name = "JWPlayer"
    override val mainUrl = "https://www.jwplayer.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            with(app.get(url).document) {
                val data = this.select("script").mapNotNull { script ->
                    val scriptData = script.data()
                    when {
                        scriptData.contains("sources: [") -> {
                            scriptData.substringAfter("sources: [")
                                .substringBefore("],").replace("'", "\"")
                        }
                        scriptData.contains("otakudesu('") -> {
                            scriptData.substringAfter("otakudesu('")
                                .substringBefore("');")
                        }
                        scriptData.contains("file:") -> {
                            "[{\"file\": \"${scriptData.substringAfter("file:").substringBefore(",").trim()}\"}]"
                        }
                        else -> null
                    }
                }.firstOrNull()

                data?.let { sourcesData ->
                    tryParseJson<List<ResponseSource>>(sourcesData)?.forEach { source ->
                        if (source.file.isNotEmpty()) {
                            val quality = getQualityFromName(
                                Regex("(\\d{3,4}p)").find(source.file)?.groupValues?.get(1)
                                    ?: source.label?.let { Regex("(\\d{3,4}p)").find(it)?.groupValues?.get(1) }
                            )
                            
                            sources.add(
                                newExtractorLink(name, name, source.file, if (source.file.contains(".m3u8") == true || source.type == "hls") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
        return sources
    }

    private data class ResponseSource(
        @JsonProperty("file") val file: String,
        @JsonProperty("type") val type: String?,
        @JsonProperty("label") val label: String?
    )
}

class VidHDJW : JWPlayer() {
    override val name = "VidHD"
    override val mainUrl = "https://vidhd.fun"
}
class Vidbom : JWPlayer() {
    override val name = "Vidbom"
    override val mainUrl = "https://vidbom.com"
}
class Vadbam : JWPlayer() {
    override val name = "Vadbam"
    override val mainUrl = "https://vadbam.com/"
}
class Vidshar : JWPlayer() {
    override val name = "Vidshar"
    override val mainUrl = "https://vidshar.org/"
}
