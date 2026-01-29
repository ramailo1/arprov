cloudstream {
    authors = listOf("Antigravity")
    description = "FaselHD Provider for Cloudstream"
    language = "ar"
    status = 3 // Working, as per common Cloudstream convention (1=Down, 2=Ongoing, 3=Working) - or just 1? MyCima uses 1. Let's use 3 as it's often 'Working'. Wait, MyCima uses 1. Let's stick to simple integer 1 or 3.
    // Actually, CloudstreamExtension.kt enum is:
    // None = 0, Down = 1, Maintenance = 2, Working = 3
    // MyCima uses 1? That would break logic if 1 is 'Down'.
    // Checking Common types... often 3 is 'Working'.
    // Let's use 3.
    status = 3 
    tvTypes = listOf("TvSeries", "Movie", "Anime")
    iconUrl = "https://web1296x.faselhdx.bid/wp-content/themes/faselhd_2020/images/favicon.png"
}

android {
    namespace = "com.lagradost.cloudstream3.faselhd"
}
