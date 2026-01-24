package com.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.nicehttp.Requests
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.Qualities


open class Govad : ExtractorApi() {
    private val app = Requests()
    override val name = "Govad"
    override val mainUrl = "https://govad.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        val regcode = """$mainUrl/embed-(\w+)""".toRegex()
        val code = regcode.find(url)?.groupValues?.getOrNull(1)
        val link = "$mainUrl/$code"
        with(app.get(link).document) {
            val data = this.select("script").mapNotNull { script ->
                if (script.data().contains("sources: [")) {
                    script.data().substringAfter("sources: [")
                        .substringBefore("],").replace("file", "\"file\"").replace("label", "\"label\"").replace("type", "\"type\"")
                } else {
                    null
                }
            }.firstOrNull()

            data?.let { jsonData ->
                tryParseJson<List<ResponseSource>>("[$jsonData]")?.forEach {
                    sources.add(
                        newExtractorLink(
                            name,
                            name,
                            it.file,
                            if(it.file.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                }
            }
        }
        return sources
    }

    private data class ResponseSource(
        @JsonProperty("file") val file: String,
        @JsonProperty("type") val type: String?,
        @JsonProperty("label") val label: String?
    )
}


