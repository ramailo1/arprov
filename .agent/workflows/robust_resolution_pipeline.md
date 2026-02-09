---
description: A robust, multi-agent style pipeline for complex resolutions. Includes Enhancement, Resolution, Debugging, Verification, and Documentation phases.
---

# Robust Resolution Pipeline

Use this workflow when the user requests a complex fix or explicitly asks for the "multi-agent" approach. This simulates 5 distinct expert agents working in sequence.

## Phase 1: The Enhancement Agent (Architect)
**Goal:** Analyze the request and suggest the best approach and extra enhancements.
1.  **Analyze**: Read the request and relevant files.
2.  **Suggest**: Don't just fix the bug/feature. Propose:
    - Code quality improvements (Clean Code).
    - Robustness checks (Null safety, edge cases).
    - Architecture alignment (Patterns used in the project).
3.  **Output**: Update `task.md` and `implementation_plan.md` with these enhancements.

## Phase 2: The Resolution Agent (Engineer)
**Goal:** Implement the solution based on the plan.
1.  **Execute**: Write the code.
2.  **Constraint**: Use `replace_file_content` or `rewrite_file` strictly following the plan.
3.  **Focus**: Pure implementation speed and accuracy.

## Phase 3: The Debugging Agent (Investigator)
**Goal:** Pre-verify the solution logic and check for "invisible" issues.
1.  **Review**: Look at the code *just written* in Phase 2.
2.  **Simulate**: "Dry run" the code in your head.
    - "What if the list is empty?"
    - "What if the network fails?"
    - "Is this coroutine safe?"
3.  **Fix**: If issues are found, apply hotfixes immediately BEFORE running the build.

## Phase 4: The Verification Agent (Tester)
**Goal:** Prove it works.
1.  **Build**: Run the project build command (e.g., `.\gradlew.bat assembleDebug`).
2.  **Test**: If possible, write and run a test case.
3.  **Validate**: Check logs/output. If it fails, kick back to Phase 3.

## Phase 5: The Documentation Agent (Scribe)
**Goal:** Ensure no knowledge is lost.
1.  **Artifacts**: Update `walkthrough.md` with exactly what was done and tested.
2.  **Code Docs**: Ensure the new code has KDoc/comments if complex.
3.  **Report**: Final `notify_user` should be structured clearly with "Changes", "Verification", and "Notes".

---

## Phase 6: Proven Strategies (Knowledge Base)
This section contains "brilliant thinking" and proven strategies for specific issues.

### Anti-Scraping: Handling `hide_my_HTML_` Obfuscation
**Problem**: Sites wrap their content in a script `hide_my_HTML_` to prevent Jsoup selectors from working.
**Solution**: Decode the Base64 content manually before parsing.
**Snippet (Kotlin)**:
```kotlin
private fun decodeHtml(doc: Document): Document {
    val docStr = doc.toString()
    if (!docStr.contains("hide_my_HTML_")) return doc

    val encoded = Regex("""hide_my_HTML_=['"]([^"']+)['"]""").find(docStr)?.groupValues?.get(1) ?: return doc
    val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), kotlin.text.Charsets.UTF_8)
    // Option 1: Return full new document
    // return Jsoup.parse(decoded)
    // Option 2: Append to body (Best for preserving scripts/styles)
    doc.body().append(decoded)
    return doc
}
```
**Advanced**: If the string is double-encoded or chopped, look for a custom `decodeObfuscatedString` function (see `CimaNowProvider`).

### Raw Server Parsing (No-Extractor)
**Problem**: Some "servers" aren't iframe embeds but just list raw video files in the HTML.
**Solution**: Use Regex to find specific file extensions linked to quality labels.
**Snippet**:
```kotlin
// Extract [720p] /uploads/file.mp4
Regex("""\[(\d+p)]\s+(/uploads/[^\"]+\.mp4)""").findAll(html).forEach { match ->
    val quality = match.groupValues[1]
    val link = fixUrl(match.groupValues[2])
    callback(newExtractorLink(name, name, link, ExtractorLinkType.VIDEO, getQualityFromName(quality)))
}
```

---
**Trigger:** Use this flow for significant refactors or when `task_boundary` complexity is High.
