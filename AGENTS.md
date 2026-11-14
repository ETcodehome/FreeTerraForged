# Project Guidelines for AI Agents

## 1. Cross-Platform Portability (Strict Requirement)
FreeTerraForged is a cross-platform Minecraft mod targeting **Linux**, **macOS**, and **Windows**. Every line of code, test case, script, and configuration must build, test, and execute cleanly across all three operating systems.

### Absolute Prohibitions:
- **NO Windows Drive Letters**: Never hardcode `C:`, `D:`, or any drive letter prefix in source or test code.
- **NO Hardcoded User Home Paths**: Never include `/Users/<username>`, `/home/<username>`, `C:\Users\...`, `AppData`, or machine-specific directories.
- **NO Hardcoded Backslashes in Path Strings**: Always use `java.nio.file.Path`, `Path.of(...)`, or `/` separators.
- **NO Agent-Specific Artifact Paths in Code**: Never bake assistant conversation IDs, `.gemini`, `.cursor`, `.agent`, or assistant-internal artifact folders into Java source, tests, or Gradle files.

## 2. Test Artifacts & Report Standards
- Automated tests that generate debug images, heightmaps, relief maps, or validation reports must output to the standard project build directory:
  `Path.of("build", "reports", "preset-test-heightmaps")`
- Support dynamic overrides in the following order:
  1. System property: `System.getProperty("freeterraforged.test.heightmap.dir")`
  2. Environment variable: `System.getenv("FTF_TEST_OUTPUT_DIR")`
  3. Default fallback: `Path.of("build", "reports", "preset-test-heightmaps").toAbsolutePath().normalize()`
- When an AI agent needs to inspect or display generated images/reports in chat, the agent must read or copy them from the project's `build/reports/...` directory using its own workspace tools, **never** by hardcoding its internal conversation paths into the Java codebase.

## 3. Pre-Commit / Build Verification
Before completing any task:
1. Run `git grep -P '"[A-Za-z]:[/\\]"'` and `git grep -P '"[/\\](Users|home)[/\\]"'` to verify zero hardcoded paths exist.
2. Run `./gradlew checkPathPurity` to verify zero OS-specific paths exist across all sources.
3. Run `./gradlew test` (or `./gradlew check`) to verify clean cross-platform build and execution.

---

## 4. Automated Testing Structure & CI/CD Pipeline

FreeTerraForged utilizes a two-tier automated verification architecture combining lightweight unit tests with comprehensive headless server system tests across both Fabric and NeoForge mod loaders.

```
                    ┌───────────────────────────────────────────────┐
                    │               Developer / PR Push             │
                    └───────────────────────┬───────────────────────┘
                                            │
                                            ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ Tier 1: Automated Unit Tests (`./gradlew test` / `:common:test`)                       │
│ - JUnit 5 tests (`common/src/test/java/...`)                                           │
│ - Pre-check: `checkPathPurity` (blocks hardcoded OS/user paths)                         │
│ - Finalizer: `archiveUnitTestReport` -> `build/reports/.../unit_tests_report.txt`      │
└───────────────────────────────────────────┬────────────────────────────────────────────┘
                                            │
                                            ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ Tier 2: Headless System Tests (`:fabric:runPresetTest` & `:neoforge:runPresetTest`)     │
│ - Dedicated server bootstrap via `runPresetTestServer`                                 │
│ - Server tick hook: `PresetValidationRunner.runAllTests(server)`                       │
│ - Watchdog thread monitoring timeouts and deadlocks                                   │
│ - Outputs: `suite_summary_<loader>_<timestamp>_report.txt`, visual PNGs, local HTML   │
└───────────────────────────────────────────┬────────────────────────────────────────────┘
                                            │
                                            ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ GitHub Actions CI/CD Pipeline (`verify-pr.yml` -> `publish-verification.yml`)          │
│ 1. Triggered on PR label: `verify-build` (invalidated on push by `reset-label.yml`)    │
│ 2. Parallel Matrix Execution: runs Fabric & NeoForge runners in parallel               │
│ 3. Artifact Consolidation & Asset Publishing to orphan branch `verification-assets`   │
│ 4. Token Assertion Gatekeeper: validates all `baseTestTokens` ran on both loaders      │
│ 5. Automated PR comment with executive summary table, diagnostics, and visual relief   │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

### 4.1 Tier 1: Automated Unit Tests (`:common:test`)
- **Location**: `common/src/test/java/etcodehome/freeterraforged/...`
- **Execution**: Run via `./gradlew test` or `./gradlew :common:test`.
- **Purpose**: Fast, isolated regression tests for math functions, noise modules, cell generation, curve shapes, and carver continuity (e.g. `MountainRiverCutTest`, `ContinentalnessTest`).
- **Reporting Pipeline**:
  - Gradle task `archiveUnitTestReport` parses JUnit XML files from `common/build/test-results/test` and aggregates them into `build/reports/preset-test-heightmaps/unit_tests_report.txt`.
  - In headless system runs, `PresetValidationRunner.writeSuiteSummaryReport` ingests `unit_tests_report.txt`, namespacing each unit test as `unitTest.<Class>.<method>.<loader>` so unit tests are displayed alongside system tests in reports.

### 4.2 Tier 2: Headless Preset & Worldgen System Tests
- **Location**: Implemented in [PresetValidationRunner.java](file:///C:/Modding/MC/FreeTerraForged/common/src/main/java/etcodehome/freeterraforged/test/PresetValidationRunner.java).
- **Execution**: Run via `./gradlew :fabric:runPresetTest` or `./gradlew :neoforge:runPresetTest`.
- **Mechanism**:
  - Starts a headless dedicated server using Loom's `runPresetTestServer` task.
  - `MixinMinecraftServer` hooks `onServerTick`, invoking `PresetValidationRunner.runAllTests(server)`.
  - A background `TestWatchdog` thread monitors execution, dumping thread stack traces and generating a timeout report if any stage hangs.
  - Generates shaded relief maps, raw heightmaps, and text diagnostics into `build/reports/preset-test-heightmaps/`.
  - `LocalTestReportGenerator.generate(...)` automatically builds a paired offline HTML report with tabs and visual inspections.

### 4.3 Requirements for Adding New Tests

#### Adding a Unit Test:
1. Place the test class under `common/src/test/java/etcodehome/freeterraforged/...`.
2. Use standard JUnit 5 annotations (`@Test`, `@DisplayName`, `@BeforeAll`).
3. If the test outputs images or reports, resolve the output directory dynamically using `resolveOutputDir()` defaulting to `Path.of("build", "reports", "preset-test-heightmaps")`.
4. Run `./gradlew test` to ensure it passes both the `checkPathPurity` check and the JUnit execution. It will automatically be archived into `unit_tests_report.txt`.

#### Adding a System Test (Headless Worldgen Integration):
When adding a new integration test to `PresetValidationRunner.java`:
1. **Implement the Test**:
   - Add the test routine inside `PresetValidationRunner.runAllTests(MinecraftServer server)`.
   - Update the watchdog state before starting:
     ```java
     currentTestName = "presetValidation.<testName>." + loader;
     watchdog.setStep(currentTestName);
     ```
   - Record the outcome in `testRecords`:
     ```java
     testRecords.add(new TestRecord(currentTestName, diagnosticNotes, passed, errorMessage));
     ```
2. **Follow Canonical Token Naming**:
   The test token MUST follow the structure:
   `presetValidation.<testName>.<loader>`
   where `<loader>` is either `fabric` or `neoforge`.

3. **MANDATORY: Register the Test in Expected Test Lists**:
   System test completeness is strictly verified by both the local report generator and the GitHub Actions CI pipeline. If a test is intended to be a permanent, mandatory part of the validation suite, you **MUST** register its base token (without the `.<loader>` suffix) in both locations:
   - **Local Report Generator**: Add the token string to `BASE_TEST_TOKENS` in [LocalTestReportGenerator.java](file:///C:/Modding/MC/FreeTerraForged/common/src/main/java/etcodehome/freeterraforged/test/LocalTestReportGenerator.java#L22-L38).
   - **CI Workflow Validator**: Add the token string to `baseTestTokens` in [.github/workflows/publish-verification.yml](file:///C:/Modding/MC/FreeTerraForged/.github/workflows/publish-verification.yml#L162-L175).

   > [!IMPORTANT]
   > The CI pipeline will explicitly fail if any token in `baseTestTokens` fails to execute on **both** `fabric` and `neoforge`. Keeping these lists synchronized ensures that test omissions or silent aborts are caught immediately.

### 4.4 Interaction with GitHub Actions CI/CD Pipeline

The pull request verification lifecycle is managed by three interacting workflows:

1. **Reset Verification on Push** ([reset-label.yml](file:///C:/Modding/MC/FreeTerraForged/.github/workflows/reset-label.yml)):
   - Listens to `pull_request_target` on `synchronize` (whenever new commits are pushed to an open PR).
   - Automatically strips the `verify-build` label from the PR to ensure unverified code changes cannot bypass testing.

2. **PR Preset Verification** ([verify-pr.yml](file:///C:/Modding/MC/FreeTerraForged/.github/workflows/verify-pr.yml)):
   - Triggered when a maintainer attaches the `verify-build` label to a PR.
   - Runs a parallel matrix job (`fabric` and `neoforge`) on `ubuntu-latest`.
   - Executes `./gradlew :${loader}:runPresetTest --no-daemon`.
   - Ingests `unit_tests_report.txt` and `suite_summary_${loader}_*.txt` to output a rich markdown step summary in GitHub Actions UI.
   - Uploads all generated heightmaps, relief maps, and text reports as workflow artifacts (`preset-test-artifacts-${loader}`).

3. **Publish Verification Report** ([publish-verification.yml](file:///C:/Modding/MC/FreeTerraForged/.github/workflows/publish-verification.yml)):
   - Triggered automatically when `PR Preset Verification` finishes (`workflow_run: completed`).
   - Downloads and consolidates artifacts from all parallel loader runners.
   - Pushes visual heightmap and relief map PNGs to an isolated `verification-assets` orphan branch under `pr-<PR_NUM>/` for permanent inline rendering.
   - **Enforces Gatekeeper Rules**:
     - Asserts that all `baseTestTokens` ran and passed on both loaders (`12 * 2 = 24` minimum).
     - Asserts zero test failures or watchdog timeouts.
     - Asserts unit test suite success (`unit_tests_report.txt`).
   - Publishes an executive summary table comment directly onto the PR discussion, complete with live embedded links to the generated relief maps.
   - Attaches either `verification-passed` or `verification-failed` labels to the PR.
