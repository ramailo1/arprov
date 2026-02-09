version = 1

cloudstream {
    authors = listOf("ramailo1")
    description = "QisatTv Provider"
    language = "ar"
    status = 1
    tvTypes = listOf("TvSeries", "Movie", "Anime", "Cartoon")
    iconUrl = "https://www.qisat.tv/favicon.ico"
}

android {
    namespace = "com.lagradost.cloudstream3.qisat"
}

dependencies {
    implementation("org.mozilla:rhino:1.7.14")
}
