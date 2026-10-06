# Breaker: Gradle → Maven, and what an AI-built project should actually pin

This is an addendum to `decisions/ADR-020-maven-build.md`: a plain accounting of
every Gradle/AGP feature the conversion touched, what replaced it, and a direct
opinion on build-tooling practice for a project whose contributors are mostly
AI agents, not a reflexive case for "enterprise" Maven conventions.

## Every major Gradle transition, one by one

**Build script format.** `build.gradle.kts` (Kotlin DSL, imperative, task graph)
→ `pom.xml` (declarative XML, lifecycle phases). Gradle's task graph is more
expressive than Maven's fixed lifecycle; Breaker's actual build — compile, test,
package, sign — fits the fixed lifecycle exactly, so nothing was lost here.

**Version catalog.** `gradle/libs.versions.toml` → root `pom.xml`
`<properties>`, one per dependency/plugin, each carrying a `read YYYY-MM-DD from
<url>` provenance comment (a Breaker convention, not stock Maven). Functionally
identical: one file, one source of truth, every module inherits it.

**Dependency resolution.** Gradle's configurations (`implementation`,
`testImplementation`, `api`) → Maven scopes (`compile`, `test`, `provided`).
Narrower vocabulary, same job. Transitive dependency correctness was verified
with `mvn help:effective-pom` per module (ADR-020's "effective pom" audit) —
no module re-declares what the root already provides.

**Multi-module wiring.** `settings.gradle.kts` `include(...)` + `project(":a:b")`
references → root `pom.xml` `<modules>` (walked recursively) + `<dependency>`
with `dev.breaker` groupId. Maven's reactor and Gradle's project graph solve
the same problem; Maven's is less flexible (strict parent/child tree vs.
Gradle's arbitrary graph) but Breaker's module tree is already a tree.

**D8 dexing / desugaring.** AGP's `dexDebug`/`desugarDebugFileDependencies`
tasks → direct D8 invocation via `exec-maven-plugin`, with `-desugaring` gated
behind the non-default `gradle-migration` profile. AGP's D8 8.10.9 (bundled
with build-tools 36.0.0) rejects Java 25 class files outright (class file
major version 69); this is why the conversion runs R8 9.5.22 directly instead
— a newer artifact than the SDK's bundled D8, pulled from Google's Maven repo
by coordinate rather than installed as an SDK component.

**Duplicate class checking.** AGP's `checkDebugDuplicateClasses` →
`maven-enforcer-plugin`'s `banDuplicateClasses` rule, same non-default
`gradle-migration` profile. Enforcer catches it earlier (dependency resolution
time, not dex time).

**APK assembly & signing.** AGP's `assembleDebug`/`packageDebug`/
`validateSigningDebug` → explicit `exec-maven-plugin` steps: aapt2 (resources),
D8 (dex), `jar` (zip assembly), zipalign, `apksigner sign` + `apksigner verify`.
Nothing hidden: the APK pipeline that AGP ran as opaque tasks is now five named
steps anyone can read and re-run individually.

**AAR packaging.** AGP's `bundleDebugAar` per library module → an
`exec-maven-plugin` zip step (`classes.jar` + manifest + `res/` + `R.txt`) at
the `android` parent POM, inherited by every library module, skipped on the
container itself via a `maven.bundle-aar.skip` property. Equivalent output,
same caveat as Gradle: it's a stub until a module actually has `res/`.

**Lint.** AGP's `lintDebug`/`lintAnalyzeDebug` (148 tasks across 18 modules x
4 variants in the Gradle baseline) → a single `exec-maven-plugin` step invoking
the `lint` CLI from Android cmdline-tools, inherited by the `android` parent,
skippable via `maven.lint.skip` (useful locally where cmdline-tools isn't
installed; CI installs it and runs for real).

**Instrumented tests, native lib merging, resource processing.** No Maven
equivalent attempted — none of these have any work to do yet (no `androidTest`
harness exists; no module has JNI libs outside the illustrative
`phase3-sherpa-onnx` profile; no module has `res/` yet). Revisit when a module
actually needs one, not before.

**The wrapper itself.** Gradle wrapper (distribution SHA-256 pinned, Security
Review fix #4) → Maven wrapper (`.mvn/wrapper/maven-wrapper.properties`,
distribution SHA-256 pinned the same way, tamper-tested the same way). No
functional change to the supply-chain guarantee; same mechanism, different
tool.

## Dependency/plugin version audit (this pass)

Ran `org.codehaus.mojo:versions-maven-plugin:2.22.0:display-dependency-updates`
and `:display-plugin-updates` against the full reactor. Result: every pinned
dependency and plugin is already at its newest **stable** release; the one
candidate bump (`kotlin-stdlib`/`kotlin-maven-plugin` 2.4.20 → 2.5.0-Beta1) is a
pre-release and is excluded under the repo's own rule (no alpha/beta/rc/M/
snapshot/dev/eap versions). Nothing to bump this round — recorded here so the
next pass has a diff to compare against, not a blank slate.

## POM normalization pass (this round)

- Found and removed a stale `Registry: modules.toml [module.*]` header comment
  in all 32 module POMs — left over from before `modules.toml` was retired in
  favor of the reactor itself as the registry.
- Re-ran the per-module `<dependency>` audit: no module declares a version,
  scope, or dependency that duplicates what the root already provides. Every
  child's `<build><plugins>` has exactly the one plugin declaration it needs to
  pull in the root's `pluginManagement` executions (Maven requires that
  declaration to exist somewhere in the child even when every other detail is
  inherited) — nothing more.
- No further trimming found. The reactor was already minimal going into this
  pass; this round confirms that, it doesn't change it.

## Pragmatic advice for an AI-built, AI-maintained project

Set aside what a human enterprise team would do here and ask what actually
helps an AI contributor (or ten of them, working in parallel, across sessions)
ship correct code.

**Fewer tools, not more coverage.** Gradle's main advantage over Maven is
flexibility — arbitrary task graphs, incremental builds, build caching, a
general-purpose DSL. All of that is optimized for human developer iteration
speed on a laptop. An AI agent doesn't get bored waiting for a clean build, and
a clean, deterministic, declarative lifecycle (Maven's actual design) is
*easier for an agent to reason about* than an imperative script it has to
simulate mentally to predict the task graph. Prefer the less flexible, more
legible tool when both solve the problem.

**One source of truth per fact, enforced mechanically, not by convention.**
The `modules.toml` retirement in this PR is the concrete example: a second
registry that had to be kept in sync by hand with the POM reactor was a
standing opportunity for drift, and it drifted (the `tools` module landed in
the reactor before it landed in the registry, failing a contract test). An
AI agent is exactly as capable of forgetting to update a second file as a
human is — more views of the same fact is not more safety, it's more surface
area for exactly one of them to go stale.

**Prefer a build-time failure to a review-time checklist.** Every Python
contract check this PR retired in favor of a Maven/Kotlin equivalent
(`PomContractTest.kt`, `RepoContractTest.kt`) moved from "a thing a human or
agent has to remember to run" to "a thing `./mvnw clean install` cannot skip."
An agent that forgets to run a separate check script is a certainty over
enough sessions; an agent that forgets to run the build it was asked to verify
is not.

**Comments that say *why*, dated and sourced, survive agent handoffs better
than tribal knowledge.** The `# read YYYY-MM-DD from <url>` convention on every
version pin, and the inline comments explaining *why* R8 9.5.22 is pinned
instead of the SDK's bundled D8 (Java 25 class file rejection), are there
because no single agent session holds the full history. The next agent that
touches this file has the reasoning in the file, not in a conversation it
never saw.

**Skip the enterprise defaults that exist to coordinate large human teams.**
No multi-module release plugin, no site generation, no distribution management
beyond what's needed, no SNAPSHOT versioning ceremony beyond the one `0-SNAPSHOT`
this repo actually uses. Maven ships a lot of machinery for teams coordinating
releases across many consumers; Breaker has one consumer (itself) and ships
a signed debug APK, not a published artifact. Every plugin added here was
added because a concrete Gradle/AGP feature needed replacing — the three
non-default profiles (`gradle-migration`, `phase3-sherpa-onnx`, `proguard`)
are deliberately inert until a maintainer opts in, instead of defaulting to
"on" the way an enterprise archetype generator would.

**Make the AI team's actual failure mode the thing you guard against.**
The real risk in an agent-heavy contributor base isn't malicious intent, it's
confident, plausible-looking drift — a card that no longer matches the code,
a registry that no longer matches the tree, a test that passes without
checking anything. Every structural test added in this PR (`RepoContractTest`,
the boundary/bijection checks in `PomContractTest`) targets that exact failure
mode, not a hypothetical supply-chain or build-reproducibility concern Maven
already handles natively (the wrapper's SHA-256 pin covers that).
