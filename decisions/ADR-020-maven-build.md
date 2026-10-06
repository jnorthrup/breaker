# ADR-020: One Maven build replaces Gradle and the Android Gradle Plugin

**Status:** proposed
**Date:** set on acceptance (proposed 2026-10-06)

Amends ADR-017 (its "built by the repo's Gradle build" clause) and ADR-019 (its
"`base`-plugin build file"). The spike behind every number here, the conversion
patch and the scripts that check it are in `decisions/ADR-020-maven-build/`.

## Context

The build is Gradle 9.8.0 with AGP 9.4.1 and Kotlin 2.4.20: 34 Kotlin build
scripts, a version catalog and `gradle.properties`, 1,128 lines in all. In the
repo's first six days, 4 of 26 commits touched them. The last one, the
toolchain bump (#53), changed 29 files and had to rewrite two `ui` gate tests
because AGP 9 moved its compiled class output.

This repo enforces its structure by reading the build. 13 files (3,727 lines in
all, though not every line is about Gradle) read Gradle's file formats or its
output layout:

- `tests/contract/contract_support.py`: the registry against the `include()`
  list, `depends_on` against `project(":…")`, and a plugin-id heuristic for
  "publishes an artifact".
- `test_ui_tokens_contract.py`.
- `tools/gate.py` and its tests.
- `history`'s `BuildScriptScan` family: `sqlite-jdbc` appears only in test
  configurations.
- The bytecode gates in `core` and `ui`, which find classes by AGP's and KGP's
  output paths.

A Kotlin build script is a program, and these readers are regexes over it.
`BuildScriptScan` says so itself: "It reads text. It is not the resolved
dependency graph", and a build that brings SQLite in some way the text does not
show gets past it. The real answer comes from
`./gradlew … dependencies --configuration releaseRuntimeClasspath`, which no
test runs.

The cost of changing build tools is as low now as it will ever be. The forked
base app (Phase 0) is not on `main`. 12 of the 17 modules under
`android/modules` have no source. No module ships Android resources or a
library manifest, and no dependency is an AAR. Each module written against AGP
from here on raises the cost, and the fork raises it by an amount nobody has
measured yet.

## Decision

1. **Maven replaces Gradle, AGP and KGP as the one build.** The entry point is
   `./mvnw`: Maven 3.10.0 through Maven Wrapper 3.3.4, `only-script` type, so
   no wrapper jar is committed. Its distribution is pinned by SHA-256 in
   `.mvn/wrapper/maven-wrapper.properties` (Security Review fix #4 [1], carried
   over). The same change deletes `settings.gradle.kts`, every
   `build.gradle.kts`, `gradle/`, `gradlew*` and `gradle.properties`. There is
   no period with both builds.
2. **The reactor is the registry.** The root `pom.xml` (`dev.breaker`) is the
   reactor root and the ancestor of every module's POM: directly, or through
   `android/pom.xml` (item 4). Each registered module has a
   `pom.xml` whose `artifactId` is its `modules.toml` key. The containers
   (`android`, `server`, `shared`) list their children in `<modules>`.
   `<packaging>pom</packaging>` replaces today's `base` plugin and publishes
   nothing. An edge to such a module is declared `<type>pom</type>`.
3. **Every version lives once, in the root POM's `<properties>`.** Each carries
   `<!-- read YYYY-MM-DD from <url> -->`, the rule `gradle/libs.versions.toml`
   follows today. This also ends today's 63 copies of the JDK 25 pin.
4. **Kotlin compiles through `kotlin-maven-plugin`** at the Kotlin version
   above, with `jvmTarget` 25. The pure-JVM modules (`core`, `ui-tokens`,
   `format-prompts`) take the root POM as parent. Every other module under
   `android/` takes `android/pom.xml` as parent, which gives it two things:
   - `android.jar` for API 36, in `provided` scope. The root build copies it
     from `$ANDROID_HOME` (installed and checksum-checked by sdkmanager) into the
     local repository. It is never a `system`-scope path; see the spike.
   - `-no-jdk` on their main compile, so Android code sees the Android API
     surface and not the desktop JDK.
5. **`android/app/pom.xml` builds the APK from published, pinned tools only.**
   The steps:
   - fill the manifest's identity from POM properties
   - `aapt2 link`
   - D8 from `com.android.tools:r8`, pinned in the root POM
   - add the Java resources of the runtime jars
   - `zipalign -P 16`
   - `apksigner`
   - check the signature, the alignment and the badging in `verify`

   There is no third-party Android Maven plugin. Release signing stays Phase
   16's: its key never enters the repository.
6. **Build rules replace text scans where the build can enforce them.**
   `maven-enforcer-plugin` enforces three rules:
   - `bannedDependencies`: `sqlite-jdbc` outside test scope, checked on the
     resolved graph, transitive edges included.
   - `requirePluginVersions`
   - `requireJavaVersion [25,)`

   The structural contract tests read POMs as XML (standard library only, as
   ADR-016 requires). `tools/gate.py` runs `./mvnw -B -ntp clean verify`,
   counts `target/surefire-reports`, and goes RED on any
   `The POM for … is invalid` line.
7. **The Android shape stays as it is today, now as a rule.** Library modules
   ship no Android resources and no manifest. Every dependency is a plain JAR.

## Reasons

The decision is a technical-debt comparison. It converts if the Gradle side is
larger:

    Σ over the horizon [ bumps × c_bump  +  build invariants × c_scan ]
        >  C_convert  +  Σ over the horizon [ c_pipeline + c_lint + c_ide ]

The counts below are measured (spike, 2026-10-06). Unit costs are left to the
reviewer.

**What staying on Gradle costs, and keeps costing:**

- *c_bump.* Gradle, AGP and KGP version separately, and every bump has to
  re-prove them together. #53 touched 29 files. It rewrote two `ui` gate tests
  because AGP moved its output.
- *c_scan.* Each build invariant is one more partial parser of Kotlin script.
  13 files read the build today, and none of them can see a transitive
  dependency.
- *Freshness machinery.* Gradle may skip a test task as UP-TO-DATE, FROM-CACHE
  or NO-SOURCE. So `gate.py` snapshots JUnit XML mtimes, and CI has to pass
  `--no-daemon --no-build-cache --rerun-tasks` to keep the gate honest.
  `clean verify` is fresh by construction.

**What Maven costs:**

- *C_convert.* The conversion patch changes 83 files (+1,829 / −1,509), with
  33 POMs totalling 1,307 lines against Gradle's 1,128. Maven is not shorter:
  the APK steps AGP ran out of sight are now 234 visible lines. After the patch,
  these still read Gradle and have to be ported:
  - `contract_support.py`'s Gradle half. The prototype is about 80 lines.
  - `gate.py` steps 4 and 5
  - 12 `history` tests and 2 `ui` tests
  - every card's Test Locations
  - the docs and CI
- *c_pipeline.* We own the composition of the APK steps; the tools themselves
  are maintained upstream. D8 has to keep up with the bytecode level:
  build-tools 36.0.0's D8 cannot read Java 25 classes.
- *c_lint, c_ide.* The Gradle build runs Android Lint over all 18 Android
  modules (112 lint tasks in the baseline run), and the Maven build runs none.
  Android Studio's Android tooling expects a Gradle project model (not tested in
  the spike). AGP's variants, AAR consumption, resource merging, R8 shrinking
  and native-library merging do not exist until we build them.

**Why the comparison favours Maven today:**

- Invariants become data and rules instead of regexes. The POM contract
  prototype caught all 5 seeded breaches. The enforcer stopped `sqlite-jdbc`
  both directly and through a dependency's POM
  (`android_audio → android_core → sqlite-jdbc`), which no text scan can see.
- Maven's output layout (`target/classes`) has not moved since Maven 2. On the
  first run 139 tests failed. Changing four hard-coded paths left 14, and those
  14 test the Gradle build itself. Those paths change once, not on every bump.
- Every test still runs: 1,227 tests, with the same count in each of the 8
  modules as the Gradle baseline.
- The APK is buildable without AGP. The spike produced a signed, 16 KiB-aligned
  APK whose identity (package, minSdk, targetSdk, compileSdk) matches AGP's
  debug APK.
- A smaller supply-chain surface. No binary wrapper jar is committed. The
  distribution pin fails on a one-character tamper. Every plugin version is
  pinned and enforced.

**What would make the comparison favour Gradle:** the Phase 0 fork, or any later
phase, needing AARs, resources spread across modules, or Compose. Item 7 makes
those a rule. Without it, the Maven side becomes reimplementing AGP.

## Consequences

Rules in:
- One Maven reactor, `./mvnw` only; a version is added only in the root POM,
  with its read date and URL.
- Structural checks that parse POMs, and build rules that enforce on the
  resolved graph.
- A cut-over PR for the whole toolchain, as #53 was. It includes the test
  changes in `history`, `ui` and `core` that the conversion forces.
- The fork arrives in Phase 0 as Maven. Bug Hunt re-reviews the build supply
  chain, including Maven plugins and the Google repository, and re-verifies fix
  #4 against the Maven pin [1].
- If accepted, these change in the same PR:
  - ADR-017's build clause and ADR-019's build-file clause (amended in place)
  - `AGENTS.md` standing orders (`./mvnw`, root-POM pins, Maven coordinates in
    `tools/latest.sh`)
  - the wording of fix #4 in `docs/03`, `docs/04` (Phase 0 and 1 rows),
    `ARCHITECTURE.md`, `README.md` and `MODULES.md`
  - "Gradle dependency audit" in `docs/03`, which becomes a Maven one
  - `.github/CONTRIBUTING.md`, `.github/workflows/checks.yml` (the
    wrapper-validation step goes) and `.github/ISSUE_TEMPLATE/proposal.yml`
  - `decisions/README.md`'s pointer to the catalog
  - every card's Test Locations: `./gradlew :a:b:test` becomes
    `./mvnw -pl a/b -am test`

Rules out:
- Gradle anywhere in the repo, including a second build kept "for the IDE".
- Third-party Android Maven plugins. The only one, `android-maven-plugin`, last
  published a release (4.6.0) on 2019-05-09.
- AAR dependencies, Android resources or manifests in library modules, and
  `system`-scope dependencies.
- Release keys in the repository.

Open before acceptance, because the spike did not settle them:
1. The fork's dependency list. It decides the comparison.
2. Lint parity: either the SDK's standalone `lint` run over the reactor, or the
   loss recorded here as an amendment.
3. A device smoke test of a Maven-built APK. The app has no entry point yet;
   the Gradle build has the same gap.
4. The steps for native libraries (Phase 3) and release signing (Phase 16).
5. Windows contributors: two pipeline steps run through `sh`.
