# ADR-020 supporting material: the Maven conversion spike

This is proposal material for `decisions/ADR-020-maven-build.md`, which is
**proposed, not accepted**. Nothing here is wired into the build, the gate or
CI. When ADR-020 is decided, delete this folder. If it is accepted, its content
moves into the conversion PR.

Spike run on 2026-10-06 against `4a95265`: macOS arm64, JDK 25.0.4 (GraalVM),
Python 3.12, Maven 3.10.0 through the patch's `./mvnw`, and an Android SDK
holding `platforms;android-36` (`platform-36_r02.zip`) and `build-tools;36.0.0`.
Both SDK packages were checked against the SHA-1 published in
`dl.google.com/android/repository/repository2-3.xml`.

## What is here

| File | What it is |
|------|------------|
| `conversion.patch` | The converted build, as a patch against `4a95265`. `git apply --check --binary` passes on `4a95265`. |

The Python scripts that were originally here (pom_contract.py, pom_contract_mutations.py,
runtime_jars.py, count_tests.py) have been superseded by Kotlin test classes in `tools/`,
which run as part of the normal Maven build (`./mvnw -pl tools test`). No extra Maven
dependencies are needed: javax.xml, java.io and java.nio are JDK APIs; kotlin-stdlib and
JUnit are universally provided by the root POM to every module in the reactor.

The patch changes 83 files (+1,829 / −1,509):

- It adds 33 POMs, `mvnw`, `mvnw.cmd`, and `.mvn/wrapper/maven-wrapper.properties`
  with the pinned distribution.
- It turns the app manifest into a template.
- It changes the hard-coded paths in four test files: `ModuleFiles.kt` and
  `AppPrivateStorageTest.kt` in history, `NoFrameworkBytecodeTest.kt` in core,
  and `GateSelfTest.kt` in ui.
- It updates `.gitignore` and `.gitattributes`.
- It deletes every Gradle file.

## Reproduce

You need JDK 25, Python 3.11 or later, and `ANDROID_HOME` set to an SDK that has
`platforms;android-36` and `build-tools;36.0.0`.

    git worktree add ../breaker-maven 4a95265        # throwaway checkout; main is untouched
    cd ../breaker-maven
    git apply --binary ../breaker/decisions/ADR-020-maven-build/conversion.patch
    ./mvnw -B -ntp -Dmaven.test.failure.ignore=true clean verify
    python3 ../breaker/decisions/ADR-020-maven-build/count_tests.py . maven
    python3 ../breaker/decisions/ADR-020-maven-build/pom_contract.py .
    python3 ../breaker/decisions/ADR-020-maven-build/pom_contract_mutations.py .

For the Gradle baseline, use a plain checkout of `4a95265`: run
`./gradlew --no-daemon --no-build-cache --rerun-tasks build`, then
`count_tests.py . gradle`.

`-Dmaven.test.failure.ignore=true` is there only so that the 14 known failures
(see "What still reads Gradle") do not stop the modules after them. The
conversion PR ports those tests and drops the flag.

## Results

### Test parity

| Module | Gradle (`testDebugUnitTest` / `test`) | Maven (surefire) | Maven failing |
|--------|------:|------:|------:|
| android/modules/audio | 151 | 151 | 0 |
| android/modules/core | 211 | 211 | 0 |
| android/modules/format | 77 | 77 | 0 |
| android/modules/history | 435 | 435 | 12 |
| android/modules/transport | 63 | 63 | 0 |
| android/ui | 198 | 198 | 2 |
| shared/modules/format-prompts | 25 | 25 | 0 |
| shared/modules/ui-tokens | 67 | 67 | 0 |
| **total** | **1,227** (0 failing) | **1,227** | **14** |

Surefire's default class-name patterns picked up every test class that Gradle
ran. The conversion PR should still keep a count-parity check, because a test
class that falls outside those patterns would be skipped without any failure.

On the first run, 139 tests failed (7 failures, 132 errors). Every one traced to
four hard-coded paths that point at a Gradle marker file or a Gradle/AGP output
directory:

- history's `ModuleFiles` root markers
- history's `AppPrivateStorageTest` source root
- core's `build/classes/kotlin/main`
- ui's `build/intermediates/built_in_kotlinc/<variant>/…`

Pointing them at `pom.xml` and `target/classes` left 14 failures.

### What still reads Gradle (the 14)

Each of these tests checks the Gradle build itself, so each needs a port, not a
path change:

- history `SqliteNeverShipsTest` (9) and `ScanNeverShipsEverywhereTest` (2) scan
  `build.gradle.kts` files and the version catalog for `sqlite`. The enforcer
  rule below covers the same ground on the resolved graph. Port these to read
  POM scopes, or retire them in favour of the rule.
- history `CardMatchesCodeTest`, one test: "no Room in the build script". Port it
  to read the POM.
- ui `AndroidConfinementGateTest`, two tests that pin AGP's variant names and
  directory layout. Port them to pin `target/classes`.

Python code outside the Kotlin test run also reads Gradle:

- `contract_support.py`'s Gradle half (the include list, `project()` edges, the
  `base` heuristic)
- `test_ui_tokens_contract.py`'s build-block tests
- `gate.py` steps 4 and 5, and `test_gate.py`

### The ported structural contract

`pom_contract.py` is GREEN on the converted tree. All five seeded breaches turn
it RED, each with a named reason:

- an undeclared sibling edge
- a missing required edge
- a module dropped from `<modules>`
- an unregistered module added to `<modules>`
- an `artifactId` that has drifted from its registry key

### Build rules, each proven to fail loudly

- **sqlite-jdbc never ships (enforcer `bannedDependencies`).**
  - Moving history's `sqlite-jdbc` from test scope to compile scope stops the
    build. The message is the card's rule, and the path shown is
    `android_history → sqlite-jdbc`. Restoring the scope makes it pass again.
  - Transitive case: publish a `core` whose POM carries `sqlite-jdbc`, then
    build `audio` on its own so that `core` comes from the repository. The build
    stops on `android_audio → android_core → sqlite-jdbc`. Restoring `core`
    makes it pass again. No text scan can see that path.
- **The Android API surface (`-no-jdk`).** A probe file in `transport` that
  calls `java.net.http.HttpClient`, which is in the desktop JDK but not on
  Android, fails to compile with `Unresolved reference 'http'`. Removing the
  probe makes it compile again.
- **Wrapper pin.** With the pinned SHA-256, `./mvnw -v` reports Maven 3.10.0.
  Change one hex digit and it exits 1 with "Failed to validate Maven
  distribution SHA-256", on the second run as well, leaving no partial install.
  - Apache publishes no SHA-256 for its distributions, so the pinned value was
    computed from the zip.
  - The zip's SHA-512 matched the value published both at Central and at
    `downloads.apache.org`.
  - Its `.asc` signature verified against Apache Maven's `KEYS`: a good
    signature by Tamas Cservenak, EdDSA key `88BE34F94BDB2B5357044E2E3A387D43964143E3`.

### A pitfall the spike hit, and the rule it produced

The first draft gave `android.jar` `system` scope with an
`${env.ANDROID_HOME}` path. Two things went wrong:

- Without `ANDROID_HOME`, the whole reactor fails model validation, including
  JVM-only modules.
- When a module's POM is read back from a repository, Maven treats it as
  invalid and drops its transitive dependencies with only a WARNING. The
  `sqlite-jdbc` ban then passed when it should have failed.

The fix is `provided` scope plus a root-only `install:install-file` from the
SDK. After it, the build logs 0 invalid-POM warnings, JVM-only modules build
without an SDK, and the transitive ban fires. ADR-020 makes three rules out of
this:

- no `system` scope
- the gate goes RED on any `The POM for … is invalid` line
- the enforcer is not trusted without that gate check

### The APK, built without AGP

`./mvnw -pl android/app -am verify` produces `android/app/target/breaker-debug.apk`
(742,530 bytes), and every check passes:

- `apksigner verify` reports Verifies, using v3.
- `zipalign -c -P 16` passes.
- `aapt2 dump badging` reports package `dev.breaker.dictation`, minSdk 30,
  targetSdk 36 and compileSdk 36, the same as AGP's `app-debug.apk` from the
  baseline.

The APK contains `AndroidManifest.xml`, `resources.arsc` (stored), one
`classes.dex`, and the Java resources of the runtime jars: `tokens.css` and
eight `*.kotlin_builtins`. AGP's debug APK holds the same Java resources, but
splits the code across 11 debug dex files and adds an AGP metadata file.

Two findings shaped the pipeline:

- **D8.** build-tools 36.0.0 ships D8 8.10.9, which fails with "Unsupported
  class file major version 69" (Java 25). D8 from `com.android.tools:r8:9.5.22`,
  taken from Google Maven and SHA-1 checked, works, so the root POM pins D8 on
  its own.
- **The manifest.** `aapt2 link` refuses a manifest with no `package`
  attribute, and AGP used to inject it. The manifest becomes a template filled
  in from POM properties, so the app's identity is still declared in one place.

Java resources have to be merged by the pipeline. `format-prompts` loads its
prompt from the classpath, so an APK that left them out would pass every JVM
test and then fail on the phone. The conversion PR should add a check that
every non-class runtime resource is present in the APK.

### Timing (not comparable, not a reason either way)

- Gradle baseline: 3 min 26 s, 1,174 tasks. Warm Gradle caches and no daemon;
  it builds debug and release and runs Lint on 18 modules.
- Maven: 2 min 14 s warm, 3 min 45 s from an empty local repository. Debug APK
  only, no Lint.

## What the spike did not do

- Install or launch the APK on a device. The app has no entry point yet, and
  the Gradle build has the same gap.
- Android Lint: the baseline ran 112 lint tasks and the Maven build runs none.
- Native libraries (sherpa-onnx `.so`, Phase 3) and release signing (Phase 16).
- Port the 14 tests above, `contract_support.py`, `gate.py`, CI, the cards and
  the docs.
- Run on Windows. Two pipeline steps use `sh`.
- Look at the Phase 0 fork's dependencies (AARs? Compose? resources?). This
  decides ADR-020's comparison.

## Versions read for this spike (2026-10-06)

`AGENTS.md` says to run `tools/latest.sh` first, but that script does not exist
in this tree. Each version below was read with the documented fallback instead,
`maven-metadata.xml`, taking the highest version that is not an alpha, beta, rc
or milestone. The conversion PR adds these coordinates to `tools/latest.sh`.

| Artifact | Version | Read from |
|----------|---------|-----------|
| Maven distribution | 3.10.0 (4.0.0 is still rc) | https://repo1.maven.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml |
| Maven Wrapper (plugin and scripts) | 3.3.4 | https://repo1.maven.org/maven2/org/apache/maven/plugins/maven-wrapper-plugin/maven-metadata.xml |
| kotlin-maven-plugin, kotlin-stdlib | 2.4.20 | https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-maven-plugin/maven-metadata.xml |
| maven-surefire-plugin | 3.6.0 | https://repo1.maven.org/maven2/org/apache/maven/plugins/maven-surefire-plugin/maven-metadata.xml |
| maven-enforcer-plugin | 3.6.3 | https://repo1.maven.org/maven2/org/apache/maven/plugins/maven-enforcer-plugin/maven-metadata.xml |
| maven-dependency-plugin | 3.11.0 | https://repo1.maven.org/maven2/org/apache/maven/plugins/maven-dependency-plugin/maven-metadata.xml |
| exec-maven-plugin | 3.6.4 | https://repo1.maven.org/maven2/org/codehaus/mojo/exec-maven-plugin/maven-metadata.xml |
| maven-clean / resources / compiler / jar / install / deploy / site | 3.5.0 / 3.5.0 / 3.16.0 / 3.5.1 / 3.2.0 / 3.2.0 / 3.22.0 | https://repo1.maven.org/maven2/org/apache/maven/plugins/<artifact>/maven-metadata.xml |
| com.android.tools:r8 (D8) | 9.5.22 | https://dl.google.com/android/maven2/com/android/tools/r8/maven-metadata.xml |
| junit, sqlite-jdbc | 4.13.2, 3.53.4.0 (unchanged) | https://repo1.maven.org/maven2/junit/junit/maven-metadata.xml, https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/maven-metadata.xml |
| android-maven-plugin (not used) | 4.6.0, last published 2019-05-09 | https://repo1.maven.org/maven2/com/simpligility/maven/plugins/android-maven-plugin/maven-metadata.xml |
