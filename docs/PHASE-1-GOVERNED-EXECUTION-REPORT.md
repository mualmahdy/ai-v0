# PHASE 1 — GOVERNED INTELLIGENT EXECUTION & SECURE RUNTIME
## Implementation Report (branch `phase-1-governed-intelligent-execution`)

- Repository: https://github.com/mualmahdy/ai-v0
- Base: `main` @ `98e7508` (Governance Phase)
- Scope delivered in this branch: the **Admission Control Pipeline**, **Workspace
  Path Policy**, **Sandbox Lifecycle**, **Human Approval Gate**, **Governed
  Coding Toolchain** (incl. `apply_patch` with optimistic concurrency), audit
  integration, and DI wiring.

---

## A. Status legend (same convention as previous phases)

`IMPLEMENTED / INTEGRATED / TESTED / ANDROID-VALIDATED / PRODUCTION-PROVEN / PARTIAL / BLOCKED / UNKNOWN`

## B. What was built (all NEW code — no mocks)

| # | File | Layer | Status |
|---|------|-------|--------|
| 1 | `domain/core/security/governance/AdmissionControlModels.kt` | domain | IMPLEMENTED / TESTED |
| 2 | `domain/core/security/governance/WorkspacePathPolicy.kt` | domain | IMPLEMENTED / TESTED |
| 3 | `domain/core/runtime/SandboxLifecycleModels.kt` | domain | IMPLEMENTED / TESTED |
| 4 | `domain/core/tools/patch/FilePatchModels.kt` | domain | IMPLEMENTED / TESTED |
| 5 | `domain/ports/governed/AdmissionPorts.kt` | domain ports | IMPLEMENTED / TESTED |
| 6 | `application/governed/AdmissionControlService.kt` | application | IMPLEMENTED / TESTED |
| 7 | `application/governed/HumanApprovalGate.kt` | application | IMPLEMENTED / TESTED |
| 8 | `application/governed/SandboxLifecycleService.kt` | application | IMPLEMENTED / TESTED |
| 9 | `application/governed/CodingToolchainService.kt` | application | IMPLEMENTED / TESTED |
| 10 | `infrastructure/governed/InMemoryHumanApprovalStore.kt` | infrastructure | IMPLEMENTED (PARTIAL durability — see D) |
| 11 | `presentation/di/AppContainer.kt` | wiring | INTEGRATED |
| 12 | 6 test files (59 tests) | test | TESTED |

## C. The ordered admission pipeline (Workstream A core)

Every tool request MUST traverse, in order, with fail-fast semantics and a
full stage trace recorded on every decision:

```
PARAMETER_VALIDATION → PRINCIPAL_AUTHORIZATION → RISK_CLASSIFICATION
→ SECURITY_POLICY (ceiling) → WORKSPACE_SCOPE_VALIDATION → PATH_POLICY
→ BUDGET_AUTHORIZATION → RATE_LIMIT → HUMAN_APPROVAL → SANDBOX_ADMISSION
```

Enforced invariants (each covered by at least one test):
1. **No bypass** — `CodingToolchainService.executeTool` refuses to execute
   unless the pipeline explicitly returned `ALLOWED`.
2. **Security is a ceiling** — security runs BEFORE budget; a budget ALLOW
   can never revisit a security DENY (test: `security deny is a CEILING`).
3. **Stage identity on deny** — every denial carries the exact stage +
   machine-readable reason (`UNKNOWN_TOOL`, `BUDGET_DENIED`,
   `RATE_LIMITED`, `PATH_POLICY:ContainmentViolation`,
   `SANDBOX_INSUFFICIENT_ISOLATION`, …).
4. **Audit both ways** — ALLOWED and DENIED are audited into the same
   Room-backed audit trail used by the rest of the system, with principal
   attribution and stage counts.

## D. Honest limitations (no fabricated claims)

| Item | Status | Detail |
|------|--------|--------|
| Approval durability | PARTIAL | `InMemoryHumanApprovalStore` is volatile. Fail-safe direction: process death only causes approval RE-REQUEST (never unauthorized execution). Room v11 entity + migration is the declared follow-up. |
| `run_code` / `run_tests` execution | BLOCKED (by design) | Android in-app execution cannot provide TRUE process/network isolation. Admission DENIES with `SANDBOX_INSUFFICIENT_ISOLATION` after human approval — honest refusal, never a fake success or a mock. Enabling requires an out-of-process executor (future phase). |
| Android build (`assembleDebug`) | NOT RUN in this environment | No Android SDK available. Verified instead with standalone Kotlin 2.0.21 compiler: **main + test sources compile clean**, **59/59 JVM tests pass** (JUnit Platform). Gradle verification commands to run on an SDK-equipped machine are listed in §F. |
| Device/emulator validation | NOT RUN | Same environment constraint. Robolectric/Room-in-memory + on-device validation remain required before PRODUCTION-PROVEN. |
| `write_file` check-then-write | HONEST NOTE | Hash check happens before write through the storage port; a narrow race window exists at the storage-adapter level. `apply_patch` (preferred primitive) revalidates hunk context atomically in the patch engine. |

## E. Test evidence (this environment)

- Compiler: standalone `kotlinc 2.0.21` (JVM target), sources = the 19 new/used
  Android-free files + 6 test files.
- Runner: JUnit Platform Console 1.10.2.
- Result: **59 tests found, 59 started, 59 successful, 0 failed, 0 skipped.**

Highlights:
- `pipeline stages run in canonical order for an allowed read` — order asserted.
- `security deny is a CEILING — budget allow cannot override`.
- `approval token is ONE-SHOT — replay is denied`.
- `run_code is honestly denied at SANDBOX_ADMISSION on Android`.
- `write_file with stale hash is refused — no silent overwrite`.
- `honest isolation refusal when TRUE_ISOLATION is required`.
- `path traversal is caught by the pipeline (defense in depth)` — the real
  `SecurityGuardService` catches traversal at SECURITY_POLICY; the path
  policy engine catches forbidden targets/containment at PATH_POLICY.

## F. Verification commands for an SDK-equipped machine

```bash
git checkout phase-1-governed-intelligent-execution
./gradlew :app:testDebugUnitTest   # includes the 59 new governed tests
./gradlew :app:assembleDebug       # full APK build
```

## G. Merge readiness

See `MERGE-READINESS-REPORT.md` and `merge-into-main.sh` in the merge kit:
the branch merges into `main` with **no conflicts** (verified by three-way
merge simulation and an actual test merge in a scratch clone), and `main`
is never force-pushed.
