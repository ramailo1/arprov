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
**Trigger:** Use this flow for significant refactors or when `task_boundary` complexity is High.
