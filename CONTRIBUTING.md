# 🤝 Contributing to ArProv

Thank you for your interest in contributing to ArProv! We welcome all developers who share our passion for high-quality Arabic content delivery.

## 🚀 Getting Started

### 1. Fork & Clone
```bash
git clone https://github.com/ramailo1/arprov.git
cd arprov
```

### 2. Environment Setup
- **JDK**: 11 or higher (JDK 17 recommended).
- **Gradle**: Included wrapper `./gradlew`.
- **IDE**: Android Studio or IntelliJ IDEA.

## 🔧 Coding Standards
- **Language**: Kotlin.
- **Naming**: PascalCase for Classes, camelCase for functions/variables.
- **Architecture**: Keep Providers modular and separate from Extractors.
- **Posters**: Always use the **Tiered Poster Extraction** pattern for consistency.

## 📤 Submission Process
1. Create a feature branch (`git checkout -b feature/cool-new-provider`).
2. Implement and test locally (`./gradlew assembleDebug`).
3. Commit with prefix (`[Cima4u] Fix: ...`).
4. Open a Pull Request with a clear description of changes.

---

### 🛠️ Modifications and Improvements
- **Standardized Workflow**: Streamlined the contribution process by removing legacy scripts.
- **Documentation First**: Every provider update now requires architecture-aligned documentation.
- **Quality Gates**: Enforced strict adherence to the new Tiered Extraction and AJAX retrieval standards.

> [!NOTE]
> We value the foundation provided by [dhomred/cloudstream-extensions-arabic-v2](https://github.com/dhomred/cloudstream-extensions-arabic-v2).
> Our goal is to push these extensions to the next level of professional reliability.
---