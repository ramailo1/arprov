# 🎬 ArProv - Professional Arabic CloudStream Extensions

<p align="center">
  <img src="https://img.shields.io/badge/Project-ArProv-blueviolet?style=for-the-badge&logo=android" alt="ArProv">
  <img src="https://img.shields.io/badge/Kotlin-Expert-7F52FF?style=for-the-badge&logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/Status-Optimized-green?style=for-the-badge" alt="Status">
</p>

<p align="center">
  <strong>The Gold Standard for Arabic Content on CloudStream. Clean, High-Performance, and Community-Driven.</strong>
</p>

---

## 🏗️ System Architecture

ArProv is designed with a modular architecture that separates content sourcing (Providers) from video link retrieval (Extractors). This ensures maximum stability and easy maintenance.

```mermaid
graph TD
    User([User App]) --> Core[CloudStream Core]
    Core --> Providers[ArProv Providers Library]
    
    subgraph "ArProv Logic"
        Providers --> CP[Cima4u Forum]
        Providers --> AS[ArabSeed]
        Providers --> AK[Akwam]
        
        CP --> tier[Tiered Poster Logic]
        CP --> ajax[AJAX Player Extraction]
    end
    
    ajax --> Ext[Common Extractors Box]
    Ext --> FM[FileMoon]
    Ext --> DM[DoodStream]
    Ext --> ST[StreamTape]
    
    FM --> CDN[(CDN / Video Hosts)]
    DM --> CDN
    ST --> CDN
    
    style ArProv Logic fill:#f9f,stroke:#333,stroke-width:2px
```

---

## 🚀 Key Features

*   🛡️ **Tiered Poster Extraction**: proprietary logic to ensure 100% catch rate for movie posters using local UI scoping and safe meta-tag fallbacks.
*   ⚡ **AJAX Player Retrieval**: Direct communication with server backends (`admin-ajax.php`) to bypass obfuscated frontend code.
*   🌍 **Universal Compatibility**: Optimized for Android, Android TV, and Web interfaces.
*   💎 **Premium Quality**: Support for multi-resolution streaming (4K, 1080p, 720p).

---

## 📦 Extension Directory

### ✅ Active & Optimized
| Provider | Genre | Package Name |
| :--- | :--- | :--- |
| **Cima4u Forum** | Premium Movies/Series | `com.lagradost.cloudstream3.cima4uforum` |
| **ArabSeed** | Movies & Series | `com.lagradost.cloudstream3.arabseed` |
| **Akwam** | General Entertainment | `com.lagradost.cloudstream3.akwam` |
| **FaselHD** | Arabic & International | `com.lagradost.cloudstream3.faselhd` |
| **Anime4up** | Anime & Movies | `com.lagradost.cloudstream3` |
| **Animeiat** | Dedicated Anime | `com.lagradost.cloudstream3.animeiat` |
| **MovizLand** | Movies & Series | `com.lagradost.cloudstream3.movizlands` |
| **Cima4U** | Movies & Series | `com.lagradost.cloudstream3.cima4u` |
| **CimaClub** | Movies & Series | `com.lagradost.cloudstream3.cimaclub` |
| **CimaLeek** | Movies & Series | `com.lagradost.cloudstream3.cimaleek` |
| **CimaNow** | Movies & Series | `com.lagradost.cloudstream3.cimanow` |
| **QisatTv** | Series & Story | `com.lagradost.cloudstream3.qisat` |
| **Fushaar** | Movies & Series | `com.lagradost.cloudstream3.fushaar` |
| **EgyBest** | Movies & Series | `com.lagradost.cloudstream3.egybest` |
| **EgyDead** | Movies & Series | `com.lagradost.cloudstream3.egydead` |
| **MyCima** | Movies & Series | `com.lagradost.cloudstream3.mycima` |
| **GateAnime** | Anime & Movies | `com.lagradost.cloudstream3.gateanime` |

> [!NOTE]
> **Fushaar**: Some posters may be missing. This is an issue with the source website, not the extension.

### 🛠️ In Development (Coming Soon)
- [ ] **Shahid4u** - `Maintenance`
- [ ] **RistoAnime** - `Development`

+
+
### 🚫 Blocked / Not Working
These providers are currently blocked by their respective websites (e.g., Cloudflare, geoblocking) or have significant issues.

| Provider | Reason | Status |
| :--- | :--- | :--- |
| **AnimeBlkom** | Permanent Block | `Disabled` |
| **FajerShow** | Domain Issues | `Disabled` |
| **ShahidMBC** | Geo-blocked / DRM | `Disabled` |

---

## 🔧 Installation & Setup

> [!IMPORTANT]
> **Required Component**: You **MUST** install the **Extractors** extension from this repository. Most providers (like Fushaar, Akwam, etc.) rely on the common Extractors box to retrieve video links. Without it, you will see "No link found" errors.

### Instant Repositories
You can use the **shortcode** directly in CloudStream:
```text
arprov
```

Or copy and paste this URL into your CloudStream settings:

```text
https://raw.githubusercontent.com/ramailo1/arprov/main/repo.json
```

### Manual Build
```bash
./gradlew build
```

---

## 📝 Attribution & Legal

> [!NOTE]
> This project is a refined fork of [dhomred/cloudstream-extensions-arabic-v2](https://github.com/dhomred/cloudstream-extensions-arabic-v2).
> Re-architected and maintained by **ramailo1**.

## 🏆 Hall of Fame
We honor the brilliant minds who have contributed to the success of ArProv.

| Contributor | Achievement | Impact |
| :--- | :--- | :--- |
| **[Abodabodd](https://github.com/Abodabodd)** | **Anti-Scraping & Server Logic** | Unlocked `CimaNow` provider capabilities. |
| **[dhomred](https://github.com/dhomred)** | **Original Architect** | Foundation of the v2 extensions. |

Want to be here? Submit a fix or new provider!

Distributed under the **MIT License**. See [LICENSE](LICENSE) for more information.

---

<p align="center">
  ⭐ <strong>Support our work by starring this repository!</strong> ⭐
</p>