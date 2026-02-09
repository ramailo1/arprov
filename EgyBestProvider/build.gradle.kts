version = 10

cloudstream {
    description = "EgyBest Provider - (In Progress)"
    authors = listOf( "ramailo1" )

	language = "ar"
	
    status = 0

    tvTypes = listOf( "TvSeries" , "Movie" , "Anime" )

    iconUrl = "https://www.google.com/s2/favicons?domain=www.egy.best&sz=%size%"
}

android {
    namespace = "com.lagradost.cloudstream3.egybest"
}

dependencies {
    implementation("org.mozilla:rhino:1.7.14")
}
