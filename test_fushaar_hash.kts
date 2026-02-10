import java.util.Base64

fun main() {
    val hash = "YW5hbW92ID0__IGh0dHBzOi8vdi5hZmxhbS5uZXdzL2VtYmVkLTAwOWo5N2JmczV2OS5odG1sCnZpZHNwZWVkID0__IGh0dHBzOi8vdy5hbmFtb3YuY2FtL2VtYmVkLXI2cnhwbGIwdnJhdC5odG1sCnZpZG9iYSA9PiBodHRwczovL3ZpZHNwZWVkLmNjL2VtYmVkLXAzcGJ6aGI1ZWY0by5odG1s"
    
    val cleanHash = hash.replace("__", "/").replace("_", "+")
    val decoded = String(Base64.getDecoder().decode(cleanHash))
    
    println("Decoded Content:\n$decoded")
    
    val regex = Regex("""(.*?)\s*[=\-][>?]\s*(https?://[^\s\n]+)""")
    val matches = regex.findAll(decoded)
    
    println("\nExtracted Sources:")
    matches.forEach { match ->
        val name = match.groupValues[1].trim()
        val url = match.groupValues[2].trim()
        println(" - $name: $url")
    }
}
main()
