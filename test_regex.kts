fun main() {
    val movieUrl = "https://egibest.net/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d8%a7%d9%88%d9%86%d9%84%d8%a7%d9%8a%d9%86-%d9%81%d9%8a%d9%84%d9%85-whistle-2025-%d9%85%d8%aa%d8%ac%d9%85/"
    val seriesUrl = "https://egibest.net/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%85%d8%b3%d9%84%d8%b3%d9%84-the-burbs-%d8%a7%d9%84%d9%85%d9%88%d8%b3%d9%85-%d8%a7%d9%84%d8%a7%d9%88%d9%84-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-7-%d9%85%d8%aa%d8%ac%d9%85%d8%a9/"

    val movieRegex = Regex(".*/(movie|masrahiya|فيلم|مسرحية)/")
    val decodedMovie = java.net.URLDecoder.decode(movieUrl, "UTF-8")
    val decodedSeries = java.net.URLDecoder.decode(seriesUrl, "UTF-8")

    println("Movie URL: $decodedMovie")
    println("Is Movie (Current Regex): ${Regex(".*/(movie|masrahiya)/").containsMatchIn(decodedMovie)}")
    println("Is Movie (New Regex): ${movieRegex.containsMatchIn(decodedMovie)}")
    
    val seriesRegex = Regex("(مسلسل|الموسم|الحلقة|series|season|episode)")
    println("Series URL: $decodedSeries")
    println("Is Series (New Regex): ${seriesRegex.containsMatchIn(decodedSeries)}")
}
main()
