# AI-V0 — Closure Phase Test Report

**Date**: 2026-09-26 · **Environment**: Linux sandbox, JDK 21 (Temurin-equivalent OpenJDK 21.0.2), Gradle 9.3.1, AGP 9.1.1, Kotlin 2.2.10, Android SDK Platform 36.1, Robolectric 4.16.1, Roborazzi 1.59.0.

---

## 1. Summary

| Metric | Value |
|---|---|
| Test classes executed | **157** |
| Tests executed | **1176** |
| **Passed** | **1176** |
| Failed | **0** |
| Blocked | **0** |
| Unverified (documented below) | 2 categories (see §4) |
| `compileDebugKotlin` (main sources) | **PASS** |
| `testDebugUnitTest` (full suite) | **PASS** (`BUILD SUCCESSFUL`) |
| `assembleDebug` (APK) | **PASS** — `app-debug.apk` produced (102,247,699 bytes) |

The APK is a real, installable debug build (debug-signed with the AGP
auto-generated keystore). No release keystore exists in this environment,
so `bundleRelease` was not attempted — this is an environment limitation,
not a project state claim.

## 2. Closure-phase additions covered by tests

| Contract | Test class / method(s) | Result |
|---|---|---|
| Immutable invocation scope — acceptance-pinned tuple survives post-acceptance switch; exploding live providers never consulted | `gapclosure/ImmutableInvocationScopeTest` (5 tests: adversarial switch, resume-stable identity, null-project honesty, legacy seam, codec round-trip) | PASS |
| v3 package portability — sessions WITH turns + timeline, tasks WITH real lifecycle + checkpoint, artifacts | `gapclosure/ClosureTransferAtomicityTest.v3 round trip carries turns timeline tasks artifacts` | PASS |
| Count-contract rejection — a tampered manifest count refuses the import with destination untouched | `ClosureTransferAtomicityTest` (COUNT_CONTRACT_MISMATCH) | PASS |
| Identity-preserving move — every scoped row rebound + verified; source cleared | `ClosureTransferAtomicityTest.identity preserving move rebinds every scoped row` | PASS |
| Task lifecycle honesty — imported RUNNING stays RUNNING (never blanket COMPLETED) | `ClosureTransferAtomicityTest` (inside round-trip) | PASS |
| Artifact versioning — create/read/list/rollback, append-only history, current-pointer advance | `ClosureTransferAtomicityTest.artifact version lifecycle` | PASS |
| Folder understanding — grounded mode reports read/skipped files honestly; ATTACHMENT_ONLY never claims understanding | `ClosureTransferAtomicityTest.folder understanding` | PASS |
| Bidi isolation — LTR technical runs isolated inside Arabic prose; pure-LTR untouched | `ClosureTransferAtomicityTest.bidi sanitizer` | PASS |
| Package safety (pre-existing, updated to v3) — digest, tamper, traversal, secrets, zip-slip, clone, verified move | `convergence/ProjectPackageTransferTest` (19 tests) | PASS |
| Composer consolidation — agent/model chip no longer duplicated in composer (IA §13) | `presentation/ChatInputAcceptanceTest` (updated contract) | PASS |
| Migration v19→v20 + full chain validation | `convergence/Migration18to19Test`, `Migration17to18Test`, `MigrationChainValidationTest`, `governance/GovernancePersistenceTest` (version 20) | PASS |
| Studio approval/turn persistence suites under the new durable-scope seam | `presentation/viewmodel/StudioViewModel*Test` (4 suites, ~90 tests) | PASS |
| Governance persistence/revoke-model round-trips | `governance/*` suites | PASS |

## 3. Pre-existing suites (regression)

All 140+ pre-existing test classes remain green, including the
adversarial/convergence/gapclosure families (scope isolation, session
durability, process-death recovery, egress scoping, RAG honesty,
workspace atomicity, feed isolation, navigation shell, chat surface
Roborazzi matrices incl. the new closure captures).

## 4. Unverified / environment-limited (honest record)

1. **Keyboard-state visual fixtures (§20 keyboard matrix)** — Robolectric
   does not reproduce a live IME; the composer's `imePadding` contract is
   covered by the existing behavioral tests (`BottomBarImeGatingTest`,
   composer acceptance tests), but pixel-level keyboard-visible captures
   are NOT produced in this environment. **Status: unverified.**
2. **TalkBack semantics + hardware-keyboard navigation suites (§18)** —
   no screen reader / input-injection harness exists here. The semantic
   labels exist (contentDescriptions, visible honest-reason text);
   automated a11y runs are **unverified**.
3. **On-device E2E (`connectedDebugAndroidTest`)** — no emulator/device in
   this sandbox. The CI workflow (`e2e-device.yml`) remains the execution
   path for the device suite. **Status: not run here.**

## 5. Flakiness observations (documented honestly)

During full-suite iterations under this sandbox's constrained CPU, two
pre-existing Robolectric Compose tests occasionally timed out under load
(`AppLockServiceAuditTest`, `Slice7ScreensRoborazziMatrixTest`,
`NavigationShellTest` — different ones per run) while passing in isolated
class runs and in the final full-suite run. The final recorded run
(above) is fully green with zero failures; individual reruns of each
flaky-under-load class were also green. No code change in this patch is
 implicated in those timeouts (verified: the failing assertions are
unrelated wait-until conditions under parallel Robolectric load).

## 6. Build artifacts

- `app-debug.apk` — built from THIS exact source state
  (`assembleDebug` → BUILD SUCCESSFUL), 102 MB (includes ONNX runtime +
  Firebase App Check).
- Unit-test XML results: `app/build/test-results/testDebugUnitTest/`
  (157 classes).
- Screenshot references: `app/src/test/screenshots/` (existing matrices +
  the new closure captures written by
  `ClosureTechnicalRenderersRoborazziTest`).
