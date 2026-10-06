# tools

Build verification tests. Kotlin test classes that run as part of the normal
Maven build (`./mvnw -pl tools test`). Fully replace `tools/check_repo.py`,
`tools/gate.py`, `tests/contract/` and `tests/unit/` — no modules.toml, no
Python contract tier. The root `pom.xml` reactor is read directly.

## What is here

`PomContractTest.kt` — verifies:

- every reactor module has a `pom.xml`
- `dev.breaker` dependencies stay within each module's declared boundaries (both directions)
- every module's `artifactId` is consistent with its folder

`RepoContractTest.kt` — verifies:

- every reactor module folder has `AGENTS.md` and `README.md`
- every card carries the nine required sections
- no module depends on itself; no dependency cycles anywhere in the reactor
- no folder with an `AGENTS.md` is missing from the reactor
- parent READMEs (`android/`, `server/`, `shared/`) name their sub-modules
- no NUL bytes in tracked source/config files (outside intentional binary-content test fixtures)

## Running

    ./mvnw -pl tools test

Or as part of the full build: `./mvnw clean install` runs this module along
with everything else in the reactor.

No extra dependencies. `javax.xml`, `java.io`, `kotlin-stdlib`, and JUnit are
all available from the JDK or the root POM.
