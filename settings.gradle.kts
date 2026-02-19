rootProject.name = "ArProv"

val disabled = listOf<String>(
    "AnimeBlkomProvider",
    "FajerShowProvider", 
    "ShahidMBCProvider",
    "TopCinemaProvider"
)

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
