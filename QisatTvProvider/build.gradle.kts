version = 1

cloudstream {
    authors = listOf("ramailo1")
    description = "QisatTv Provider (Working)"
    language = "ar"
    status = 1
    tvTypes = listOf("TvSeries", "Movie", "Anime", "Cartoon")
    iconUrl = "https://www.qisat.tv/wp-content/uploads/2026/01/favicon.png"
}

android {
    namespace = "com.lagradost.cloudstream3.qisat"
}

dependencies {
    implementation("org.mozilla:rhino:1.7.14")
}
