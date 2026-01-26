# 🎬 CloudStream Extensions Arabic - Professional & Organized

<p align="center">
  <img src="https://img.shields.io/badge/Extensions-50+-green.svg" alt="Extensions Count">
  <img src="https://codeberg.org/dhomred/cloudstream-extensions-arabic/workflows/Build/badge.svg" alt="Build Status">
  <img src="https://img.shields.io/badge/badge/Language-Kotlin-blue.svg" alt="Language">
  <img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License">
</p>

<p align="center">
  <strong>Advanced Arabic Extensions for CloudStream – Clean, Organized, and High-Performance</strong>
</p>

---

## 📋 Table of Contents

- [🚀 Quick Installation](#-quick-installation)
- [📦 Extension Status](#-extension-status)
- [🔍 Supported Extractors](#-supported-extractors)
- [🏗️ Project Architecture](#-project-architecture)
- [🛠️ Development & Build](#-development--build)
- [📝 Contributing](#-contributing)
- [📞 Support & Contact](#-support--contact)
- [⚖️ License](#-license)

---

## 🚀 Quick Installation

### Method 1: Repository Link (Recommended)
1. Open **CloudStream** app.
2. Go to **Settings** ⚙️ > **Extensions**.
3. Tap **Add Repository** (+).
4. Paste the following URL:
   ```text
   https://raw.githubusercontent.com/ramailo1/arprov/main/repo.json
   ```

### Method 2: Manual Installation
1. Download `.zip` from [Releases](https://github.com/ramailo1/arprov/releases).
2. Extract to `cloudstream/extensions/` folder.
3. Restart the app.

---

## 📦 Extension Status

We maintain a high standard of quality. Plugins are categorized by their current operational status.

### ✅ Working (Active & Functional)
These plugins are fully operational and regularly maintained.

| Provider | Type | Language |
| :--- | :--- | :--- |
| **Akwam** | Movies & Series | Arabic |
| **Fushaar** | International | Arabic |
| **ArabSeed** | Movies & Series | Arabic |
| **Anime4up Pack** | Anime | Arabic |
| **MovizLands** | Movies & Series | Arabic |

### 🛠️ Working On (WIP / Under Fix)
New plugins or those undergoing maintenance/updates.

| Provider | Status | Progress |
| :--- | :--- | :--- |
| **AnimeBlkom** | 🌐 Hybrid WebView | `Testing` |
| **RistoAnime** | 🛠️ WebView Enabled | `Soon` |
| **Shahid4u** | 🛠️ URL Updated | `Soon` |
| **Shed4u** | 🛠️ URL Updated | `Soon` |
| **Cima4u Actor** | 🛠️ Refactoring | `Soon` |
| **Cima4u Shop** | 🛠️ Extractors Fix | `Soon` |
| **EgyDead** | 🔍 Testing | `Soon` |
| **GateAnime** | 🏗️ Initial Build | `Soon` |

### ❌ Down (Currently Broken)
These plugins are currently non-functional due to site changes or API issues.

| Provider | Known Issue | Status |
| :--- | :--- | :--- |
| **Animeiat** | 🛑 Broken Logic | ⏸️ Paused |
| **Cima4u** | 🛑 Broken Logic | ⏸️ Paused |
| **CimaClub** | 🛑 Broken Logic | ⏸️ Paused |
| **CimaNow** | 🛑 Broken Logic | ⏸️ Paused |
| **CimaLeek** | 🛑 Broken Logic | ⏸️ Paused |
| **FajerShow** | 🛑 Broken Logic | ⏸️ Paused |
| **FaselHD** | 🛑 Broken Logic | ⏸️ Paused |
| **MyCima** | 🛑 Broken Logic | ⏸️ Paused |
| **ShahidMBC** | 🛑 Broken Logic | ⏸️ Paused |
| **TopCinema** | 🛑 Broken Logic | ⏸️ Paused |
| **EgyBest** | Domain Change / JS Captcha | ⏸️ Paused |

---

## 🔍 Supported Extractors

Our extensions utilize a robust set of underlying extractors to ensure link stability.

- **Fast Streaming**: StreamTape, DoodStream, MixDrop
- **High Quality**: FileMoon, MegaUp, Vidmoly
- **Reliability**: JWPlayer, LinkBox, VidHD, VoeSx

---

## 🏗️ Project Architecture

```text
cloudstream-extensions-arabic/
├── AkwamProvider/        # Provider Source Code
├── Extractors/           # Video Link Extractors
├── docs/                 # Documentation & Guides
├── scripts/              # Build & Analysis Tools
├── repo.json             # Repository Manifest
└── build.gradle.kts      # Global Build Config
```

---

## 🛠️ Development & Build

### Requirements
- **Java 11+** & **Kotlin 1.8+**
- **Gradle 7.0+**
- **Python 3.8+** (for management scripts)

### Build Commands
```bash
# Build all extensions
./gradlew build

# Run quality analysis
python scripts/analyze_issues.py
```

---

## 📝 Contributing

We value your help! To contribute:
1. **Fork** the repository.
2. Create your **Feature Branch** (`git checkout -b feature/NewProvider`).
3. **Commit** your changes.
4. **Push** to the branch and open a **Pull Request**.

---

## 📞 Support & Contact

- **Report Issues**: [Issue Tracker](https://github.com/ramailo1/arprov/issues)
- **Discussions**: [Community Hub](https://github.com/ramailo1/arprov/discussions)

---

## ⚖️ License

Distributed under the **MIT License**. See `LICENSE` for more information.

---

<p align="center">
  ⭐ <strong>If you find this repository useful, please give it a star!</strong> ⭐
</p>